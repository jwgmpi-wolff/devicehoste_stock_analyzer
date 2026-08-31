using System.Globalization;
using System.IO;
using System.Text;

namespace StockMovementAnalyzer.Windows;

public sealed record CoinbaseHoldingEstimate(string Symbol, decimal Quantity, decimal? AverageCost);
public sealed record CoinbaseImportResult(IReadOnlyList<CoinbaseHoldingEstimate> Holdings, int TransactionCount);

public static class CoinbaseCsvImporter
{
    public const string Source = "Coinbase CSV estimate";

    public static CoinbaseImportResult Import(string csv)
    {
        var rows = ParseRows(csv);
        if (rows.Count == 0) throw new InvalidDataException("The Coinbase CSV is empty.");
        var headerIndex = rows.FindIndex(IsCoinbaseHeader);
        if (headerIndex < 0) throw new InvalidDataException("The selected file is not a Coinbase transaction CSV.");
        var columns = rows[headerIndex]
            .Select((value, index) => (Name: NormalizeHeader(value), Index: index))
            .GroupBy(item => item.Name)
            .ToDictionary(group => group.Key, group => group.First().Index);

        var transactions = rows.Skip(headerIndex + 1).Select(row => ParseTransaction(row, columns))
            .Where(transaction => transaction is not null)
            .Cast<Transaction>()
            .OrderBy(transaction => transaction.Timestamp)
            .ToList();
        var balances = new Dictionary<string, Balance>(StringComparer.OrdinalIgnoreCase);
        foreach (var transaction in transactions)
        {
            if (!balances.TryGetValue(transaction.Asset, out var balance))
            {
                balance = new Balance();
                balances[transaction.Asset] = balance;
            }
            if (IsDisposal(transaction.Type)) balance.Dispose(transaction.Quantity);
            else if (IsAcquisition(transaction.Type)) balance.Acquire(transaction.Quantity, AcquisitionCost(transaction));
        }

        var holdings = balances.Where(item => item.Value.Quantity > QuantityEpsilon)
            .Select(item => new CoinbaseHoldingEstimate(
                $"{item.Key.ToUpperInvariant()}-USD",
                item.Value.Quantity,
                item.Value.CostBasisComplete ? item.Value.KnownCost / item.Value.Quantity : null))
            .ToList();
        return new CoinbaseImportResult(holdings, transactions.Count);
    }

    private static Transaction? ParseTransaction(IReadOnlyList<string> row, IReadOnlyDictionary<string, int> columns)
    {
        var type = Value(row, columns, Type).Trim().ToLowerInvariant();
        var asset = Value(row, columns, Asset).Trim().ToUpperInvariant();
        var quantity = ParseNumber(Value(row, columns, Quantity));
        if (string.IsNullOrWhiteSpace(type) || string.IsNullOrWhiteSpace(asset) || quantity is null || quantity == 0m) return null;
        return new Transaction(
            ParseTimestamp(Value(row, columns, Timestamp)),
            type,
            asset,
            Math.Abs(quantity.Value),
            ParseNumber(Value(row, columns, SpotPrice)),
            Abs(ParseNumber(Value(row, columns, Subtotal))),
            Abs(ParseNumber(Value(row, columns, Total))),
            Abs(ParseNumber(Value(row, columns, Fees))) ?? 0m);
    }

    private static decimal? AcquisitionCost(Transaction transaction) => transaction switch
    {
        { Total: > 0m } => transaction.Total,
        { Subtotal: > 0m } => transaction.Subtotal + transaction.Fees,
        { SpotPrice: > 0m } => transaction.Quantity * transaction.SpotPrice + transaction.Fees,
        _ => null,
    };

    private static bool IsCoinbaseHeader(IReadOnlyList<string> row)
    {
        var names = row.Select(NormalizeHeader).ToHashSet(StringComparer.OrdinalIgnoreCase);
        return RequiredColumns.All(names.Contains);
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
            else if (!quoted && character == ',')
            {
                row.Add(field.ToString());
                field.Clear();
            }
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
        if (quoted) throw new InvalidDataException("The Coinbase CSV contains an unterminated quoted value.");
        return rows;
    }

