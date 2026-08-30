# Stock Movement Analyzer

A native Android and Windows app that produces explainable, probabilistic stock movement analysis from current market evidence. An optional Ollama review uses only free models running on hardware you control; no paid or cloud model API is supported. It does not provide financial advice or guaranteed outcomes.

## Architecture

- `app/`: Kotlin, Jetpack Compose, StateFlow/ViewModel, OkHttp, and Kotlin Serialization.
- `domain/`: indicators, news freshness validation, non-hallucination validation, and weighted scoring independent of UI.
- `data/`: keyless Yahoo Finance adapter, optional HTTPS proxy adapter, provider errors, and repository orchestration.
- `ui/`: persistent watchlist, locally logged holdings, adaptive stock-card grid, 10-60 minute and 1/5/10-day horizon controls, live refresh, and evidence detail view.
- `windows/`: native .NET 8 WPF grid, cross-platform parity verification, and self-contained per-user setup for Windows x64 and ARM64.
- `proxy/`: optional dependency-free Node 20 adapter for deployments that already use Finnhub or Alpha Vantage.

The default Android client calls Yahoo Finance public HTTPS endpoints directly. It requires no account, API key, paid plan, proxy deployment, or `marketData.baseUrl` setting. Yahoo Finance is a best-effort public source rather than a contracted application API, so availability, throttling, and response compatibility are not guaranteed. Provider failures never generate substituted market values.

## Live data provider

The app retrieves:

- current price and source timestamp from `query1.finance.yahoo.com/v8/finance/chart`;
- one-minute OHLCV for intraday horizons and daily OHLCV for 1/5/10-day horizons from the same chart endpoint;
- ticker-related headlines, publishers, links, and publication timestamps from `query1.finance.yahoo.com/v1/finance/search`.

Yahoo's public chart currently publishes regular, pre-market, and after-hours samples, but no distinct 20:00-04:00 ET overnight samples. The clients never relabel after-hours data as overnight data. They retain the last genuine overnight snapshot when a provider supplies one and retain the last Yahoo after-hours snapshot from five-day extended-session history. Both snapshots are stored locally and remain visible across refresh failures and app restarts until a newer value from the same session type replaces them.

Quote and candle calls share a short in-memory response cache to avoid duplicate chart requests during one refresh. Responses are not persisted by the app. News is filtered to articles whose `relatedTickers` contains the requested symbol. Yahoo does not provide a sentiment score in this response, so the app labels its deterministic local headline score rather than attributing that score to Yahoo.

No setup is required beyond normal Android internet access. `local.properties` remains optional and may contain only Android SDK settings or non-secret analyzer thresholds.

## Free local AI models

AI review is Ollama-only. The technical Yahoo-backed calculation remains authoritative and available when Ollama is stopped, unreachable, or returns invalid output. Both platforms send the same validated price, signal contributions, baseline classification/range, and current sourced headlines to the selected local model. Intraday prompts include only non-future articles published within 24 hours; daily prompts allow seven days. Every article includes its publication timestamp. The provider rechecks quote and market-data freshness when the model call starts, uses temperature zero, rejects malformed recommendations, and rejects price ranges outside 50%-150% of the validated current quote. A stale or insufficient analysis produces no model request.

Install Ollama from its official distribution, then explicitly download any free model supported by your hardware. A practical compact starting point is:

```powershell
ollama pull qwen3:4b
ollama list
```

The model is not bundled because model files are large, hardware-dependent third-party artifacts. Windows discovers all models currently returned by Ollama at `http://127.0.0.1:11434`. Android Settings accepts the Ollama URL and installed model name. To use a Windows-hosted model from Android, configure Ollama to listen on the trusted LAN interface, allow TCP 11434 only on a private network, and enter a URL such as `http://192.168.1.10:11434`. Never expose an unauthenticated Ollama endpoint to the public internet. Android permits cleartext traffic solely to support this user-configured local HTTP service; use a trusted LAN or local HTTPS reverse proxy.

## Run Windows

Use the matching installer under `releases/`, or build the native client:

