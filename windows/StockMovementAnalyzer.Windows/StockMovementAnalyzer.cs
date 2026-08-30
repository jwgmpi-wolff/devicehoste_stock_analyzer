namespace StockMovementAnalyzer.Windows;

public sealed record HorizonDefinition(
    string Id,
    int DurationMinutes,
    int CandleIntervalMinutes,
    int RangeMinutes,
    string Label,
    long FreshnessMinutes)
{
    public int Periods => DurationMinutes / CandleIntervalMinutes;
    public bool IsDaily => CandleIntervalMinutes == 1_440;
    public override string ToString() => Label;

    public static IReadOnlyList<HorizonDefinition> All { get; } =
    [
        new("TEN", 10, 1, 120, "10m", 15),
        new("TWENTY", 20, 1, 120, "20m", 15),
        new("THIRTY", 30, 1, 120, "30m", 15),
        new("FORTY", 40, 1, 120, "40m", 15),
        new("FIFTY", 50, 1, 120, "50m", 15),
        new("SIXTY", 60, 1, 120, "60m", 15),
        new("ONE_DAY", 1_440, 1_440, 129_600, "1d", 7_200),
        new("FIVE_DAYS", 7_200, 1_440, 129_600, "5d", 7_200),
        new("TEN_DAYS", 14_400, 1_440, 129_600, "10d", 7_200),
    ];
}

public enum Direction { Up, Down, Neutral, NeutralInsufficientData }
public enum Recommendation { Buy, Sell, Hold, Unavailable }

public sealed record ProjectedPriceRange(double Low, double High);
public sealed record Quote(
    string Symbol,
    double Price,
    DateTimeOffset Timestamp,
    string Provider,
    double? OvernightPrice = null,
    double? OvernightChange = null,
    double? OvernightChangePercent = null,
    double? PreMarketPrice = null,
    double? PreMarketChange = null,
    double? PreMarketChangePercent = null,
    double? AfterHoursPrice = null,
    double? AfterHoursChange = null,
    double? AfterHoursChangePercent = null);
public sealed record Candle(DateTimeOffset Timestamp, double Open, double High, double Low, double Close, long? Volume);
public sealed record TimestampedSentiment(double Score, string Source, DateTimeOffset PublishedAt, string Headline, string? Url, string ScoringMethod);
public sealed record NewsSentimentBatch(string Provider, DateTimeOffset RetrievedAt, IReadOnlyList<TimestampedSentiment> Items);
public sealed record MarketSnapshot(
    string Symbol,
    string Provider,
    DateTimeOffset RetrievedAt,
    int IntervalMinutes,
    Quote? Quote,
    IReadOnlyList<Candle> Candles,
    NewsSentimentBatch? News = null,
    string? NewsWarning = null);
public sealed record IndicatorValues(
    double? MomentumPercent,
    double? ShortMovingAverage,
    double? LongMovingAverage,
    double? RelativeVolume,
    double? Rsi,
    double? Macd,
    double? Vwap,
    double? SentimentAverage);
public sealed record SignalContribution(string Name, double? Value, double Weight, double? Contribution);
public sealed record AnalysisResult(
    string Symbol,
    HorizonDefinition Horizon,
    Direction Direction,
    int Confidence,
    string Provider,
    DateTimeOffset? LastDataTimestamp,
    DateTimeOffset RetrievedAt,
    long? SourceAgeMinutes,
    int CandleIntervalMinutes,
    Quote? Quote,
    IndicatorValues? Indicators,
    IReadOnlyList<SignalContribution> Signals,
    Recommendation Recommendation,
    ProjectedPriceRange? ProjectedPriceRange,
    IReadOnlyList<string> Warnings,
    string Reason,
    NewsSentimentBatch? News = null);

public sealed class StockAnalyzerEngine(double positiveThreshold = 0.2, double negativeThreshold = -0.2)
{
    private static readonly TimeZoneInfo EasternTime = TimeZoneInfo.FindSystemTimeZoneById("Eastern Standard Time");
    private const int OpenMinutes = 9 * 60 + 30;
    private const int CloseMinutes = 16 * 60;
    private const long OffHoursIntradayFreshness = 18L * 60L;
    private const long WeekendIntradayFreshness = 72L * 60L;

