using System.Net.Http;
using System.Text.Json;

namespace StockMovementAnalyzer.Windows;

public sealed class AnalysisService(HttpClient httpClient, string? finnhubApiKey = null)
{
    private const string Provider = "Yahoo Finance";
    private const string ExtendedHoursBaseUrl = "https://query2.finance.yahoo.com";
    private const string FinnhubBaseUrl = "https://finnhub.io";
    private static readonly TimeZoneInfo EasternTime = TimeZoneInfo.FindSystemTimeZoneById("Eastern Standard Time");

    public async Task<AnalysisResult> AnalyzeAsync(string symbol, HorizonDefinition horizon, CancellationToken cancellationToken)
    {
        var normalized = symbol.Trim().ToUpperInvariant();
        var quoteRequest = GetQuoteAsync(normalized, cancellationToken);
        var candlesRequest = GetCandlesAsync(normalized, horizon, cancellationToken);
        var newsRequest = GetNewsAsync(normalized, cancellationToken);
        await Task.WhenAll(quoteRequest, candlesRequest);
        var quote = await quoteRequest;
        var candles = await candlesRequest;
        NewsSentimentBatch? news = null;
        string? newsWarning = null;
        try { news = await newsRequest; }
        catch (Exception error) { newsWarning = error.Message; }
        var snapshot = new MarketSnapshot(normalized, Provider, DateTimeOffset.UtcNow, horizon.CandleIntervalMinutes, quote, candles, news, newsWarning);
        return new StockAnalyzerEngine().Analyze(snapshot, horizon);
    }

    private async Task<Quote> GetQuoteAsync(string symbol, CancellationToken cancellationToken)
    {
        using var chart = await GetJsonAsync($"/v8/finance/chart/{Uri.EscapeDataString(symbol)}?interval=1m&range=1d", cancellationToken);
        JsonDocument? quoteSummary = null;
        try { quoteSummary = await GetJsonAsync($"/v7/finance/quote?symbols={Uri.EscapeDataString(symbol)}", cancellationToken); }
        catch { quoteSummary = null; }
        var result = GetChartResult(chart);
        var meta = result.GetProperty("meta");
        var summary = quoteSummary is null ? null : GetQuoteResult(quoteSummary, symbol);
        var regularPrice = OptionalDouble(meta, "regularMarketPrice") ?? OptionalDouble(summary, "regularMarketPrice");
        var regularMarketTime = OptionalLong(meta, "regularMarketTime") ?? OptionalLong(summary, "regularMarketTime");
        if (regularPrice is null || regularMarketTime is null)
        {
            var finnhubQuote = await GetFinnhubQuoteAsync(symbol, cancellationToken);
            regularPrice ??= finnhubQuote?.Price;
            regularMarketTime ??= finnhubQuote?.Timestamp;
        }
        if (regularPrice is null)
            throw new InvalidOperationException("Current price was unavailable.");
        if (regularMarketTime is null)
            throw new InvalidOperationException("Quote timestamp was unavailable.");
        var extended = await GetExtendedSessionAsync(symbol, cancellationToken);
        var preMarketChange = OptionalDouble(meta, "preMarketChange")
            ?? OptionalDouble(summary, "preMarketChange");
        var directAfterHoursChange = OptionalDouble(meta, "postMarketChange")
            ?? OptionalDouble(summary, "postMarketChange");
        var directPreMarketPrice = OptionalDouble(meta, "preMarketPrice")
            ?? OptionalDouble(summary, "preMarketPrice");
        var preMarketPrice = directPreMarketPrice
            ?? (preMarketChange is double preChange ? regularPrice + preChange : null)
            ?? extended.PreMarketPrice;
        var directAfterHoursPrice = OptionalDouble(meta, "postMarketPrice")
            ?? OptionalDouble(summary, "postMarketPrice");
        var afterHoursPrice = directAfterHoursPrice
            ?? (directAfterHoursChange is double postChange ? regularPrice + postChange : null)
            ?? extended.AfterHours?.Price;
        var afterHoursChange = directAfterHoursChange
            ?? (directAfterHoursPrice is double directPost ? directPost - regularPrice : null)
            ?? extended.AfterHours?.Change;
        var preMarketChangePercent = OptionalDouble(meta, "preMarketChangePercent")
            ?? OptionalDouble(summary, "preMarketChangePercent")
            ?? (preMarketPrice is double prePrice && regularPrice != 0.0 ? ((prePrice - regularPrice) / regularPrice) * 100.0 : null);
        var afterHoursChangePercent = OptionalDouble(meta, "postMarketChangePercent")
            ?? OptionalDouble(summary, "postMarketChangePercent")
            ?? (directAfterHoursPrice is not null || directAfterHoursChange is not null
                ? afterHoursChange is double currentPostChange && regularPrice != 0.0 ? (currentPostChange / regularPrice) * 100.0 : null
                : extended.AfterHours?.Percent);
        var quote = new Quote(
            symbol,
            regularPrice.Value,
            DateTimeOffset.FromUnixTimeSeconds(regularMarketTime.Value),
            Provider,
            extended.Overnight?.Price,
            extended.Overnight?.Change,
            extended.Overnight?.Percent,
            preMarketPrice,
            preMarketChange ?? (preMarketPrice is double resolvedPre ? resolvedPre - regularPrice.Value : null),
            preMarketChangePercent,
            afterHoursPrice,
            afterHoursChange,
            afterHoursChangePercent);
        quoteSummary?.Dispose();
        return quote;
    }

