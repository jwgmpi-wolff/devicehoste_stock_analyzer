using System.Globalization;
using System.IO;
using System.Text;
using System.Text.RegularExpressions;

namespace StockMovementAnalyzer.Windows;

public sealed record FidelityHoldingEstimate(string Symbol, decimal Quantity, decimal? AverageCost);
public sealed record FidelityImportResult(IReadOnlyList<FidelityHoldingEstimate> Holdings, int PositionCount);

public static partial class FidelityCsvImporter
{
    public const string Source = "Fidelity CSV";

    public static FidelityImportResult Import(string csv)
    {
        var rows = ParseRows(csv);
        if (rows.Count == 0) throw new InvalidDataException("The Fidelity CSV is empty.");
        var headerIndex = rows.FindIndex(IsFidelityHeader);
        if (headerIndex < 0) throw new InvalidDataException("The selected file is not a Fidelity positions CSV.");
        var columns = rows[headerIndex].Select((value, index) => (Name: NormalizeHeader(value), Index: index))
            .GroupBy(item => item.Name).ToDictionary(group => group.Key, group => group.First().Index);
        var positions = rows.Skip(headerIndex + 1).Select(row => ParsePosition(row, columns))
            .Where(position => position is not null).Cast<Position>().ToList();
        var holdings = positions.GroupBy(position => position.Symbol).Select(group =>
        {
            var quantity = group.Sum(position => position.Quantity);
            return new FidelityHoldingEstimate(
                group.Key,
                quantity,
                group.All(position => position.CostBasis is not null)
                    ? group.Sum(position => position.CostBasis!.Value) / quantity
                    : null);
        }).OrderBy(holding => holding.Symbol).ToList();
        return new FidelityImportResult(holdings, positions.Count);
    }

    private static Position? ParsePosition(IReadOnlyList<string> row, IReadOnlyDictionary<string, int> columns)
    {
        var symbol = Value(row, columns, Symbol).Trim().ToUpperInvariant();
        var quantity = ParseNumber(Value(row, columns, Quantity));
        if (!SymbolPattern().IsMatch(symbol) || symbol.Contains('*') || quantity is null || quantity <= 0m) return null;
        return new Position(symbol, quantity.Value, Abs(ParseNumber(Value(row, columns, CostBasisTotal))));
    }

    private static bool IsFidelityHeader(IReadOnlyList<string> row)
    {
        var columns = row.Select(NormalizeHeader).ToHashSet(StringComparer.OrdinalIgnoreCase);
        return RequiredColumns.All(columns.Contains);
    }

    private static List<List<string>> ParseRows(string csv)
    {
        var rows = new List<List<string>>();
        var row = new List<string>();
        var field = new StringBuilder();
        var quoted = false;
        for (var index = 0; index < csv.Length; index++)
        {
            var character = csv[index];
            if (quoted && character == '"' && index + 1 < csv.Length && csv[index + 1] == '"')
            {
                field.Append('"');
                index++;
            }
            else if (character == '"') quoted = !quoted;
            else if (!quoted && character == ',') { row.Add(field.ToString()); field.Clear(); }
            else if (!quoted && character is '\r' or '\n')
            {
                if (character == '\r' && index + 1 < csv.Length && csv[index + 1] == '\n') index++;
                row.Add(field.ToString());
                field.Clear();
                if (row.Any(value => !string.IsNullOrWhiteSpace(value))) rows.Add([.. row]);
                row.Clear();
            }
            else field.Append(character);
        }
        if (field.Length > 0 || row.Count > 0)
        {
            row.Add(field.ToString());
            if (row.Any(value => !string.IsNullOrWhiteSpace(value))) rows.Add(row);
        }
        if (quoted) throw new InvalidDataException("The Fidelity CSV contains an unterminated quoted value.");
        return rows;
    }

    private static string NormalizeHeader(string value) => value.Trim().TrimStart('\uFEFF').ToLowerInvariant();
    private static string Value(IReadOnlyList<string> row, IReadOnlyDictionary<string, int> columns, string name) =>
        columns.TryGetValue(name, out var index) && index < row.Count ? row[index] : "";
    private static decimal? ParseNumber(string value) => decimal.TryParse(
        value.Trim().Replace("$", "").Replace(",", "").Replace("+", ""),
        NumberStyles.Number | NumberStyles.AllowDecimalPoint | NumberStyles.AllowLeadingSign,
        CultureInfo.InvariantCulture,
        out var number) ? number : null;
    private static decimal? Abs(decimal? value) => value is null ? null : Math.Abs(value.Value);

    private sealed record Position(string Symbol, decimal Quantity, decimal? CostBasis);
    private const string Symbol = "symbol";
    private const string Quantity = "quantity";
    private const string CostBasisTotal = "cost basis total";
    private static readonly string[] RequiredColumns = ["account number", "account name", Symbol, Quantity, CostBasisTotal];
    [GeneratedRegex("^[A-Z0-9.-]{1,15}$")]
    private static partial Regex SymbolPattern();
}