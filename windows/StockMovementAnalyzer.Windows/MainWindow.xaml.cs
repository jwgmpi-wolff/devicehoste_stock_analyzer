using System.Collections.ObjectModel;
using System.ComponentModel;
using System.Net.Http;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Runtime.CompilerServices;
using System.Text.RegularExpressions;
using System.Windows;
using System.Windows.Media;
using System.Windows.Threading;

namespace StockMovementAnalyzer.Windows;

public partial class MainWindow : Window, INotifyPropertyChanged
{
    private readonly HttpClient httpClient = new() { Timeout = TimeSpan.FromSeconds(45) };
    private readonly AppStore store = new();
    private readonly CancellationTokenSource lifetime = new();
    private readonly DispatcherTimer refreshTimer = new() { Interval = TimeSpan.FromSeconds(60) };
    private bool isRefreshing;
    private string status = "Ready";
    private string newSymbol = "";
    private HorizonDefinition selectedHorizon = HorizonDefinition.All[0];
    private string ollamaEndpoint = "http://127.0.0.1:11434";
    private string? selectedModel;
    private string finnhubApiKey = "d9k5sapr01qkjjrs460gd9k5sapr01qkjjrs4610";
    private bool useLocalModel = true;
    private StockRow? selectedRow;

    public MainWindow()
    {
        InitializeComponent();
        DataContext = this;
        PopulateEndpointOptions();
        PopulateModelOptions([]);
        var settings = store.Load();
        OllamaEndpoint = settings.OllamaEndpoint;
        SelectedModel = settings.Model;
        FinnhubApiKey = settings.FinnhubApiKey;
        UseLocalModel = settings.UseLocalModel;
        foreach (var row in settings.Rows)
        {
            Rows.Add(new StockRow(
                row.Symbol,
                row.Quantity,
                row.AverageCost,
                row.LastOvernightPrice,
                row.LastOvernightChange,
                row.LastOvernightPercent,
                row.LastAfterHoursPrice,
                row.LastAfterHoursChange,
                row.LastAfterHoursPercent));
        }
        Loaded += async (_, _) => { await DiscoverModelsAsync(false); await RefreshAsync(); refreshTimer.Start(); };
        refreshTimer.Tick += async (_, _) => await RefreshAsync();
        Closing += (_, _) => { refreshTimer.Stop(); lifetime.Cancel(); Save(); httpClient.Dispose(); };
    }

    public ObservableCollection<StockRow> Rows { get; } = [];
    public ObservableCollection<string> Models { get; } = [];
    public ObservableCollection<string> EndpointOptions { get; } = [];
    public ObservableCollection<string> ModelOptions { get; } = [];
    public IReadOnlyList<HorizonDefinition> Horizons { get; } = HorizonDefinition.All;
    public string Status { get => status; set => Set(ref status, value); }
    public string NewSymbol { get => newSymbol; set => Set(ref newSymbol, value); }
    public HorizonDefinition SelectedHorizon { get => selectedHorizon; set => Set(ref selectedHorizon, value); }
    public string OllamaEndpoint { get => ollamaEndpoint; set => Set(ref ollamaEndpoint, value); }
    public string? SelectedModel { get => selectedModel; set => Set(ref selectedModel, value); }
    public string FinnhubApiKey { get => finnhubApiKey; set => Set(ref finnhubApiKey, value); }
    public bool UseLocalModel { get => useLocalModel; set => Set(ref useLocalModel, value); }
    public StockRow? SelectedRow { get => selectedRow; set => Set(ref selectedRow, value); }

    private async void AddSymbol_Click(object sender, RoutedEventArgs e)
    {
        var symbol = NewSymbol.Trim().ToUpperInvariant();
        if (!Regex.IsMatch(symbol, "^[A-Z0-9.-]{1,10}$")) { Status = "Enter a valid ticker symbol"; return; }
        if (Rows.All(row => row.Symbol != symbol)) Rows.Add(new StockRow(symbol, null, null));
        NewSymbol = "";
        Save();
        await RefreshAsync();
    }

