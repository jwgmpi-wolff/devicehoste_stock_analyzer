import { createServer } from "node:http";
import { pathToFileURL } from "node:url";

const SYMBOL = /^[A-Z0-9.-]{1,10}$/;

export function createRequestHandler({ fetchImpl = fetch, env = process.env, now = () => new Date() } = {}) {
  return async function requestHandler(request, response) {
    try {
      const url = new URL(request.url, `http://${request.headers.host ?? "localhost"}`);
      const match = url.pathname.match(/^\/v1\/(quote|candles|news)\/([^/]+)$/);
      if (request.method !== "GET" || !match) return sendError(response, 404, "Not found");
      const symbol = decodeURIComponent(match[2]).trim().toUpperCase();
      if (!SYMBOL.test(symbol)) return sendError(response, 404, "Unsupported symbol");

      const provider = (env.MARKET_DATA_PROVIDER ?? "finnhub").toLowerCase();
      const result = provider === "alphavantage"
        ? await alphaVantageRequest(match[1], symbol, url, fetchImpl, env, now)
        : provider === "finnhub"
          ? await finnhubRequest(match[1], symbol, url, fetchImpl, env, now)
          : providerError(503, "Configured provider is unsupported");
      sendJson(response, 200, result);
    } catch (error) {
      const status = Number.isInteger(error.status) ? error.status : 503;
      console.warn(`Market provider request failed: type=${error.name ?? "ProviderError"} status=${status}`);
      sendError(response, status, error.publicMessage ?? "Provider unavailable");
    }
  };
}

async function finnhubRequest(resource, symbol, url, fetchImpl, env, now) {
  const apiKey = requireSecret(env.FINNHUB_API_KEY, "FINNHUB_API_KEY");
  if (resource === "news") {
    const to = dateOnly(now());
    const from = dateOnly(new Date(now().getTime() - 7 * 86_400_000));
    const endpoint = `https://finnhub.io/api/v1/company-news?symbol=${encodeURIComponent(symbol)}&from=${from}&to=${to}&token=${encodeURIComponent(apiKey)}`;
    const data = await providerJson(fetchImpl, endpoint);
    if (!Array.isArray(data)) providerError(502, "Provider returned invalid news data");
    return {
      provider: "Finnhub",
      retrievedAt: now().toISOString(),
      items: data.filter(article => article.datetime > 0 && article.headline && article.source).slice(0, 30).map(article => ({
        score: headlineSentiment(article.headline),
        source: String(article.source),
        publishedAt: new Date(article.datetime * 1000).toISOString(),
        headline: String(article.headline),
        url: validHttpUrl(article.url),
        scoringMethod: "Deterministic headline lexicon",
      })),
    };
  }
  if (resource === "quote") {
    const data = await providerJson(fetchImpl, `https://finnhub.io/api/v1/quote?symbol=${encodeURIComponent(symbol)}&token=${encodeURIComponent(apiKey)}`);
    if (!(data.c > 0) || !(data.t > 0)) providerError(404, "Unsupported symbol or quote unavailable");
    return { symbol, price: data.c, timestamp: new Date(data.t * 1000).toISOString(), provider: "Finnhub" };
  }

  const interval = candleInterval(url);
  const range = boundedInteger(url.searchParams.get("range"), 120, 10, 525_600);
  const to = Math.floor(now().getTime() / 1000);
  const from = to - range * 60;
  const resolution = interval === 1_440 ? "D" : "1";
  const endpoint = `https://finnhub.io/api/v1/stock/candle?symbol=${encodeURIComponent(symbol)}&resolution=${resolution}&from=${from}&to=${to}&token=${encodeURIComponent(apiKey)}`;
  const data = await providerJson(fetchImpl, endpoint);
  if (data.s === "no_data") providerError(423, "Market closed or no recent candles available");
  if (data.s !== "ok" || !sameLength(data.c, data.h, data.l, data.o, data.t, data.v)) providerError(502, "Provider returned invalid candle data");
  return {
    provider: "Finnhub",
    retrievedAt: now().toISOString(),
    intervalMinutes: interval,
    candles: data.t.map((timestamp, index) => ({
      timestamp: new Date(timestamp * 1000).toISOString(),
      open: data.o[index], high: data.h[index], low: data.l[index], close: data.c[index], volume: data.v[index],
    })),
  };
}

