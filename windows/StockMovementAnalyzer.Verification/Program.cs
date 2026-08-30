using StockMovementAnalyzer.Windows;
using System.Windows.Media;

var now = DateTimeOffset.Parse("2026-08-11T15:00:00Z");
var horizon = HorizonDefinition.All.Single(item => item.Id == "TEN");
var engine = new StockAnalyzerEngine();

var rising = engine.Analyze(Snapshot(now.AddMinutes(-1), index => 100.0 + index), horizon, now);
Require(rising.Direction == Direction.Up && rising.Recommendation == Recommendation.Buy, "Rising fixture must be BUY/UP.");
Require(rising.ProjectedPriceRange is not null && rising.ProjectedPriceRange.Low < rising.Quote!.Price && rising.ProjectedPriceRange.High > rising.Quote.Price,
    "Rising fixture must have a range around the quote.");

var falling = engine.Analyze(Snapshot(now.AddMinutes(-1), index => 130.0 - index), horizon, now);
Require(falling.Direction == Direction.Down && falling.Recommendation == Recommendation.Sell, "Falling fixture must be SELL/DOWN.");

var flat = engine.Analyze(Snapshot(now.AddMinutes(-1), _ => 100.0), horizon, now);
Require(flat.Direction == Direction.Neutral && flat.Recommendation == Recommendation.Hold, "Flat fixture must be HOLD/NEUTRAL.");

var stale = engine.Analyze(Snapshot(now.AddHours(-1), index => 100.0 + index), horizon, now);
Require(stale.Direction == Direction.NeutralInsufficientData && stale.Recommendation == Recommendation.Unavailable && stale.ProjectedPriceRange is null,
    "Stale fixture must fail closed.");

var offHoursNow = DateTimeOffset.Parse("2026-08-12T01:30:00Z");
var offHoursLatest = offHoursNow.AddHours(-4);
var offHours = engine.Analyze(Snapshot(offHoursLatest, index => 100.0 + index), horizon, offHoursNow);
Require(offHours.Direction is not Direction.NeutralInsufficientData && offHours.Recommendation is not Recommendation.Unavailable,
    "Off-hours intraday analysis should use recent session data instead of failing closed.");

var newsSnapshot = Snapshot(now.AddMinutes(-1), index => 100.0 + index) with
{
    News = new NewsSentimentBatch("Mock provider (test only)", now,
    [
        new(0.8, "Current source", now.AddMinutes(-10), "Current headline", null, "Test"),
        new(-1.0, "Old source", now.AddHours(-25), "Stale headline", null, "Test"),
        new(1.0, "Future source", now.AddMinutes(1), "Future headline", null, "Test"),
    ])
};
var currentNews = engine.Analyze(newsSnapshot, horizon, now);
Require(currentNews.News?.Items.Count == 1 && currentNews.News.Items[0].Headline == "Current headline" &&
        Math.Abs((currentNews.Indicators?.SentimentAverage ?? 0.0) - 0.8) < 0.0001,
    "Only news current at analysis time may remain in the result or affect sentiment.");

var overnightObservedAt = DateTimeOffset.Parse("2026-08-12T03:00:00Z");
var extractedSessions = AnalysisService.ExtractExtendedSessionPrices(
[
    new(DateTimeOffset.FromUnixTimeSeconds(1_786_478_340), 503.81), // 15:59 ET regular close
    new(DateTimeOffset.FromUnixTimeSeconds(1_786_482_000), 504.72), // 17:00 ET after-hours
    new(DateTimeOffset.FromUnixTimeSeconds(1_786_501_260), 504.01), // 22:21 ET overnight
    new(DateTimeOffset.FromUnixTimeSeconds(1_786_507_200), 999.00), // 00:00 ET, future at retrieval
], overnightObservedAt);
var extractedOvernight = extractedSessions.Overnight;
var extractedAfterHours = extractedSessions.AfterHours;
Require(extractedOvernight is not null &&
    Math.Abs(extractedOvernight.Price - 504.01) < 0.0001 &&
    Math.Abs((extractedOvernight.Change ?? 0.0) - 0.20) < 0.0001 &&
    Math.Abs((extractedOvernight.Percent ?? 0.0) - 0.0397) < 0.001 &&
    extractedOvernight.Price != 999.00,
    "Windows must populate the latest observed overnight price, use its session close baseline, and reject future samples.");
Require(extractedAfterHours is not null &&
    Math.Abs(extractedAfterHours.Price - 504.72) < 0.0001 &&
    Math.Abs((extractedAfterHours.Change ?? 0.0) - 0.91) < 0.0001,
    "Windows must keep after-hours extraction separate from overnight extraction.");

