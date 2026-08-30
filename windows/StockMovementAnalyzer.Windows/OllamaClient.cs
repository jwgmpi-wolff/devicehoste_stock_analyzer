using System.Net.Http;
using System.Net.Http.Json;
using System.Text.Json;

namespace StockMovementAnalyzer.Windows;

public sealed record ModelReview(string Recommendation, double Low, double High, string Rationale);

public sealed class OllamaClient(HttpClient httpClient, Func<DateTimeOffset>? clock = null)
{
    private static readonly JsonSerializerOptions JsonOptions = new(JsonSerializerDefaults.Web);

    public async Task<IReadOnlyList<string>> GetModelsAsync(string endpoint, CancellationToken cancellationToken)
    {
        using var response = await httpClient.GetAsync($"{Normalize(endpoint)}/api/tags", cancellationToken);
        response.EnsureSuccessStatusCode();
        using var document = JsonDocument.Parse(await response.Content.ReadAsStringAsync(cancellationToken));
        return document.RootElement.GetProperty("models").EnumerateArray()
            .Select(item => item.GetProperty("name").GetString()).Where(name => !string.IsNullOrWhiteSpace(name)).Cast<string>().ToList();
    }

    public async Task<ModelReview> ReviewAsync(string endpoint, string model, AnalysisResult analysis, CancellationToken cancellationToken)
    {
        if (analysis.Recommendation == Recommendation.Unavailable)
            throw new InvalidOperationException("Validated analysis unavailable.");
        var promptTime = (clock ?? (() => DateTimeOffset.UtcNow))();
        var freshnessMinutes = StockAnalyzerEngine.EffectiveFreshnessMinutes(analysis.Horizon, promptTime);
        var latestTimestamp = analysis.LastDataTimestamp ?? throw new InvalidOperationException("Validated market timestamp unavailable.");
        var sourceAgeMinutes = (long)(promptTime - latestTimestamp).TotalMinutes;
        if (sourceAgeMinutes < 0 || sourceAgeMinutes > freshnessMinutes)
            throw new InvalidOperationException("Validated analysis is no longer current.");
        var quote = analysis.Quote ?? throw new InvalidOperationException("Validated quote unavailable.");
        var quoteAgeMinutes = (long)(promptTime - quote.Timestamp).TotalMinutes;
        if (quoteAgeMinutes < 0 || quoteAgeMinutes > freshnessMinutes)
            throw new InvalidOperationException("Validated quote is no longer current.");
        var signals = string.Join("\n", analysis.Signals.Where(signal => signal.Contribution is not null)
            .Select(signal => $"{signal.Name}: value={signal.Value}, weight={signal.Weight}, contribution={signal.Contribution}"));
        var maximumNewsAgeMinutes = analysis.Horizon.IsDaily ? 10_080L : 1_440L;
        var headlines = analysis.News?.Items.Where(item =>
            {
                var age = (long)(promptTime - item.PublishedAt).TotalMinutes;
                return age >= 0 && age <= maximumNewsAgeMinutes &&
                    !string.IsNullOrWhiteSpace(item.Source) && !string.IsNullOrWhiteSpace(item.Headline);
            })
            .Take(8).Select(item => $"- {item.PublishedAt:O}: {item.Headline} ({item.Source})").ToList() ?? [];
        var range = analysis.ProjectedPriceRange;
        var prompt = $$"""
            You are reviewing a current validated stock-analysis snapshot. Use only the timestamped evidence below and no prior model knowledge. Do not invent prices, events, or fundamentals.
            Return JSON only: {"recommendation":"BUY|SELL|HOLD","low":number,"high":number,"rationale":"plain text under 280 characters"}.
            Symbol: {{analysis.Symbol}}
            Horizon: {{analysis.Horizon.Label}}
            Analysis refreshed at: {{analysis.RetrievedAt:O}}
            Latest market timestamp: {{latestTimestamp:O}}
            Current price: {{quote.Price}}
            Technical baseline: {{analysis.Recommendation.ToString().ToUpperInvariant()}}
            Technical projected range: {{range?.Low}} to {{range?.High}}
            Confidence: {{analysis.Confidence}}%
            Signals:
            {{signals}}
            Recent sourced headlines:
            {{(headlines.Count == 0 ? "None available" : string.Join("\n", headlines))}}
            """;
        var payload = new { model, prompt, stream = false, format = "json", options = new { temperature = 0.0 } };
        using var response = await httpClient.PostAsJsonAsync($"{Normalize(endpoint)}/api/generate", payload, cancellationToken);
        response.EnsureSuccessStatusCode();
        using var envelope = JsonDocument.Parse(await response.Content.ReadAsStringAsync(cancellationToken));
        var review = JsonSerializer.Deserialize<ModelReview>(envelope.RootElement.GetProperty("response").GetString() ?? "", JsonOptions)
            ?? throw new InvalidOperationException("Local model returned no review.");
        var recommendation = review.Recommendation.ToUpperInvariant();
        var validRecommendation = recommendation is "BUY" or "SELL" or "HOLD";
        var price = quote.Price;
        var validRange = double.IsFinite(review.Low) && double.IsFinite(review.High) && review.Low > 0 &&
            review.Low < review.High && review.Low >= price * 0.5 && review.High <= price * 1.5;
        if (!validRecommendation || !validRange)
            throw new InvalidOperationException("Local model output failed evidence safeguards.");
        var rationale = review.Rationale.Trim();
        return review with { Recommendation = recommendation, Rationale = rationale[..Math.Min(280, rationale.Length)] };
    }

    private static string Normalize(string endpoint)
    {
        if (!Uri.TryCreate(endpoint, UriKind.Absolute, out var uri) || (uri.Scheme != "http" && uri.Scheme != "https"))
            throw new InvalidOperationException("Enter a valid Ollama HTTP or HTTPS URL.");
        return endpoint.TrimEnd('/');
    }
}