    private async void Refresh_Click(object sender, RoutedEventArgs e) => await RefreshAsync();
    private async void DiscoverModels_Click(object sender, RoutedEventArgs e) => await DiscoverModelsAsync(true);
    private void FindEndpoints_Click(object sender, RoutedEventArgs e)
    {
        PopulateEndpointOptions();
        Status = $"Loaded {EndpointOptions.Count} endpoint option(s) from localhost and active LAN adapters.";
    }

    private void Delete_Click(object sender, RoutedEventArgs e)
    {
        if (SelectedRow is not null) Rows.Remove(SelectedRow);
        Save();
    }

    private void Clear_Click(object sender, RoutedEventArgs e)
    {
        if (MessageBox.Show("Clear every saved symbol and holding?", "Clear watchlist", MessageBoxButton.YesNo, MessageBoxImage.Warning) == MessageBoxResult.Yes)
        { Rows.Clear(); Save(); }
    }

    private async Task DiscoverModelsAsync(bool reportSuccess)
    {
        try
        {
            var discovered = await new OllamaClient(httpClient).GetModelsAsync(OllamaEndpoint, lifetime.Token);
            Models.Clear();
            foreach (var model in discovered) Models.Add(model);
            PopulateModelOptions(discovered);
            if (string.IsNullOrWhiteSpace(SelectedModel)) SelectedModel = ModelOptions.FirstOrDefault();
            Status = Models.Count == 0 ? "Ollama found; install a free model" : reportSuccess ? $"Found {Models.Count} local model(s)" : Status;
        }
        catch
        {
            PopulateModelOptions([]);
            Status = "Ollama unavailable; technical analysis remains active";
        }
    }

    private void PopulateEndpointOptions()
    {
        var values = new HashSet<string>(StringComparer.OrdinalIgnoreCase)
        {
            "http://127.0.0.1:11434",
            "http://localhost:11434",
            "http://host.docker.internal:11434",
            $"http://{Environment.MachineName}:11434",
        };

        foreach (var networkInterface in NetworkInterface.GetAllNetworkInterfaces()
                     .Where(adapter => adapter.OperationalStatus == OperationalStatus.Up &&
                                       adapter.NetworkInterfaceType is not NetworkInterfaceType.Loopback and not NetworkInterfaceType.Tunnel))
        {
            var ipProperties = networkInterface.GetIPProperties();
            foreach (var address in ipProperties.UnicastAddresses.Where(item => item.Address.AddressFamily == AddressFamily.InterNetwork))
            {
                values.Add($"http://{address.Address}:11434");
            }
        }

        ReplaceCollection(EndpointOptions, values.OrderBy(item => item));
        if (!EndpointOptions.Contains(OllamaEndpoint)) EndpointOptions.Insert(0, OllamaEndpoint);
    }

    private void PopulateModelOptions(IEnumerable<string> discovered)
    {
        var values = new List<string>();
        values.AddRange(discovered.Where(item => !string.IsNullOrWhiteSpace(item)));
        values.AddRange(DefaultModelExamples);
        ReplaceCollection(ModelOptions, values.Distinct(StringComparer.OrdinalIgnoreCase));
        if (!string.IsNullOrWhiteSpace(SelectedModel) && !ModelOptions.Contains(SelectedModel)) ModelOptions.Insert(0, SelectedModel);
    }

    private static void ReplaceCollection(ObservableCollection<string> target, IEnumerable<string> values)
    {
        target.Clear();
        foreach (var value in values) target.Add(value);
    }

    private static readonly string[] DefaultModelExamples =
    [
        "gpt-5.3-codex",
        "qwen3:4b",
        "qwen3:8b",
        "llama3.1:8b",
        "mistral:7b",
        "phi4:latest",
    ];