    public AnalysisResult Analyze(MarketSnapshot snapshot, HorizonDefinition horizon, DateTimeOffset? currentTime = null)
    {
        var now = currentTime ?? DateTimeOffset.UtcNow;
        var latestTimestamp = snapshot.Candles.Count == 0 ? (DateTimeOffset?)null : snapshot.Candles.Max(candle => candle.Timestamp);
        var age = latestTimestamp is null ? (long?)null : MinutesBetween(latestTimestamp.Value, now);
        var currentNews = CurrentNews(snapshot, horizon, now);
        var warnings = Validate(snapshot, horizon, now);
        if (warnings.Count > 0) return Insufficient(snapshot, horizon, latestTimestamp, age, warnings, news: currentNews);

        var ordered = snapshot.Candles.OrderBy(candle => candle.Timestamp).ToList();
        var closes = ordered.Select(candle => candle.Close).ToList();
        var indicators = new IndicatorValues(
            MomentumPercent(closes, horizon.Periods),
            SimpleMovingAverage(closes, 5),
            SimpleMovingAverage(closes, 12),
            RelativeVolume(ordered.Select(candle => candle.Volume).ToList(), 10),
            Rsi(closes),
            Macd(closes),
            Vwap(ordered.TakeLast(20).ToList()),
            currentNews?.Items.Count > 0 ? currentNews.Items.Average(item => Math.Clamp(item.Score, -1.0, 1.0)) : null);
        var signals = new List<SignalContribution>
        {
            Contribution("Momentum", indicators.MomentumPercent is double momentum ? Math.Clamp(momentum, -2.0, 2.0) / 2.0 : null, 0.30),
            Contribution("Trend", TrendScore(indicators), 0.20),
            Contribution("Volume", VolumeScore(indicators, closes[^1]), 0.10),
            Contribution("RSI", RsiScore(indicators.Rsi), 0.15),
            Contribution("MACD", indicators.Macd is double macd ? Math.Sign(macd) : null, 0.15),
            Contribution("News sentiment", indicators.SentimentAverage, 0.10),
        };
        var availableWeight = signals.Where(signal => signal.Contribution is not null).Sum(signal => signal.Weight);
        if (availableWeight < 0.6)
            return Insufficient(snapshot, horizon, latestTimestamp, age, ["Too few supported signals to calculate a prediction."], indicators, signals, currentNews);

        var score = signals.Sum(signal => signal.Contribution ?? 0.0) / availableWeight;
        var direction = score >= positiveThreshold ? Direction.Up : score <= negativeThreshold ? Direction.Down : Direction.Neutral;
        var confidence = Math.Clamp((int)Math.Floor(Math.Abs(score) * 100.0 + 0.5), 0, 100);
        var recommendation = direction switch
        {
            Direction.Up => Recommendation.Buy,
            Direction.Down => Recommendation.Sell,
            _ => Recommendation.Hold,
        };
        var projectedRange = ProjectedRange(ordered, snapshot.Quote!.Price, horizon, score);
        var scope = horizon.IsDaily ? "daily" : "intraday";
        var newsSummary = indicators.SentimentAverage is not null
            ? " Fresh timestamped news sentiment was included."
            : " News sentiment was unavailable or stale and was excluded.";
        var reason = $"Probabilistic {scope} analysis from {signals.Count(signal => signal.Contribution is not null)} supported trend, momentum, volume, technical, and sourced news signals; weighted score {score:F3}. The {recommendation.ToString().ToLowerInvariant()} classification and projected range use validated recent price behavior, not a guaranteed target.{newsSummary} This is not financial advice.";
        var limitations = new List<string>();
        if (indicators.RelativeVolume is null || indicators.Vwap is null) limitations.Add("Volume or VWAP was unavailable and was not used.");
        if (indicators.Rsi is null) limitations.Add("RSI was unavailable and was not used.");
        if (indicators.Macd is null) limitations.Add("MACD was unavailable and was not used.");
        if (indicators.SentimentAverage is null) limitations.Add(snapshot.NewsWarning ?? "Fresh timestamped sentiment was unavailable and was not used.");
        return new AnalysisResult(snapshot.Symbol, horizon, direction, confidence, snapshot.Provider, latestTimestamp, snapshot.RetrievedAt,
            age, snapshot.IntervalMinutes, snapshot.Quote, indicators, signals, recommendation, projectedRange, limitations, reason, currentNews);
    }

