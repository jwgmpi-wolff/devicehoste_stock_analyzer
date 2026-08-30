using System.IO;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace StockMovementAnalyzer.Windows;

public sealed record SavedRow(
    string Symbol,
    decimal? Quantity,
    decimal? AverageCost,
    double? LastOvernightPrice = null,
    double? LastOvernightChange = null,
    double? LastOvernightPercent = null,
    double? LastAfterHoursPrice = null,
    double? LastAfterHoursChange = null,
    double? LastAfterHoursPercent = null);
public sealed record AppSettings(string OllamaEndpoint, string? Model, bool UseLocalModel, string FinnhubApiKey, List<SavedRow> Rows);

public sealed class AppStore
{
    private static readonly JsonSerializerOptions JsonOptions = new() { WriteIndented = true };
    private static readonly Regex SymbolPattern = new("^[A-Z0-9.-]{1,10}$", RegexOptions.Compiled);
    private readonly string path;

    public AppStore(string? settingsPath = null)
    {
        path = settingsPath ?? Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "StockMovementAnalyzer", "settings.json");
    }

    public AppSettings Load()
    {
        return TryLoad(path) ?? TryLoad(path + ".bak") ?? Defaults();
    }

    public void Save(AppSettings settings)
    {
        Directory.CreateDirectory(Path.GetDirectoryName(path)!);
        var temporaryPath = path + ".tmp";
        File.WriteAllText(temporaryPath, JsonSerializer.Serialize(settings, JsonOptions));
        if (File.Exists(path)) File.Copy(path, path + ".bak", true);
        File.Move(temporaryPath, path, true);
    }

    private static AppSettings? TryLoad(string candidate)
    {
        try
        {
            using var document = JsonDocument.Parse(File.ReadAllText(candidate));
            var root = document.RootElement;
            var endpoint = ReadString(root, "OllamaEndpoint") ?? "http://127.0.0.1:11434";
            var model = ReadString(root, "Model");
            var useLocalModel = root.TryGetProperty("UseLocalModel", out var enabled) && enabled.ValueKind is JsonValueKind.True or JsonValueKind.False
                ? enabled.GetBoolean() : true;
            var finnhubApiKey = ReadString(root, "FinnhubApiKey") ?? "d9k5sapr01qkjjrs460gd9k5sapr01qkjjrs4610";
            var rows = new List<SavedRow>();
            if (root.TryGetProperty("Rows", out var storedRows) && storedRows.ValueKind == JsonValueKind.Array)
            {
                foreach (var row in storedRows.EnumerateArray())
                {
                    var symbol = ReadString(row, "Symbol")?.Trim().ToUpperInvariant();
                    if (symbol is null || !SymbolPattern.IsMatch(symbol) || rows.Any(existing => existing.Symbol == symbol)) continue;
                    rows.Add(new SavedRow(
                        symbol,
                        ReadDecimal(row, "Quantity"),
                        ReadDecimal(row, "AverageCost"),
                        ReadDouble(row, "LastOvernightPrice"),
                        ReadDouble(row, "LastOvernightChange"),
                        ReadDouble(row, "LastOvernightPercent"),
                        ReadDouble(row, "LastAfterHoursPrice"),
                        ReadDouble(row, "LastAfterHoursChange"),
                        ReadDouble(row, "LastAfterHoursPercent")));
                }
            }
            return new AppSettings(endpoint, model, useLocalModel, finnhubApiKey.Trim(), rows);
        }
        catch { return null; }
    }

    private static string? ReadString(JsonElement element, string name) =>
        element.TryGetProperty(name, out var value) && value.ValueKind == JsonValueKind.String ? value.GetString() : null;

    private static decimal? ReadDecimal(JsonElement element, string name) =>
        element.TryGetProperty(name, out var value) && value.ValueKind == JsonValueKind.Number && value.TryGetDecimal(out var number) ? number : null;

    private static double? ReadDouble(JsonElement element, string name) =>
        element.TryGetProperty(name, out var value) && value.ValueKind == JsonValueKind.Number && value.TryGetDouble(out var number) ? number : null;

    private static AppSettings Defaults() => new("http://127.0.0.1:11434", null, true, "d9k5sapr01qkjjrs460gd9k5sapr01qkjjrs4610",
        [new("MSFT", null, null), new("AAPL", null, null), new("NVDA", null, null)]);
}