    private async Task RefreshAsync()
    {
        if (isRefreshing) return;
        isRefreshing = true;
        Status = "Refreshing live evidence";
        try
        {
            var analyzer = new AnalysisService(httpClient, FinnhubApiKey);
            var ollama = new OllamaClient(httpClient);
            foreach (var row in Rows)
            {
                try
                {
                    var analysis = await analyzer.AnalyzeAsync(row.Symbol, SelectedHorizon, lifetime.Token);
                    row.ApplyTechnical(analysis);
                    if (analysis.Recommendation == Recommendation.Unavailable)
                    {
                        row.ApplyModelError("No local-model review was requested because current validated analysis was unavailable.");
                    }
                    else if (UseLocalModel && !string.IsNullOrWhiteSpace(SelectedModel))
                    {
                        var normalizedModel = NormalizeRequestedModel(SelectedModel);
                        if (!string.Equals(normalizedModel, SelectedModel, StringComparison.OrdinalIgnoreCase))
                        {
                            SelectedModel = normalizedModel;
                        }
                        try { row.ApplyModel(await ollama.ReviewAsync(OllamaEndpoint, normalizedModel, analysis, lifetime.Token), normalizedModel); }
                        catch (Exception error) { row.ApplyModelError($"{error.Message} Technical result retained."); }
                    }
                    else row.ApplyModelError(UseLocalModel ? "No installed model selected. Technical result retained." : "Local model review off.");
                }
                catch (Exception error) { row.ApplyDataError($"Live validation failed: {error.Message}"); }
            }
            Status = $"Updated {DateTime.Now:t}; next live refresh in 60 seconds";
            Save();
        }
        finally { isRefreshing = false; }
    }

    private void Save() => store.Save(new AppSettings(OllamaEndpoint, SelectedModel, UseLocalModel, FinnhubApiKey.Trim(),
        Rows.Select(row => new SavedRow(
            row.Symbol,
            row.Quantity,
            row.AverageCost,
            row.LastOvernightPrice,
            row.LastOvernightChange,
            row.LastOvernightPercent,
            row.LastAfterHoursPrice,
            row.LastAfterHoursChange,
            row.LastAfterHoursPercent)).ToList()));

    private static string NormalizeRequestedModel(string model)
    {
        var lowered = model.Trim().ToLowerInvariant();
        return lowered switch
        {
            "gpt-5.3-codex" or "gpt-5-codex" or "gpt-5" or "ghcp" or "copilot" => "qwen3:8b",
            _ => model.Trim(),
        };
    }

    public event PropertyChangedEventHandler? PropertyChanged;
    private void Set<T>(ref T field, T value, [CallerMemberName] string? propertyName = null)
    { if (EqualityComparer<T>.Default.Equals(field, value)) return; field = value; PropertyChanged?.Invoke(this, new(propertyName)); }
}