async function alphaVantageRequest(resource, symbol, url, fetchImpl, env, now) {
  const apiKey = requireSecret(env.ALPHA_VANTAGE_API_KEY, "ALPHA_VANTAGE_API_KEY");
  if (resource === "news") {
    const endpoint = `https://www.alphavantage.co/query?function=NEWS_SENTIMENT&tickers=${encodeURIComponent(symbol)}&limit=30&sort=LATEST&apikey=${encodeURIComponent(apiKey)}`;
    const data = await providerJson(fetchImpl, endpoint);
    if (data.Note || data.Information) providerError(429, "Provider rate limit exceeded");
    if (!Array.isArray(data.feed)) providerError(502, "Provider returned invalid news data");
    return {
      provider: "Alpha Vantage",
      retrievedAt: now().toISOString(),
      items: data.feed.flatMap(article => {
        const ticker = article.ticker_sentiment?.find(item => String(item.ticker).toUpperCase() === symbol);
        const score = Number(ticker?.ticker_sentiment_score);
        if (!Number.isFinite(score) || !article.title || !article.source || !article.time_published) return [];
        return [{
          score: Math.max(-1, Math.min(1, score)),
          source: String(article.source),
          publishedAt: alphaNewsTimeToIso(article.time_published),
          headline: String(article.title),
          url: validHttpUrl(article.url),
          scoringMethod: "Alpha Vantage ticker sentiment",
        }];
      }),
    };
  }
  const interval = resource === "candles" ? candleInterval(url) : 1;
  const daily = interval === 1_440;
  const functionName = daily ? "TIME_SERIES_DAILY" : "TIME_SERIES_INTRADAY";
  const intervalParameter = daily ? "" : "&interval=1min";
  const endpoint = `https://www.alphavantage.co/query?function=${functionName}&symbol=${encodeURIComponent(symbol)}${intervalParameter}&outputsize=compact&apikey=${encodeURIComponent(apiKey)}`;
  const data = await providerJson(fetchImpl, endpoint);
  if (data.Note || data.Information) providerError(429, "Provider rate limit exceeded");
  if (data["Error Message"]) providerError(404, "Unsupported symbol");
  const metadata = data["Meta Data"];
  const series = data[daily ? "Time Series (Daily)" : "Time Series (1min)"];
  if (!metadata || !series) providerError(502, "Provider returned invalid candle data");
  const timeZone = metadata["6. Time Zone"];
  const candles = Object.entries(series).map(([timestamp, values]) => ({
    timestamp: daily ? dailyDateToIso(timestamp, timeZone) : localMarketTimeToIso(timestamp, timeZone),
    open: positiveNumber(values["1. open"]),
    high: positiveNumber(values["2. high"]),
    low: positiveNumber(values["3. low"]),
    close: positiveNumber(values["4. close"]),
    volume: nonNegativeInteger(values["5. volume"]),
  })).sort((first, second) => first.timestamp.localeCompare(second.timestamp));
  if (!candles.length) providerError(423, "Market closed or no recent candles available");
  if (resource === "quote") {
    const latest = candles.at(-1);
    return { symbol, price: latest.close, timestamp: latest.timestamp, provider: "Alpha Vantage" };
  }
  return { provider: "Alpha Vantage", retrievedAt: now().toISOString(), intervalMinutes: interval, candles };
}

async function providerJson(fetchImpl, endpoint) {
  const response = await fetchImpl(endpoint, { headers: { Accept: "application/json" } });
  if (response.status === 429) providerError(429, "Provider rate limit exceeded");
  if (!response.ok) providerError(503, "Provider unavailable");
  try { return await response.json(); } catch { providerError(502, "Provider returned malformed JSON"); }
}