    private static List<string> Validate(MarketSnapshot snapshot, HorizonDefinition horizon, DateTimeOffset now)
    {
        var warnings = new List<string>();
        if (string.IsNullOrWhiteSpace(snapshot.Provider)) warnings.Add("Data provider is missing.");
        if (snapshot.Quote is null) warnings.Add("Latest quote is unavailable.");
        if (snapshot.IntervalMinutes <= 0)
        {
            warnings.Add("Candle interval is invalid.");
            return warnings;
        }
        var latest = snapshot.Candles.MaxBy(candle => candle.Timestamp);
        if (latest is null)
        {
            warnings.Add("Timestamped intraday candles are unavailable.");
            return warnings;
        }
        var age = MinutesBetween(latest.Timestamp, now);
        var freshnessMinutes = EffectiveFreshnessMinutes(horizon, now);
        if (age < 0) warnings.Add("Source timestamp is in the future.");
        if (age > freshnessMinutes) warnings.Add($"Market data is stale ({age} minutes old).");
        if (snapshot.IntervalMinutes != horizon.CandleIntervalMinutes) warnings.Add($"Candle interval does not match the {horizon.Label} horizon.");
        if (snapshot.Candles.Count < horizon.Periods + 1) warnings.Add($"Insufficient candles for the {horizon.Label} horizon.");
        if (snapshot.Quote is not null)
        {
            var quoteAge = MinutesBetween(snapshot.Quote.Timestamp, now);
            if (quoteAge < 0) warnings.Add("Quote timestamp is in the future.");
            if (quoteAge > freshnessMinutes) warnings.Add($"Latest quote is stale ({quoteAge} minutes old).");
            if (snapshot.Quote.Provider != snapshot.Provider) warnings.Add("Quote provider does not match candle provider.");
            if (snapshot.Quote.Price <= 0.0) warnings.Add("Provider returned an invalid quote price.");
        }
        if (snapshot.Candles.Any(candle => candle.Close <= 0.0 || candle.High < candle.Low)) warnings.Add("Provider returned invalid candle values.");
        return warnings;
    }

    private static double? SimpleMovingAverage(IReadOnlyList<double> values, int period) =>
        values.Count < period ? null : values.TakeLast(period).Average();

    private static double? MomentumPercent(IReadOnlyList<double> values, int periods)
    {
        if (values.Count <= periods || values[values.Count - 1 - periods] == 0.0) return null;
        var start = values[values.Count - 1 - periods];
        return ((values[^1] - start) / start) * 100.0;
    }

    private static double? RelativeVolume(IReadOnlyList<long?> volumes, int period)
    {
        var actual = volumes.TakeLast(period + 1).Where(volume => volume is not null).Select(volume => (double)volume!.Value).ToList();
        if (actual.Count != period + 1) return null;
        var baseline = actual.Take(actual.Count - 1).Average();
        return baseline > 0.0 ? actual[^1] / baseline : null;
    }

    private static double? Rsi(IReadOnlyList<double> values, int period = 14)
    {
        if (values.Count < period + 1) return null;
        var changes = values.TakeLast(period + 1).Zip(values.TakeLast(period + 1).Skip(1), (first, second) => second - first).ToList();
        var gains = changes.Sum(change => Math.Max(change, 0.0)) / period;
        var losses = changes.Sum(change => Math.Max(-change, 0.0)) / period;
        if (losses == 0.0) return gains == 0.0 ? 50.0 : 100.0;
        return 100.0 - (100.0 / (1.0 + gains / losses));
    }

    private static double? ExponentialMovingAverage(IReadOnlyList<double> values, int period)
    {
        if (values.Count < period) return null;
        var multiplier = 2.0 / (period + 1);
        var ema = values.Take(period).Average();
        foreach (var value in values.Skip(period)) ema = (value - ema) * multiplier + ema;
        return ema;
    }

    private static double? Macd(IReadOnlyList<double> values)
    {
        var fast = ExponentialMovingAverage(values, 12);
        var slow = ExponentialMovingAverage(values, 26);
        return fast is null || slow is null ? null : fast - slow;
    }

    private static double? Vwap(IReadOnlyList<Candle> candles)
    {
        if (candles.Count == 0 || candles.Any(candle => candle.Volume is null)) return null;
        var volume = candles.Sum(candle => candle.Volume!.Value);
        if (volume == 0L) return null;
        return candles.Sum(candle => ((candle.High + candle.Low + candle.Close) / 3.0) * candle.Volume!.Value) / volume;
    }

    private static NewsSentimentBatch? CurrentNews(MarketSnapshot snapshot, HorizonDefinition horizon, DateTimeOffset now)
    {
        if (snapshot.News?.Provider != snapshot.Provider) return null;
        var maximumAgeMinutes = horizon.IsDaily ? 10_080L : 1_440L;
        var fresh = snapshot.News.Items.Where(item =>
        {
            var age = MinutesBetween(item.PublishedAt, now);
            return age >= 0 && age <= maximumAgeMinutes && !string.IsNullOrWhiteSpace(item.Source) && !string.IsNullOrWhiteSpace(item.Headline);
        }).ToList();
        return snapshot.News with { Items = fresh };
    }