    private static string NormalizeHeader(string value)
    {
        var normalized = value.Trim().TrimStart('\uFEFF').ToLowerInvariant();
        return HeaderAliases.GetValueOrDefault(normalized, normalized);
    }

    private static string Value(IReadOnlyList<string> row, IReadOnlyDictionary<string, int> columns, string name) =>
        columns.TryGetValue(name, out var index) && index < row.Count ? row[index] : "";

    private static decimal? ParseNumber(string value) => decimal.TryParse(
        value.Trim().Replace("$", "").Replace(",", ""),
        NumberStyles.Number | NumberStyles.AllowDecimalPoint | NumberStyles.AllowLeadingSign,
        CultureInfo.InvariantCulture,
        out var number) ? number : null;

    private static decimal? Abs(decimal? value) => value is null ? null : Math.Abs(value.Value);
    private static DateTimeOffset ParseTimestamp(string value) => DateTimeOffset.TryParse(
        value.Trim(), CultureInfo.InvariantCulture, DateTimeStyles.AssumeUniversal, out var timestamp)
        ? timestamp : DateTimeOffset.MinValue;
    private static bool IsDisposal(string type) => DisposalTypes.Any(type.Contains);
    private static bool IsAcquisition(string type) => AcquisitionTypes.Any(type.Contains);

    private sealed record Transaction(DateTimeOffset Timestamp, string Type, string Asset, decimal Quantity,
        decimal? SpotPrice, decimal? Subtotal, decimal? Total, decimal Fees);

    private sealed class Balance
    {
        public decimal Quantity { get; private set; }
        public decimal KnownCost { get; private set; }
        public bool CostBasisComplete { get; private set; } = true;

        public void Acquire(decimal quantity, decimal? cost)
        {
            Quantity += quantity;
            if (cost is null) CostBasisComplete = false;
            else KnownCost += cost.Value;
        }

        public void Dispose(decimal quantity)
        {
            if (Quantity <= QuantityEpsilon) return;
            var removed = Math.Min(quantity, Quantity);
            KnownCost -= KnownCost * (removed / Quantity);
            Quantity -= removed;
            if (Quantity <= QuantityEpsilon)
            {
                Quantity = 0m;
                KnownCost = 0m;
                CostBasisComplete = true;
            }
        }
    }

    private const string Timestamp = "timestamp";
    private const string Type = "transaction type";
    private const string Asset = "asset";
    private const string Quantity = "quantity transacted";
    private const string SpotPrice = "spot price at transaction";
    private const string Subtotal = "subtotal";
    private const string Total = "total (inclusive of fees and/or spread)";
    private const string Fees = "fees and/or spread";
    private const decimal QuantityEpsilon = 0.000000001m;
    private static readonly string[] RequiredColumns = [Type, Asset, Quantity];
    private static readonly string[] AcquisitionTypes = ["buy", "receive", "reward", "learning reward", "staking income"];
    private static readonly string[] DisposalTypes = ["sell", "send", "withdraw"];
    private static readonly Dictionary<string, string> HeaderAliases = new(StringComparer.OrdinalIgnoreCase)
    {
        ["timestamp"] = Timestamp,
        ["transaction type"] = Type,
        ["asset"] = Asset,
        ["quantity transacted"] = Quantity,
        ["spot price at transaction"] = SpotPrice,
        ["spot price at transaction ($)"] = SpotPrice,
        ["price at transaction"] = SpotPrice,
        ["price at transaction ($)"] = SpotPrice,
        ["price at transaction (usd)"] = SpotPrice,
        ["subtotal"] = Subtotal,
        ["subtotal ($)"] = Subtotal,
        ["total (inclusive of fees and/or spread)"] = Total,
        ["total (inclusive of fees and/or spread) ($)"] = Total,
        ["total (inclusive of fees)"] = Total,
        ["total (inclusive of fees) ($)"] = Total,
        ["fees and/or spread"] = Fees,
        ["fees and/or spread ($)"] = Fees,
        ["fees"] = Fees,
        ["fees ($)"] = Fees,
    };
}