var extractedOvernightRow = new StockRow("MSFT", null, null);
extractedOvernightRow.ApplyTechnical(rising with
{
    Quote = rising.Quote! with
    {
        OvernightPrice = extractedOvernight!.Price,
        OvernightChange = extractedOvernight.Change,
        OvernightChangePercent = extractedOvernight.Percent,
    }
});
Require(extractedOvernightRow.OvernightGrid.Contains("504.01") &&
        extractedOvernightRow.OvernightGrid.Contains("+0.20") &&
        extractedOvernightRow.OvernightGrid.Contains("+0.04%"),
    "The Windows overnight dashboard cell must render the extracted overnight snapshot.");

var flashRow = new StockRow("MSFT", null, null);
var initialFlashResult = rising with
{
    Quote = rising.Quote! with { OvernightPrice = 130.0, PreMarketPrice = 131.0, AfterHoursPrice = 132.0 }
};
flashRow.ApplyTechnical(initialFlashResult);
Require(IsColor(flashRow.PriceFlashBrush, 0x00, 0x00, 0x00, 0x00) &&
        IsColor(flashRow.OvernightFlashBrush, 0x00, 0x00, 0x00, 0x00) &&
        IsColor(flashRow.PreMarketFlashBrush, 0x00, 0x00, 0x00, 0x00) &&
        IsColor(flashRow.AfterHoursFlashBrush, 0x00, 0x00, 0x00, 0x00),
    "The first quote must establish a baseline without flashing.");

flashRow.ApplyTechnical(initialFlashResult with
{
    Quote = initialFlashResult.Quote! with
    {
        Price = initialFlashResult.Quote.Price + 1.0,
        OvernightPrice = initialFlashResult.Quote.OvernightPrice + 1.0,
        PreMarketPrice = initialFlashResult.Quote.PreMarketPrice + 1.0,
        AfterHoursPrice = initialFlashResult.Quote.AfterHoursPrice + 1.0,
    }
});
Require(IsColor(flashRow.PriceFlashBrush, 0x66, 0x34, 0xC7, 0x59) &&
        IsColor(flashRow.OvernightFlashBrush, 0x66, 0x34, 0xC7, 0x59) &&
        IsColor(flashRow.PreMarketFlashBrush, 0x66, 0x34, 0xC7, 0x59) &&
        IsColor(flashRow.AfterHoursFlashBrush, 0x66, 0x34, 0xC7, 0x59),
    "Every increased market value must flash green.");

flashRow.ApplyTechnical(initialFlashResult);
Require(IsColor(flashRow.PriceFlashBrush, 0x66, 0xFF, 0x3B, 0x30) &&
        IsColor(flashRow.OvernightFlashBrush, 0x66, 0xFF, 0x3B, 0x30) &&
        IsColor(flashRow.PreMarketFlashBrush, 0x66, 0xFF, 0x3B, 0x30) &&
        IsColor(flashRow.AfterHoursFlashBrush, 0x66, 0xFF, 0x3B, 0x30),
    "Every decreased market value must flash red.");

await Task.Delay(1_300);
Require(IsColor(flashRow.PriceFlashBrush, 0x66, 0xFF, 0x3B, 0x30) &&
        IsColor(flashRow.OvernightFlashBrush, 0x66, 0xFF, 0x3B, 0x30) &&
        IsColor(flashRow.PreMarketFlashBrush, 0x66, 0xFF, 0x3B, 0x30) &&
        IsColor(flashRow.AfterHoursFlashBrush, 0x66, 0xFF, 0x3B, 0x30),
    "Market-value flashes must not clear while waiting for the next refresh.");

flashRow.ApplyTechnical(initialFlashResult);
Require(IsColor(flashRow.PriceFlashBrush, 0x00, 0x00, 0x00, 0x00) &&
        IsColor(flashRow.OvernightFlashBrush, 0x00, 0x00, 0x00, 0x00) &&
        IsColor(flashRow.PreMarketFlashBrush, 0x00, 0x00, 0x00, 0x00) &&
        IsColor(flashRow.AfterHoursFlashBrush, 0x00, 0x00, 0x00, 0x00),
    "An unchanged next refresh must clear every market-value flash.");

var retainedSessionRow = new StockRow("MSFT", null, null);
retainedSessionRow.ApplyTechnical(initialFlashResult);
var retainedOvernightText = retainedSessionRow.OvernightGrid;
var retainedAfterHoursText = retainedSessionRow.AfterHoursGrid;
retainedSessionRow.ApplyTechnical(initialFlashResult with
{
    Quote = initialFlashResult.Quote! with
    {
        Price = initialFlashResult.Quote.Price + 10.0,
        OvernightPrice = null,
        OvernightChange = null,
        OvernightChangePercent = null,
        AfterHoursPrice = null,
        AfterHoursChange = null,
        AfterHoursChangePercent = null,
    }
});
Require(retainedSessionRow.OvernightGrid == retainedOvernightText &&
        retainedSessionRow.AfterHoursGrid == retainedAfterHoursText,
    "Missing refresh values must retain exact last overnight and after-hours snapshots.");