    private async Task<ExtendedSessionPrices> GetExtendedSessionAsync(string symbol, CancellationToken cancellationToken)
    {
        try
        {
            var observedAt = DateTimeOffset.UtcNow;
            using var chart = await GetExtendedSessionJsonAsync(symbol, cancellationToken)
                ?? throw new InvalidOperationException("Extended-session chart was unavailable.");
            var result = GetChartResult(chart);
            var timestamps = result.GetProperty("timestamp").EnumerateArray().Select(value => value.GetInt64()).ToList();
            var closes = result.GetProperty("indicators").GetProperty("quote")[0].GetProperty("close").EnumerateArray().Select(NullableDouble).ToList();
            var samples = timestamps.Select((timestamp, index) => new SessionSample(DateTimeOffset.FromUnixTimeSeconds(timestamp), ElementAt(closes, index)))
                .ToList();
            return ExtractExtendedSessionPrices(samples, observedAt);
        }
        catch { return new ExtendedSessionPrices(null, null, null); }
    }

    internal static ExtendedSessionPrices ExtractExtendedSessionPrices(
        IReadOnlyList<SessionSample> samples,
        DateTimeOffset observedAt)
    {
        var observedSamples = samples
            .Where(sample => sample.Price is not null && sample.Timestamp <= observedAt)
            .ToList();
        SessionSample? LatestSessionPrice(int startMinute, int endMinute) => observedSamples
                .Where(sample =>
                {
                    var time = TimeZoneInfo.ConvertTime(sample.Timestamp, EasternTime).TimeOfDay;
                    var minute = time.Hours * 60 + time.Minutes;
                    return minute >= startMinute && minute < endMinute;
                })
                .MaxBy(sample => sample.Timestamp);
        SessionSample? LatestOvernightPrice() => observedSamples
                .Where(sample =>
                {
                    var time = TimeZoneInfo.ConvertTime(sample.Timestamp, EasternTime).TimeOfDay;
                    var minute = time.Hours * 60 + time.Minutes;
                    return minute >= 20 * 60 || minute < 4 * 60;
                })
                .MaxBy(sample => sample.Timestamp);
        double? RegularClose(DateTime date) => observedSamples
                .Where(sample =>
                {
                    var local = TimeZoneInfo.ConvertTime(sample.Timestamp, EasternTime);
                    var minute = local.Hour * 60 + local.Minute;
                    return local.Date == date && minute >= 9 * 60 + 30 && minute < 16 * 60;
                })
                .MaxBy(sample => sample.Timestamp)?.Price;
        SessionQuote? ToSessionQuote(SessionSample? sample, Func<DateTimeOffset, DateTime> baselineDate)
        {
            if (sample?.Price is not double sessionPrice) return null;
            var baseline = RegularClose(baselineDate(sample.Timestamp));
            double? change = baseline is double regularClose ? sessionPrice - regularClose : null;
            double? percent = change is double resolvedChange && baseline is double nonzeroBaseline && nonzeroBaseline != 0.0
                ? (resolvedChange / nonzeroBaseline) * 100.0
                : null;
            return new SessionQuote(sessionPrice, change, percent);
        }
        var overnight = ToSessionQuote(LatestOvernightPrice(), timestamp =>
        {
            var local = TimeZoneInfo.ConvertTime(timestamp, EasternTime);
            return local.Hour < 4 ? local.Date.AddDays(-1) : local.Date;
        });
        var afterHours = ToSessionQuote(
            LatestSessionPrice(16 * 60, 20 * 60),
            timestamp => TimeZoneInfo.ConvertTime(timestamp, EasternTime).Date);
        return new ExtendedSessionPrices(
            overnight,
            LatestSessionPrice(4 * 60, 9 * 60 + 30)?.Price,
            afterHours);
    }