    private static ProjectedPriceRange? ProjectedRange(IReadOnlyList<Candle> candles, double currentPrice, HorizonDefinition horizon, double score)
    {
        var returns = candles.TakeLast(21).Zip(candles.TakeLast(21).Skip(1),
            (first, second) => first.Close > 0.0 ? (double?)((second.Close - first.Close) / first.Close) : null).Where(value => value is not null).Select(value => value!.Value).ToList();
        if (returns.Count < 5) return null;
        var averageReturn = returns.Average();
        var variance = returns.Sum(value => (value - averageReturn) * (value - averageReturn)) / returns.Count;
        var projectedVolatility = Math.Max(Math.Sqrt(variance), 0.001) * Math.Sqrt(horizon.Periods);
        var halfSpan = currentPrice * Math.Min(projectedVolatility, 0.35);
        var center = currentPrice + Math.Clamp(score, -1.0, 1.0) * halfSpan * 0.5;
        return new ProjectedPriceRange(Math.Max(center - halfSpan, 0.01), center + halfSpan);
    }

    private static SignalContribution Contribution(string name, double? value, double weight) => new(name, value, weight, value * weight);
    private static double? TrendScore(IndicatorValues values) => values.ShortMovingAverage is null || values.LongMovingAverage is null
        ? null : values.ShortMovingAverage > values.LongMovingAverage ? 1.0 : values.ShortMovingAverage < values.LongMovingAverage ? -1.0 : 0.0;
    private static double? VolumeScore(IndicatorValues values, double latestPrice)
    {
        if (values.RelativeVolume is null || values.Vwap is null) return null;
        if (values.RelativeVolume < 1.1) return 0.0;
        return latestPrice >= values.Vwap ? 1.0 : -1.0;
    }
    private static double? RsiScore(double? rsi) => rsi is null ? null : rsi >= 70.0 ? -1.0 : rsi <= 30.0 ? 1.0 : 0.0;
    private static long MinutesBetween(DateTimeOffset start, DateTimeOffset end) => (long)(end - start).TotalMinutes;

    public static long EffectiveFreshnessMinutes(HorizonDefinition horizon, DateTimeOffset now)
    {
        if (horizon.IsDaily) return horizon.FreshnessMinutes;
        var easternNow = TimeZoneInfo.ConvertTime(now, EasternTime);
        if (easternNow.DayOfWeek is DayOfWeek.Saturday or DayOfWeek.Sunday) return WeekendIntradayFreshness;
        var minuteOfDay = easternNow.Hour * 60 + easternNow.Minute;
        return minuteOfDay >= OpenMinutes && minuteOfDay < CloseMinutes ? horizon.FreshnessMinutes : OffHoursIntradayFreshness;
    }

    private static AnalysisResult Insufficient(MarketSnapshot snapshot, HorizonDefinition horizon, DateTimeOffset? timestamp, long? age,
        IReadOnlyList<string> warnings, IndicatorValues? indicators = null, IReadOnlyList<SignalContribution>? signals = null, NewsSentimentBatch? news = null) =>
        new(snapshot.Symbol, horizon, Direction.NeutralInsufficientData, 0, string.IsNullOrWhiteSpace(snapshot.Provider) ? "Unknown" : snapshot.Provider,
            timestamp, snapshot.RetrievedAt, age, snapshot.IntervalMinutes, snapshot.Quote, indicators, signals ?? [], Recommendation.Unavailable, null,
            warnings, $"Insufficient live data. {string.Join(" ", warnings)} No directional prediction was generated.", news);
}

public static class HeadlineSentimentScorer
{
    private static readonly HashSet<string> Positive = ["beat", "beats", "bullish", "gain", "gains", "growth", "higher", "improve", "improves", "profit", "profits", "raise", "raises", "record", "surge", "surges", "upgrade", "upgrades"];
    private static readonly HashSet<string> Negative = ["bearish", "cut", "cuts", "decline", "declines", "downgrade", "downgrades", "drop", "drops", "fall", "falls", "fraud", "investigation", "lawsuit", "loss", "losses", "lower", "miss", "misses", "risk", "risks"];

    public static double Score(string headline)
    {
        var words = System.Text.RegularExpressions.Regex.Matches(headline.ToLowerInvariant(), "[a-z]+").Select(match => match.Value);
        var raw = words.Sum(word => (Positive.Contains(word) ? 1 : 0) - (Negative.Contains(word) ? 1 : 0));
        return Math.Clamp(raw / 3.0, -1.0, 1.0);
    }
}