function localMarketTimeToIso(value, timeZone) {
  if (!/^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$/.test(value) || !timeZone) providerError(502, "Provider timestamp is invalid");
  const [date, time] = value.split(" ");
  const [year, month, day] = date.split("-").map(Number);
  const [hour, minute, second] = time.split(":").map(Number);
  const guess = Date.UTC(year, month - 1, day, hour, minute, second);
  const formatter = new Intl.DateTimeFormat("en-US", {
    timeZone, hour12: false, year: "numeric", month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit", second: "2-digit",
  });
  const parts = Object.fromEntries(formatter.formatToParts(new Date(guess)).filter(part => part.type !== "literal").map(part => [part.type, Number(part.value)]));
  const represented = Date.UTC(parts.year, parts.month - 1, parts.day, parts.hour % 24, parts.minute, parts.second);
  return new Date(guess - (represented - guess)).toISOString();
}

function requireSecret(value, name) {
  if (!value) providerError(503, `${name} is not configured on the proxy`);
  return value;
}

function positiveNumber(value) {
  const parsed = Number(value);
  if (!(parsed > 0)) providerError(502, "Provider returned an invalid numeric value");
  return parsed;
}

function nonNegativeInteger(value) {
  const parsed = Number(value);
  if (!Number.isSafeInteger(parsed) || parsed < 0) providerError(502, "Provider returned invalid volume");
  return parsed;
}

function boundedInteger(value, fallback, minimum, maximum) {
  const parsed = Number(value ?? fallback);
  return Number.isInteger(parsed) && parsed >= minimum && parsed <= maximum ? parsed : fallback;
}

function sameLength(...values) {
  return values.length > 0 && values.every(value => Array.isArray(value) && value.length > 0 && value.length === values[0].length);
}

function providerError(status, publicMessage) {
  const error = new Error(publicMessage);
  error.name = "ProviderError";
  error.status = status;
  error.publicMessage = publicMessage;
  throw error;
}

function sendJson(response, status, value) {
  response.writeHead(status, { "Content-Type": "application/json", "Cache-Control": "no-store" });
  response.end(JSON.stringify(value));
}

function sendError(response, status, message) { sendJson(response, status, { error: message }); }

if (import.meta.url === pathToFileURL(process.argv[1]).href) {
  const port = boundedInteger(process.env.PORT, 8787, 1, 65_535);
  createServer(createRequestHandler()).listen(port, () => console.log(`Market data proxy listening on port ${port}`));
}

function dailyDateToIso(value, timeZone) {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) providerError(502, "Provider daily timestamp is invalid");
  return localMarketTimeToIso(`${value} 00:00:00`, timeZone);
}

function candleInterval(url) {
  const interval = boundedInteger(url.searchParams.get("interval"), 1, 1, 1_440);
  if (interval !== 1 && interval !== 1_440) providerError(400, "Unsupported candle interval");
  return interval;
}

function headlineSentiment(headline) {
  const positive = new Set(["beat", "beats", "bullish", "gain", "gains", "growth", "higher", "improve", "improves", "profit", "profits", "raise", "raises", "record", "surge", "surges", "upgrade", "upgrades"]);
  const negative = new Set(["bearish", "cut", "cuts", "decline", "declines", "downgrade", "downgrades", "drop", "drops", "fall", "falls", "fraud", "investigation", "lawsuit", "loss", "losses", "lower", "miss", "misses", "risk", "risks"]);
  const words = String(headline).toLowerCase().match(/[a-z]+/g) ?? [];
  const raw = words.reduce((sum, word) => sum + (positive.has(word) ? 1 : 0) - (negative.has(word) ? 1 : 0), 0);
  return Math.max(-1, Math.min(1, raw / 3));
}

function alphaNewsTimeToIso(value) {
  const match = String(value).match(/^(\d{4})(\d{2})(\d{2})T(\d{2})(\d{2})(\d{2})$/);
  if (!match) providerError(502, "Provider news timestamp is invalid");
  return new Date(Date.UTC(...match.slice(1).map(Number).map((part, index) => index === 1 ? part - 1 : part))).toISOString();
}

function dateOnly(value) { return value.toISOString().slice(0, 10); }

function validHttpUrl(value) {
  try {
    const parsed = new URL(value);
    return parsed.protocol === "https:" || parsed.protocol === "http:" ? parsed.toString() : null;
  } catch { return null; }
}