    private async Task<JsonDocument?> GetExtendedSessionJsonAsync(string symbol, CancellationToken cancellationToken)
    {
        var path = $"/v8/finance/chart/{Uri.EscapeDataString(symbol)}?interval=1m&range=5d&includePrePost=true";
        var baseUrls = new[] { ExtendedHoursBaseUrl, "https://query1.finance.yahoo.com" }.Distinct(StringComparer.OrdinalIgnoreCase);
        foreach (var baseUrl in baseUrls)
        {
            try { return await GetJsonAsync(path, cancellationToken, baseUrl); }
            catch { }
        }
        return null;
    }

    private async Task<FinnhubQuote?> GetFinnhubQuoteAsync(string symbol, CancellationToken cancellationToken)
    {
        if (string.IsNullOrWhiteSpace(finnhubApiKey)) return null;
        try
        {
            using var quote = await GetJsonAsync(
                $"/api/v1/quote?symbol={Uri.EscapeDataString(symbol)}&token={Uri.EscapeDataString(finnhubApiKey.Trim())}",
                cancellationToken,
                FinnhubBaseUrl);
            var root = quote.RootElement;
            var price = OptionalDouble(root, "c");
            var timestamp = OptionalLong(root, "t");
            if (price is null || timestamp is null || timestamp <= 0) return null;
            return new FinnhubQuote(price.Value, timestamp.Value);
        }
        catch
        {
            return null;
        }
    }

    private async Task<IReadOnlyList<Candle>> GetCandlesAsync(string symbol, HorizonDefinition horizon, CancellationToken cancellationToken)
    {
        var interval = horizon.IsDaily ? "1d" : "1m";
        var range = horizon.IsDaily ? "6mo" : "1d";
        using var chart = await GetJsonAsync($"/v8/finance/chart/{Uri.EscapeDataString(symbol)}?interval={interval}&range={range}", cancellationToken);
        var result = GetChartResult(chart);
        var timestamps = result.GetProperty("timestamp").EnumerateArray().Select(value => value.GetInt64()).ToList();
        var values = result.GetProperty("indicators").GetProperty("quote")[0];
        var opens = values.GetProperty("open").EnumerateArray().Select(NullableDouble).ToList();
        var highs = values.GetProperty("high").EnumerateArray().Select(NullableDouble).ToList();
        var lows = values.GetProperty("low").EnumerateArray().Select(NullableDouble).ToList();
        var closes = values.GetProperty("close").EnumerateArray().Select(NullableDouble).ToList();
        var volumes = values.GetProperty("volume").EnumerateArray().Select(NullableLong).ToList();
        var candles = new List<Candle>();
        for (var index = 0; index < timestamps.Count; index++)
        {
            if (ElementAt(opens, index) is not double open || ElementAt(highs, index) is not double high ||
                ElementAt(lows, index) is not double low || ElementAt(closes, index) is not double close) continue;
            candles.Add(new Candle(DateTimeOffset.FromUnixTimeSeconds(timestamps[index]), open, high, low, close, ElementAt(volumes, index)));
        }
        if (candles.Count == 0) throw new InvalidOperationException("Candle values were unavailable.");
        return candles;
    }