```powershell
dotnet build .\windows\StockMovementAnalyzer.Windows\StockMovementAnalyzer.Windows.csproj -c Release
```

The self-contained setup installs under the current user's local application directory, creates Start Menu and desktop shortcuts, and needs no administrator access or separately installed .NET runtime. Watchlist, holdings, Ollama endpoint, and selected model are persisted separately under local application data. Updating replaces program binaries only; it does not delete settings. The Windows grid refreshes every 60 seconds, supports the same 10-60 minute and 1/5/10-day horizons as Android, uses the same color palette, and opens a complete evidence pane when a row is selected.

## Validate and redeploy every change

Run the repository deployment workflow after every application change:

```powershell
.\scripts\deploy-all.ps1
```

It runs the complete Android unit suite, Windows parity/current-evidence/update-persistence verification, builds the Android APK, publishes self-contained ARM64 and x64 Windows executables, refreshes all files under `releases/`, installs Windows for the current architecture, updates Android in place, and relaunches Windows. The Android package ID, signing identity, DataStore file, and `watchlist_json` key remain stable, so `installDebug` preserves the device watchlist and holdings. Windows settings remain at `%LOCALAPPDATA%\StockMovementAnalyzer\settings.json`, outside the replaceable program directory, and are saved atomically with a backup.

## Optional proxy adapter

The repository retains `proxy/` for users who separately choose Finnhub or Alpha Vantage. It is not used by the default app and is not required for free updates.

For local proxy development in PowerShell:

```powershell
Set-Location .\proxy
$env:MARKET_DATA_PROVIDER = 'finnhub'
$env:FINNHUB_API_KEY = '<enter in your terminal; never commit it>'
npm start
```

The optional proxy adapter requires HTTPS. Never put an API key in `local.properties` or the APK.

### Proxy contract

The app calls:

- `GET /v1/quote/{symbol}`
- `GET /v1/candles/{symbol}?interval=1&range=120` for one-minute candles
- `GET /v1/candles/{symbol}?interval=1440&range=129600` for daily candles
- `GET /v1/news/{symbol}` for newly retrieved, timestamped company news

Quote responses contain `symbol`, numeric `price`, ISO-8601 `timestamp`, and `provider`. Candle responses contain `provider`, ISO-8601 `retrievedAt`, `intervalMinutes`, and timestamped OHLC candles with optional volume. News responses contain the provider and retrieval time plus each article's headline, source, publication time, URL, score, and scoring method. Provider errors map to `404` unsupported symbol, `423` market closed/no recent data, `429` rate limit, and `5xx` provider unavailable. Responses use `Cache-Control: no-store`.

## Run Android

Prerequisites: Android Studio or JDK 17, Android SDK 35, and an Android 8.0 (API 26) or newer emulator/device.

```powershell
.\gradlew.bat testDebugUnitTest
.\gradlew.bat assembleDebug
.\gradlew.bat installDebug
```

The debug APK is generated at `app/build/outputs/apk/debug/app-debug.apk`.

If Yahoo Finance is unreachable, throttles the device, changes its response contract, or returns stale/insufficient data, the app displays **Live data unavailable** or `NEUTRAL / INSUFFICIENT DATA` and generates no directional prediction.

## Watchlist and holdings

Entering a valid symbol and tapping the add icon saves it to the device watchlist. Use the pencil icon on a stock card to log positive whole or fractional shares and an optional nonnegative average cost. Use the trash icon to remove one symbol, or the clear-watchlist icon in the top bar to remove all symbols. Destructive actions require confirmation.

Watchlist and holding records are stored only in Android DataStore for this app. They are not uploaded to the market-data proxy and do not affect prediction scoring. A live holding value is displayed only when a real validated quote is available; otherwise no value is estimated. Reinstalling with `adb install -r` preserves this data, while uninstalling or clearing app storage removes it.

## Prediction model

For the selected 10, 20, 30, 40, 50, or 60 minute horizon, the analyzer uses one-minute candles. The 1, 5, and 10-day projections use provider daily candles. In both cases, only supported values calculated from retrieved candles are used:

| Signal | Default weight | Calculation |
| --- | ---: | --- |
| Momentum | 0.30 | Price change across the selected horizon, capped before weighting |
| Trend | 0.20 | Five-period SMA compared with 12-period SMA |
| Volume | 0.10 | Latest volume versus its recent average, with price relative to VWAP |
| RSI | 0.15 | Fourteen-period RSI |
| MACD | 0.15 | Difference between 12- and 26-period EMAs |
| News sentiment | 0.10 | Average of fresh, sourced, ticker-specific article scores |

Unavailable indicators are excluded rather than substituted. At least 60% of signal weight must be supported. The score is normalized by available weight. A score at or above `0.2` is `UP / BUY`; at or below `-0.2` is `DOWN / SELL`; otherwise valid data produces `NEUTRAL / HOLD`. Confidence is the absolute normalized score times 100, capped to 0-100. Thresholds can be changed through the non-secret `local.properties` values.

Every validated result also includes a projected price range for the selected horizon. The analyzer calculates recent close-to-close return volatility from up to 21 candles, scales it by the square root of the horizon periods, caps the modeled span at 35%, and tilts the range center by half of the normalized signal score. This is a probabilistic interval based on recent behavior, not a guaranteed target, limit-order price, or promise that the market will remain inside the range. Invalid, stale, or insufficient data produces `UNAVAILABLE` with no price range.

Each refresh independently requests quote, candle, and news data. Intraday projections use articles published within 24 hours; daily projections use articles published within seven days. Stale, future-dated, source-less, headline-less, ticker-unrelated, or provider-mismatched items are excluded. Yahoo Finance does not return sentiment in the public search response, so the app applies a small deterministic positive/negative headline lexicon and labels every score `Deterministic headline lexicon`; it does not claim those scores came from Yahoo.

## Non-hallucination checks

Before a directional result is allowed, the app verifies:

- provider identity and a timestamped quote are present;
- quote and candle timestamps are not future-dated or older than 15 minutes for intraday analysis or five calendar days for daily analysis;
- quote and candle provider identities match;
- enough candles exist for the selected horizon;
- candle interval and numeric values are valid;
- at least 60% of weighted signals can be calculated from retrieved values.

If any required check fails, confidence is zero, direction is `NEUTRAL / INSUFFICIENT DATA`, recommendation and price range are `UNAVAILABLE`, and the reason is displayed. Provider/network/configuration failures do not create an `AnalysisResult`; the UI displays **Live data unavailable** and `Not calculated` instead. The detail view exposes the predictive action and range, provider, pull timestamp, latest source timestamp, age, candle interval, quote timestamp, indicator values, each signal weight/contribution, missing inputs, confidence calculation, and final reason.

Run both test suites to verify these behaviors:

```powershell
.\gradlew.bat testDebugUnitTest
Push-Location .\proxy
npm test
Pop-Location
```

## Troubleshooting

- **Rate limit exceeded:** wait for Yahoo Finance to accept requests again; the app does not bypass provider throttling.
- **No internet/provider unavailable:** verify Android connectivity and Yahoo Finance availability.
- **Market closed/stale data:** no directional result is generated after the 15-minute freshness limit.
- **Unsupported symbol:** use an exchange symbol accepted by the configured provider.
- **Insufficient candles:** wait for enough current one-minute candles or choose a shorter horizon.

## Known limitations

- The deterministic baseline and optional local language-model review cannot know future events or guarantee market movement.
- `BUY`, `SELL`, and `HOLD` are model classifications, not personalized investment advice or trade instructions. The range can be exceeded, especially around gaps, earnings, and breaking news.
- Yahoo Finance public endpoints are free and keyless but are unofficial for this application use and can change, throttle, delay, or become unavailable without notice.
- Day projections use trading-session candles, so 5-day and 10-day labels refer to five and ten observed sessions rather than guaranteed calendar-day outcomes.
- Alpha Vantage compact intraday output and Finnhub candle availability may limit coverage.
- Headline sentiment is a limited contextual signal and does not understand sarcasm, nuance, article bodies, or future events.
- Live refresh is enabled by default every 60 seconds while this app screen and ViewModel remain active; there is no background worker.