retainedSessionRow.ApplyTechnical(initialFlashResult with
{
    Quote = initialFlashResult.Quote! with { OvernightPrice = 140.0, AfterHoursPrice = 141.0 }
});
Require(retainedSessionRow.OvernightGrid != retainedOvernightText &&
        retainedSessionRow.AfterHoursGrid != retainedAfterHoursText,
    "New overnight and after-hours snapshots must replace retained values.");

var fallbackFlashRow = new StockRow("MSFT", null, null);
var changeOnlyResult = rising with
{
    Quote = rising.Quote! with
    {
        OvernightChange = 1.0,
        PreMarketChange = 2.0,
        AfterHoursChange = 3.0,
    }
};
fallbackFlashRow.ApplyTechnical(changeOnlyResult);
fallbackFlashRow.ApplyTechnical(changeOnlyResult with
{
    Quote = changeOnlyResult.Quote! with
    {
        OvernightPrice = changeOnlyResult.Quote.Price + 1.0,
        PreMarketPrice = changeOnlyResult.Quote.Price + 2.0,
        AfterHoursPrice = changeOnlyResult.Quote.Price + 3.0,
    }
});
Require(IsColor(fallbackFlashRow.OvernightFlashBrush, 0x00, 0x00, 0x00, 0x00) &&
        IsColor(fallbackFlashRow.PreMarketFlashBrush, 0x00, 0x00, 0x00, 0x00) &&
        IsColor(fallbackFlashRow.AfterHoursFlashBrush, 0x00, 0x00, 0x00, 0x00),
    "Equivalent change-only and explicit session prices must not produce a false flash.");

var temporaryRoot = Path.Combine(Path.GetTempPath(), $"stock-analyzer-verification-{Guid.NewGuid():N}");
Directory.CreateDirectory(temporaryRoot);
try
{
    var settingsPath = Path.Combine(temporaryRoot, "settings.json");
    File.WriteAllText(settingsPath, """
        {
          "OllamaEndpoint": "http://192.168.1.10:11434",
          "UseLocalModel": true,
          "Rows": [
            { "Symbol": "MSFT", "Quantity": 12.5, "AverageCost": 310.75, "FutureField": "ignored" },
            { "Symbol": "NVDA", "Quantity": null, "AverageCost": null }
          ],
          "FutureSetting": 42
        }
        """);
    var store = new AppStore(settingsPath);
    var upgraded = store.Load();
    Require(upgraded.Rows.Count == 2 && upgraded.Rows[0] == new SavedRow("MSFT", 12.5m, 310.75m),
        "Previous-version Windows watchlist and holding must load unchanged.");
    var retainedRows = upgraded.Rows.Select(row => row.Symbol == "MSFT"
        ? row with
        {
            LastOvernightPrice = 320.0,
            LastOvernightChange = 1.5,
            LastOvernightPercent = 0.47,
            LastAfterHoursPrice = 321.0,
            LastAfterHoursChange = 2.5,
            LastAfterHoursPercent = 0.78,
        }
        : row).ToList();
    store.Save(upgraded with { Model = "qwen3:4b", Rows = retainedRows });
    var retainedSettings = store.Load();
    Require(retainedSettings.Rows[0].LastOvernightPrice == 320.0 &&
            retainedSettings.Rows[0].LastAfterHoursPrice == 321.0,
        "Last overnight and after-hours snapshots must persist across Windows restarts.");
    File.WriteAllText(settingsPath, "corrupt update residue");
    var recovered = store.Load();
    Require(recovered.Rows.Count == 2 && recovered.Rows[0].Symbol == "MSFT",
        "Watchlist must recover from the pre-update backup.");
}
finally
{
    Directory.Delete(temporaryRoot, true);
}

Console.WriteLine("Windows parity and update-persistence verification passed.");

static MarketSnapshot Snapshot(DateTimeOffset latest, Func<int, double> closeAt)
{
    var candles = Enumerable.Range(0, 30).Select(index =>
    {
        var close = closeAt(index);
        return new Candle(latest.AddMinutes(index - 29), close - 0.2, close + 0.4, close - 0.4, close, 1_000L + index * 20);
    }).ToList();
    return new MarketSnapshot("MSFT", "Mock provider (test only)", latest, 1,
        new Quote("MSFT", candles[^1].Close, latest, "Mock provider (test only)"), candles);
}

static void Require(bool condition, string message)
{
    if (!condition) throw new InvalidOperationException(message);
}

static bool IsColor(Brush brush, byte alpha, byte red, byte green, byte blue) =>
    brush is SolidColorBrush solid && solid.Color == Color.FromArgb(alpha, red, green, blue);