public sealed class StockRow(
    string symbol,
    decimal? quantity,
    decimal? averageCost,
    double? savedOvernightPrice = null,
    double? savedOvernightChange = null,
    double? savedOvernightPercent = null,
    double? savedAfterHoursPrice = null,
    double? savedAfterHoursChange = null,
    double? savedAfterHoursPercent = null) : INotifyPropertyChanged
{
    private string price = "-", extendedSession = "-", overnightGrid = "Unavailable", preMarketGrid = "Unavailable", afterHoursGrid = "Unavailable", technical = "-", technicalRange = "-", confidence = "-", modelResult = "-", modelRationale = "Waiting";
    private string technicalSummary = "Analysis has not refreshed yet.", sourceDetails = "Not available.", indicatorDetails = "Not calculated.";
    private string signalDetails = "Not calculated.", newsDetails = "No news evidence loaded.", modelDetails = "Local model review pending.";
    private string warningDetails = "None reported.", finalReason = "Analysis has not refreshed yet.";
    private Brush technicalBrush = DirectionBrushes.Neutral, overnightBrush = DirectionBrushes.Neutral, preMarketBrush = DirectionBrushes.Neutral, modelBrush = DirectionBrushes.Neutral;
    private Brush priceFlashBrush = DirectionBrushes.Transparent, overnightFlashBrush = DirectionBrushes.Transparent, preMarketFlashBrush = DirectionBrushes.Transparent, afterHoursFlashBrush = DirectionBrushes.Transparent;
    private double? previousPrice, previousOvernightPrice, previousPreMarketPrice, previousAfterHoursPrice;
    private SessionValues? lastOvernight = RestoreSession(savedOvernightPrice, savedOvernightChange, savedOvernightPercent);
    private SessionValues? lastAfterHours = RestoreSession(savedAfterHoursPrice, savedAfterHoursChange, savedAfterHoursPercent);
    private decimal? quantity = quantity, averageCost = averageCost;
    public string Symbol { get; } = symbol;
    public string Price { get => price; private set => Set(ref price, value); }
    public string ExtendedSession { get => extendedSession; private set => Set(ref extendedSession, value); }
    public string OvernightGrid { get => overnightGrid; private set => Set(ref overnightGrid, value); }
    public string PreMarketGrid { get => preMarketGrid; private set => Set(ref preMarketGrid, value); }
    public string AfterHoursGrid { get => afterHoursGrid; private set => Set(ref afterHoursGrid, value); }
    public string Technical { get => technical; private set => Set(ref technical, value); }
    public string TechnicalRange { get => technicalRange; private set => Set(ref technicalRange, value); }
    public string Confidence { get => confidence; private set => Set(ref confidence, value); }
    public string ModelResult { get => modelResult; private set => Set(ref modelResult, value); }
    public string ModelRationale { get => modelRationale; private set => Set(ref modelRationale, value); }
    public string TechnicalSummary { get => technicalSummary; private set => Set(ref technicalSummary, value); }
    public string SourceDetails { get => sourceDetails; private set => Set(ref sourceDetails, value); }
    public string IndicatorDetails { get => indicatorDetails; private set => Set(ref indicatorDetails, value); }
    public string SignalDetails { get => signalDetails; private set => Set(ref signalDetails, value); }
    public string NewsDetails { get => newsDetails; private set => Set(ref newsDetails, value); }
    public string ModelDetails { get => modelDetails; private set => Set(ref modelDetails, value); }
    public string WarningDetails { get => warningDetails; private set => Set(ref warningDetails, value); }
    public string FinalReason { get => finalReason; private set => Set(ref finalReason, value); }
    public Brush TechnicalBrush { get => technicalBrush; private set => Set(ref technicalBrush, value); }
    public Brush OvernightBrush { get => overnightBrush; private set => Set(ref overnightBrush, value); }
    public Brush PreMarketBrush { get => preMarketBrush; private set => Set(ref preMarketBrush, value); }
    public Brush ModelBrush { get => modelBrush; private set => Set(ref modelBrush, value); }
    public Brush PriceFlashBrush { get => priceFlashBrush; private set => Set(ref priceFlashBrush, value); }
    public Brush OvernightFlashBrush { get => overnightFlashBrush; private set => Set(ref overnightFlashBrush, value); }
    public Brush PreMarketFlashBrush { get => preMarketFlashBrush; private set => Set(ref preMarketFlashBrush, value); }
    public Brush AfterHoursFlashBrush { get => afterHoursFlashBrush; private set => Set(ref afterHoursFlashBrush, value); }
    public decimal? Quantity { get => quantity; set => Set(ref quantity, value); }
    public decimal? AverageCost { get => averageCost; set => Set(ref averageCost, value); }
    public double? LastOvernightPrice => lastOvernight?.Price;
    public double? LastOvernightChange => lastOvernight?.Change;
    public double? LastOvernightPercent => lastOvernight?.Percent;
    public double? LastAfterHoursPrice => lastAfterHours?.Price;
    public double? LastAfterHoursChange => lastAfterHours?.Change;
    public double? LastAfterHoursPercent => lastAfterHours?.Percent;

    public void ApplyTechnical(AnalysisResult value)
    {
        var overnight = RetainSession(value.Quote, value.Quote?.OvernightPrice, value.Quote?.OvernightChange, value.Quote?.OvernightChangePercent, ref lastOvernight);
        var afterHours = RetainSession(value.Quote, value.Quote?.AfterHoursPrice, value.Quote?.AfterHoursChange, value.Quote?.AfterHoursChangePercent, ref lastAfterHours);
        UpdateFlash(value.Quote?.Price, ref previousPrice, brush => PriceFlashBrush = brush);
        UpdateFlash(overnight?.Price, ref previousOvernightPrice, brush => OvernightFlashBrush = brush);
        UpdateFlash(SessionFlashValue(value.Quote, value.Quote?.PreMarketPrice, value.Quote?.PreMarketChange), ref previousPreMarketPrice, brush => PreMarketFlashBrush = brush);
        UpdateFlash(afterHours?.Price, ref previousAfterHoursPrice, brush => AfterHoursFlashBrush = brush);
        Price = value.Quote?.Price.ToString("C2") ?? "Unavailable";
        ExtendedSession = ExtendedSessionSummary(value.Quote, afterHours);
        OvernightGrid = SessionGridText(overnight?.Price, overnight?.Change, overnight?.Percent, null);
        OvernightBrush = DirectionBrushes.ForSessionChange(overnight?.Change);
        PreMarketGrid = SessionGridText(value.Quote?.PreMarketPrice, value.Quote?.PreMarketChange, value.Quote?.PreMarketChangePercent, value.Quote?.Price);
        PreMarketBrush = DirectionBrushes.ForPreMarketChange(value.Quote?.PreMarketChange ??
            (value.Quote?.PreMarketPrice is double pre && value.Quote?.Price is double baseline ? pre - baseline : null));
        AfterHoursGrid = SessionGridText(afterHours?.Price, afterHours?.Change, afterHours?.Percent, null);
        Technical = value.Recommendation.ToString().ToUpperInvariant();
        TechnicalRange = value.ProjectedPriceRange is null ? "Unavailable" : $"{value.ProjectedPriceRange.Low:C2} - {value.ProjectedPriceRange.High:C2}";
        Confidence = $"{value.Confidence}%";
        TechnicalBrush = DirectionBrushes.For(value.Direction);
        ModelRationale = value.Reason;
        TechnicalSummary = $"{Technical} · {TechnicalRange} · Confidence {Confidence}";
        SourceDetails = $"Provider: {value.Provider}\nRetrieved: {value.RetrievedAt.LocalDateTime:g}\nLatest source: {value.LastDataTimestamp?.LocalDateTime:g}\nSource age: {value.SourceAgeMinutes?.ToString() ?? "Unavailable"} minutes\nCandle interval: {value.CandleIntervalMinutes} minute(s)\nLast overnight: {OvernightGrid}\nPre/After market: {ExtendedSession}\nLatest quote: {Price}";
        IndicatorDetails = value.Indicators is null ? "Indicators were not calculated because live-data validation failed." :
            $"Momentum: {Display(value.Indicators.MomentumPercent)}%\nShort moving average: {Display(value.Indicators.ShortMovingAverage)}\nLong moving average: {Display(value.Indicators.LongMovingAverage)}\nRelative volume: {Display(value.Indicators.RelativeVolume)}\nRSI: {Display(value.Indicators.Rsi)}\nMACD: {Display(value.Indicators.Macd)}\nVWAP: {Display(value.Indicators.Vwap)}\nFresh news sentiment: {Display(value.Indicators.SentimentAverage)}";
        SignalDetails = value.Signals.Count == 0 ? "Signals were not calculated." : string.Join("\n", value.Signals.Select(signal =>
            $"{signal.Name}: value {Display(signal.Value)} x weight {signal.Weight:F2} = {Display(signal.Contribution)}"));
        NewsDetails = value.News?.Items.Count > 0
            ? string.Join("\n\n", value.News.Items.Select(item => $"{item.Headline}\n{item.Source} · {item.PublishedAt.LocalDateTime:g} · Score {item.Score:+0.000;-0.000;0.000}"))
            : "No timestamped news was returned for this refresh.";
        WarningDetails = value.Warnings.Count == 0 ? "None reported." : string.Join("\n", value.Warnings.Select(warning => $"- {warning}"));
        FinalReason = value.Reason;
    }
    public void ApplyModel(ModelReview value, string model)
    {
        ModelResult = $"{value.Recommendation} {value.Low:C2}-{value.High:C2}";
        ModelBrush = DirectionBrushes.For(value.Recommendation);
        ModelRationale = value.Rationale;
        ModelDetails = $"Model: {model}\nRecommendation: {value.Recommendation}\nProjected range: {value.Low:C2} - {value.High:C2}\nRationale: {value.Rationale}\nOnly evidence current at refresh time was supplied. This secondary local-model review does not replace the validated technical baseline.";
    }
    public void ApplyModelError(string message)
    { ModelResult = "Unavailable"; ModelBrush = DirectionBrushes.Neutral; ModelRationale = message; ModelDetails = message; }
    public void ApplyDataError(string message)
    {
        ResetFlashes();
        Price = ExtendedSession = PreMarketGrid = Technical = TechnicalRange = Confidence = "Unavailable";
        OvernightGrid = SessionGridText(lastOvernight?.Price, lastOvernight?.Change, lastOvernight?.Percent, null);
        AfterHoursGrid = SessionGridText(lastAfterHours?.Price, lastAfterHours?.Change, lastAfterHours?.Percent, null);
        ModelResult = "Unavailable";
        TechnicalBrush = PreMarketBrush = ModelBrush = DirectionBrushes.Neutral;
        OvernightBrush = DirectionBrushes.ForSessionChange(lastOvernight?.Change);
        ModelRationale = message;
        TechnicalSummary = "UNAVAILABLE · No projection generated";
        SourceDetails = IndicatorDetails = SignalDetails = NewsDetails = "Not available because live-data validation failed.";
        ModelDetails = "No local-model review was requested because the validated baseline was unavailable.";
        WarningDetails = FinalReason = message;
    }

    private static void UpdateFlash(double? currentValue, ref double? previousValue, Action<Brush> setBrush)
    {
        var previous = previousValue;
        previousValue = currentValue;
        setBrush((previous, currentValue) switch
        {
            (double oldValue, double newValue) when newValue - oldValue > 0.00005 => DirectionBrushes.FlashRise,
            (double oldValue, double newValue) when newValue - oldValue < -0.00005 => DirectionBrushes.FlashDrop,
            _ => DirectionBrushes.Transparent,
        });
    }

    private static double? SessionFlashValue(Quote? quote, double? sessionPrice, double? sessionChange) =>
        sessionPrice ?? (quote is not null && sessionChange is not null ? quote.Price + sessionChange : null);

    private static SessionValues? RetainSession(
        Quote? quote,
        double? sessionPrice,
        double? sessionChange,
        double? sessionPercent,
        ref SessionValues? retained)
    {
        if (quote is null || sessionPrice is null && sessionChange is null) return retained;
        var resolvedPrice = sessionPrice ?? quote.Price + sessionChange!.Value;
        var resolvedChange = sessionChange ?? resolvedPrice - quote.Price;
        var resolvedPercent = sessionPercent ?? (quote.Price != 0.0 ? (resolvedChange / quote.Price) * 100.0 : null);
        retained = new SessionValues(resolvedPrice, resolvedChange, resolvedPercent);
        return retained;
    }

    private static SessionValues? RestoreSession(double? price, double? change, double? percent) =>
        price is double savedPrice && change is double savedChange
            ? new SessionValues(savedPrice, savedChange, percent)
            : null;

    private void ResetFlashes()
    {
        previousPrice = previousOvernightPrice = previousPreMarketPrice = previousAfterHoursPrice = null;
        PriceFlashBrush = OvernightFlashBrush = PreMarketFlashBrush = AfterHoursFlashBrush = DirectionBrushes.Transparent;
    }

    private static string ExtendedSessionSummary(Quote? quote, SessionValues? afterHours)
    {
        if (quote is null) return "Unavailable";
        var pre = SessionText("Pre", quote.PreMarketPrice, quote.PreMarketChangePercent);
        var after = SessionText("After (last)", afterHours?.Price, afterHours?.Percent);
        return pre is not null && after is not null
            ? $"{pre} | {after}"
            : pre ?? after ?? "Unavailable";
    }

    private static string? SessionText(string label, double? price, double? changePercent)
    {
        if (price is null && changePercent is null) return null;
        var priceText = price is null ? "-" : price.Value.ToString("C2");
        var percentText = changePercent is null ? "-" : $"{NormalizeSignedPercent(changePercent.Value):+0.00;-0.00;0.00}%";
        return $"{label} {priceText} ({percentText})";
    }

    private static string SessionGridText(double? sessionPrice, double? sessionChange, double? sessionPercent, double? regularPrice)
    {
        if (sessionPrice is null && sessionChange is null && sessionPercent is null) return "Unavailable";
        var resolvedPrice = sessionPrice ?? (regularPrice is double regular && sessionChange is double change ? regular + change : null);
        var delta = sessionChange ?? (resolvedPrice is double price && regularPrice is double baseline ? price - baseline : null);
        var priceText = resolvedPrice is null ? "-" : resolvedPrice.Value.ToString("C2");
        var deltaText = delta is null ? "-" : $"{NormalizeSignedPercent(delta.Value):+0.00;-0.00;0.00}";
        var percentText = sessionPercent is null ? "-" : $"{NormalizeSignedPercent(sessionPercent.Value):+0.00;-0.00;0.00}%";
        return $"{priceText} ({deltaText}, {percentText})";
    }

    private static double NormalizeSignedPercent(double value) => Math.Abs(value) < 0.00005 ? 0.0 : value;

    private static string Display(double? value) => value?.ToString("F4") ?? "Unsupported / unavailable";

    private sealed record SessionValues(double Price, double Change, double? Percent);

    public event PropertyChangedEventHandler? PropertyChanged;
    private void Set<T>(ref T field, T value, [CallerMemberName] string? propertyName = null)
    { if (EqualityComparer<T>.Default.Equals(field, value)) return; field = value; PropertyChanged?.Invoke(this, new(propertyName)); }
}