    private async Task<NewsSentimentBatch> GetNewsAsync(string symbol, CancellationToken cancellationToken)
    {
        using var search = await GetJsonAsync($"/v1/finance/search?q={Uri.EscapeDataString(symbol)}&quotesCount=0&newsCount=30", cancellationToken);
        var items = new List<TimestampedSentiment>();
        foreach (var article in search.RootElement.GetProperty("news").EnumerateArray())
        {
            if (!article.TryGetProperty("relatedTickers", out var related) ||
                !related.EnumerateArray().Any(value => string.Equals(value.GetString(), symbol, StringComparison.OrdinalIgnoreCase))) continue;
            if (!article.TryGetProperty("providerPublishTime", out var timeValue) || !timeValue.TryGetInt64(out var timestamp)) continue;
            var headline = article.TryGetProperty("title", out var titleValue) ? titleValue.GetString() : null;
            var source = article.TryGetProperty("publisher", out var publisherValue) ? publisherValue.GetString() : null;
            if (string.IsNullOrWhiteSpace(headline) || string.IsNullOrWhiteSpace(source)) continue;
            var link = article.TryGetProperty("link", out var linkValue) ? linkValue.GetString() : null;
            if (link is not null && !link.StartsWith("https://", StringComparison.Ordinal)) link = null;
            items.Add(new TimestampedSentiment(HeadlineSentimentScorer.Score(headline), source,
                DateTimeOffset.FromUnixTimeSeconds(timestamp), headline, link, "Deterministic headline lexicon"));
        }
        return new NewsSentimentBatch(Provider, DateTimeOffset.UtcNow, items);
    }

    private async Task<JsonDocument> GetJsonAsync(string path, CancellationToken cancellationToken, string baseUrl = "https://query1.finance.yahoo.com")
    {
        using var request = new HttpRequestMessage(HttpMethod.Get, $"{baseUrl}{path}");
        request.Headers.Accept.ParseAdd("application/json");
        request.Headers.UserAgent.ParseAdd("StockMovementAnalyzer/1.6");
        using var response = await httpClient.SendAsync(request, cancellationToken);
        if (!response.IsSuccessStatusCode)
            throw new HttpRequestException($"HTTP {(int)response.StatusCode} from {baseUrl}");
        return JsonDocument.Parse(await response.Content.ReadAsStringAsync(cancellationToken));
    }

    private static JsonElement GetChartResult(JsonDocument chart)
    {
        var results = chart.RootElement.GetProperty("chart").GetProperty("result");
        if (results.ValueKind != JsonValueKind.Array || results.GetArrayLength() == 0)
            throw new InvalidOperationException("Unsupported symbol.");
        return results[0];
    }

    private static JsonElement? GetQuoteResult(JsonDocument quoteSummary, string symbol)
    {
        if (!quoteSummary.RootElement.TryGetProperty("quoteResponse", out var quoteResponse)) return null;
        if (!quoteResponse.TryGetProperty("result", out var results) || results.ValueKind != JsonValueKind.Array) return null;
        foreach (var item in results.EnumerateArray())
        {
            if (!item.TryGetProperty("symbol", out var value)) continue;
            if (string.Equals(value.GetString(), symbol, StringComparison.OrdinalIgnoreCase)) return item;
        }
        return null;
    }

    private static double? OptionalDouble(JsonElement parent, string propertyName)
    {
        return parent.TryGetProperty(propertyName, out var value) && value.ValueKind == JsonValueKind.Number
            ? value.GetDouble()
            : null;
    }

    private static double? OptionalDouble(JsonElement? parent, string propertyName)
    {
        return parent.HasValue && parent.Value.TryGetProperty(propertyName, out var value) && value.ValueKind == JsonValueKind.Number
            ? value.GetDouble()
            : null;
    }

    private static long? OptionalLong(JsonElement parent, string propertyName)
    {
        return parent.TryGetProperty(propertyName, out var value) && value.ValueKind == JsonValueKind.Number && value.TryGetInt64(out var result)
            ? result
            : null;
    }

    private static long? OptionalLong(JsonElement? parent, string propertyName)
    {
        return parent.HasValue && parent.Value.TryGetProperty(propertyName, out var value) && value.ValueKind == JsonValueKind.Number && value.TryGetInt64(out var result)
            ? result
            : null;
    }

    internal sealed record SessionSample(DateTimeOffset Timestamp, double? Price);
    internal sealed record SessionQuote(double Price, double? Change, double? Percent);
    internal sealed record ExtendedSessionPrices(SessionQuote? Overnight, double? PreMarketPrice, SessionQuote? AfterHours);

    private static double? NullableDouble(JsonElement value) => value.ValueKind == JsonValueKind.Number ? value.GetDouble() : null;
    private static long? NullableLong(JsonElement value) => value.ValueKind == JsonValueKind.Number && value.TryGetInt64(out var result) ? result : null;
    private static T? ElementAt<T>(IReadOnlyList<T?> values, int index) where T : struct => index < values.Count ? values[index] : null;

    private sealed record FinnhubQuote(double Price, long Timestamp);
}
