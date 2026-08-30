import assert from "node:assert/strict";
import { once } from "node:events";
import { createServer } from "node:http";
import test from "node:test";
import { createRequestHandler } from "../server.mjs";

test("normalizes a timestamped Finnhub quote without returning the key", async () => {
  const providerFetch = async url => {
    assert.match(url, /token=server-secret/);
    return jsonResponse({ c: 421.25, t: 1786460340 });
  };
  const response = await invoke(createRequestHandler({ fetchImpl: providerFetch, env: { MARKET_DATA_PROVIDER: "finnhub", FINNHUB_API_KEY: "server-secret" } }), "/v1/quote/MSFT");
  assert.equal(response.status, 200);
  assert.equal(response.body.provider, "Finnhub");
  assert.equal(response.body.price, 421.25);
  assert.match(response.body.timestamp, /^\d{4}-\d{2}-\d{2}T/);
  assert.doesNotMatch(JSON.stringify(response.body), /server-secret/);
});

test("maps provider throttling without generating market values", async () => {
  const response = await invoke(createRequestHandler({
    fetchImpl: async () => jsonResponse({}, 429),
    env: { MARKET_DATA_PROVIDER: "finnhub", FINNHUB_API_KEY: "server-secret" },
  }), "/v1/quote/MSFT");
  assert.equal(response.status, 429);
  assert.deepEqual(response.body, { error: "Provider rate limit exceeded" });
});

test("rejects unsupported symbols before calling a provider", async () => {
  let called = false;
  const response = await invoke(createRequestHandler({ fetchImpl: async () => { called = true; } }), "/v1/quote/not%20valid");
  assert.equal(response.status, 404);
  assert.equal(called, false);
});

test("requests and normalizes Finnhub daily candles for day projections", async () => {
  const providerFetch = async url => {
    assert.match(url, /resolution=D/);
    return jsonResponse({
      s: "ok",
      t: [1786233600, 1786320000],
      o: [100, 101], h: [102, 103], l: [99, 100], c: [101, 102], v: [1000, 1200],
    });
  };
  const response = await invoke(createRequestHandler({
    fetchImpl: providerFetch,
    env: { MARKET_DATA_PROVIDER: "finnhub", FINNHUB_API_KEY: "server-secret" },
    now: () => new Date("2026-08-11T15:00:00Z"),
  }), "/v1/candles/MSFT?interval=1440&range=129600");
  assert.equal(response.status, 200);
  assert.equal(response.body.intervalMinutes, 1440);
  assert.equal(response.body.candles.length, 2);
  assert.match(response.body.candles[1].timestamp, /^2026-/);
});

test("requests Alpha Vantage daily data and preserves its source date", async () => {
  const providerFetch = async url => {
    assert.match(url, /function=TIME_SERIES_DAILY/);
    assert.doesNotMatch(url, /interval=1min/);
    return jsonResponse({
      "Meta Data": { "6. Time Zone": "America/New_York" },
      "Time Series (Daily)": {
        "2026-08-10": { "1. open": "100", "2. high": "102", "3. low": "99", "4. close": "101", "5. volume": "1200" },
      },
    });
  };
  const response = await invoke(createRequestHandler({
    fetchImpl: providerFetch,
    env: { MARKET_DATA_PROVIDER: "alphavantage", ALPHA_VANTAGE_API_KEY: "server-secret" },
    now: () => new Date("2026-08-11T15:00:00Z"),
  }), "/v1/candles/MSFT?interval=1440&range=129600");
  assert.equal(response.status, 200);
  assert.equal(response.body.intervalMinutes, 1440);
  assert.equal(response.body.candles[0].timestamp, "2026-08-10T04:00:00.000Z");
});

test("normalizes Finnhub news with source time and labeled local scoring", async () => {
  const providerFetch = async url => {
    assert.match(url, /company-news/);
    return jsonResponse([{ datetime: 1786460340, headline: "Profit growth beats outlook", source: "Reuters", url: "https://example.com/story" }]);
  };
  const response = await invoke(createRequestHandler({
    fetchImpl: providerFetch,
    env: { MARKET_DATA_PROVIDER: "finnhub", FINNHUB_API_KEY: "server-secret" },
    now: () => new Date("2026-08-11T15:00:00Z"),
  }), "/v1/news/MSFT");
  assert.equal(response.status, 200);
  assert.equal(response.body.items[0].source, "Reuters");
  assert.equal(response.body.items[0].scoringMethod, "Deterministic headline lexicon");
  assert.ok(response.body.items[0].score > 0);
});

test("preserves Alpha Vantage ticker sentiment instead of overall sentiment", async () => {
  const providerFetch = async url => {
    assert.match(url, /NEWS_SENTIMENT/);
    return jsonResponse({ feed: [{
      title: "Microsoft update", source: "Example Wire", time_published: "20260811T143000", url: "https://example.com/msft",
      overall_sentiment_score: -0.9,
      ticker_sentiment: [{ ticker: "MSFT", ticker_sentiment_score: "0.42" }],
    }] });
  };
  const response = await invoke(createRequestHandler({
    fetchImpl: providerFetch,
    env: { MARKET_DATA_PROVIDER: "alphavantage", ALPHA_VANTAGE_API_KEY: "server-secret" },
    now: () => new Date("2026-08-11T15:00:00Z"),
  }), "/v1/news/MSFT");
  assert.equal(response.status, 200);
  assert.equal(response.body.items[0].score, 0.42);
  assert.equal(response.body.items[0].publishedAt, "2026-08-11T14:30:00.000Z");
  assert.equal(response.body.items[0].scoringMethod, "Alpha Vantage ticker sentiment");
});

async function invoke(handler, path) {
  const server = createServer(handler).listen(0, "127.0.0.1");
  await once(server, "listening");
  try {
    const address = server.address();
    const response = await fetch(`http://127.0.0.1:${address.port}${path}`);
    return { status: response.status, body: await response.json() };
  } finally {
    server.close();
    await once(server, "close");
  }
}

function jsonResponse(value, status = 200) {
  return new Response(JSON.stringify(value), { status, headers: { "Content-Type": "application/json" } });
}