internal static class DirectionBrushes
{
    public static readonly Brush Transparent = FrozenBrush(0x00, 0x00, 0x00, 0x00);
    public static readonly Brush FlashRise = FrozenBrush(0x66, 0x34, 0xC7, 0x59);
    public static readonly Brush FlashDrop = FrozenBrush(0x66, 0xFF, 0x3B, 0x30);
    public static readonly Brush Rise = FrozenBrush(0x15, 0x65, 0xC0);
    public static readonly Brush Drop = FrozenBrush(0x7B, 0x1F, 0xA2);
    public static readonly Brush Neutral = FrozenBrush(0x6B, 0x6B, 0x72);
    public static readonly Brush Positive = FrozenBrush(0x16, 0x80, 0x3C);
    public static readonly Brush Negative = FrozenBrush(0xC6, 0x28, 0x28);
    public static readonly Brush Active = FrozenBrush(0xC2, 0x8C, 0x00);

    public static Brush For(Direction direction) => direction switch
    {
        Direction.Up => Rise,
        Direction.Down => Drop,
        _ => Neutral,
    };

    public static Brush For(string recommendation) => recommendation.ToUpperInvariant() switch
    {
        "BUY" => Rise,
        "SELL" => Drop,
        _ => Neutral,
    };

    public static Brush ForSessionChange(double? change) => change switch
    {
        > 0.00005 => Positive,
        < -0.00005 => Negative,
        _ => Neutral,
    };

    public static Brush ForPreMarketChange(double? change) => change switch
    {
        > 0.00005 => Active,
        < -0.00005 => Active,
        _ => Neutral,
    };

    private static Brush FrozenBrush(byte red, byte green, byte blue)
        => FrozenBrush(0xFF, red, green, blue);

    private static Brush FrozenBrush(byte alpha, byte red, byte green, byte blue)
    {
        var brush = new SolidColorBrush(Color.FromArgb(alpha, red, green, blue));
        brush.Freeze();
        return brush;
    }
}
