package com.wolffentp.stockanalyzer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.wolffentp.stockanalyzer.domain.AnalysisResult
import com.wolffentp.stockanalyzer.domain.Direction
import com.wolffentp.stockanalyzer.domain.Horizon
import com.wolffentp.stockanalyzer.domain.MarketSession
import com.wolffentp.stockanalyzer.domain.Recommendation
import com.wolffentp.stockanalyzer.data.ModelSettings
import com.wolffentp.stockanalyzer.data.StoredSessionSnapshot
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

private val AppColors = lightColorScheme(
    primary = Color(0xFF1565C0),
    secondary = Color(0xFF7B1FA2),
    background = Color(0xFFF7F7F4),
    surface = Color.White,
    onSurface = Color(0xFF19191D),
)

@Composable
fun StockAnalyzerApp(stockViewModel: StockViewModel = viewModel()) {
    val state by stockViewModel.state.collectAsStateWithLifecycle()
    MaterialTheme(colorScheme = AppColors, typography = MaterialTheme.typography.copy(
        headlineLarge = MaterialTheme.typography.headlineLarge.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold),
        titleLarge = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold),
    )) {
        val selected = state.selectedSymbol?.let { symbol -> state.rows.firstOrNull { it.symbol == symbol } }
        if (selected == null) {
            Dashboard(state, stockViewModel)
        } else {
            AnalysisDetail(selected, onBack = { stockViewModel.select(null) })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Dashboard(state: StockUiState, model: StockViewModel) {
    var symbolInput by remember { mutableStateOf("") }
    var inputError by remember { mutableStateOf(false) }
    var holdingEntry by remember { mutableStateOf<StockRowState?>(null) }
    var deleteSymbol by remember { mutableStateOf<String?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    var configureModel by remember { mutableStateOf(false) }
    var tableMode by rememberSaveable { mutableStateOf(false) }
    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Stock Movement Analyzer") },
            actions = {
                IconButton(onClick = { configureModel = true }) {
                    Icon(Icons.Default.Settings, contentDescription = "Configure free local model")
                }
                IconButton(onClick = { confirmClear = true }, enabled = state.rows.isNotEmpty()) {
                    Icon(Icons.Default.DeleteSweep, contentDescription = "Clear watchlist")
                }
                IconButton(onClick = model::refresh, enabled = !state.isRefreshing) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh live data")
                }
            },
        )
    }) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(padding).padding(horizontal = 16.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Probabilistic analysis", style = MaterialTheme.typography.headlineLarge, fontSize = 30.sp)
                Text("Fresh timestamped trends and sourced news. Not financial advice.", color = Color(0xFF55555D))
                Text("Time horizon", fontWeight = FontWeight.Bold)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(Horizon.entries) { horizon ->
                        FilterChip(
                            selected = state.horizon == horizon,
                            onClick = { model.setHorizon(horizon) },
                            label = { Text(horizon.label) },
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = symbolInput,
                        onValueChange = { symbolInput = it; inputError = false },
                        modifier = Modifier.weight(1f),
                        label = { Text("Stock symbol") },
                        singleLine = true,
                        isError = inputError,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = {
                            inputError = !model.addSymbol(symbolInput)
                            if (!inputError) symbolInput = ""
                        }),
                    )
                    Spacer(Modifier.width(8.dp))
                    IconButton(onClick = {
                        inputError = !model.addSymbol(symbolInput)
                        if (!inputError) symbolInput = ""
                    }) { Icon(Icons.Default.Add, contentDescription = "Save symbol to watchlist") }
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text(if (state.isRefreshing) "Refreshing live data" else "Live refresh", fontWeight = FontWeight.Bold)
                        Text(
                            "Every 60 seconds · Last ${state.lastRefreshAt.formatTimeOnly()}",
                            color = Color(0xFF66666D),
                            fontSize = 13.sp,
                        )
                    }
                    Switch(checked = state.autoRefresh, onCheckedChange = model::setAutoRefresh)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = !tableMode,
                        onClick = { tableMode = false },
                        label = { Text("Cards") },
                    )
                    FilterChip(
                        selected = tableMode,
                        onClick = { tableMode = true },
                        label = { Text("Table") },
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            if (state.rows.isNotEmpty()) {
                if (tableMode) {
                    StockTable(
                        rows = state.rows,
                        onSelect = model::select,
                        modifier = Modifier.fillMaxWidth().weight(1f),
                    )
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 150.dp),
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        gridItems(state.rows, key = { it.symbol }) { row ->
                            StockGridCard(
                                row = row,
                                horizon = state.horizon,
                                onClick = { model.select(row.symbol) },
                                onEditHolding = { holdingEntry = row },
                                onDelete = { deleteSymbol = row.symbol },
                            )
                        }
                    }
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text("Watchlist is empty", style = MaterialTheme.typography.titleLarge)
                    Text("Enter a stock symbol above to save it locally.", color = Color(0xFF606067))
                }
            }
        }
    }
    holdingEntry?.let { row ->
        HoldingDialog(
            row = row,
            onDismiss = { holdingEntry = null },
            onSave = { quantity, averageCost ->
                model.saveHolding(row.symbol, quantity, averageCost).also { saved ->
                    if (saved) holdingEntry = null
                }
            },
            onClear = {
                model.clearHolding(row.symbol)
                holdingEntry = null
            },
        )
    }
    deleteSymbol?.let { symbol ->
        ConfirmationDialog(
            title = "Delete $symbol?",
            message = "This removes the symbol and its logged holding from this device.",
            confirmLabel = "Delete",
            onConfirm = { model.deleteSymbol(symbol); deleteSymbol = null },
            onDismiss = { deleteSymbol = null },
        )
    }
    if (confirmClear) {
        ConfirmationDialog(
            title = "Clear watchlist?",
            message = "This removes every saved symbol and logged holding from this device.",
            confirmLabel = "Clear all",
            onConfirm = { model.clearWatchlist(); confirmClear = false },
            onDismiss = { confirmClear = false },
        )
    }
    if (configureModel) {
        ModelSettingsDialog(
            settings = state.modelSettings,
            endpointOptions = state.endpointOptions,
            modelOptions = state.modelOptions,
            status = state.modelStatus,
            onDismiss = { configureModel = false },
            onFindEndpoints = { model.refreshEndpointOptions() },
            onDiscoverModels = { model.refreshModelOptions() },
            onSave = { enabled, endpoint, modelName, finnhubApiKey ->
                model.saveModelSettings(enabled, endpoint, modelName, finnhubApiKey).also { saved ->
                    if (saved) configureModel = false
                }
            },
        )
    }
}

@Composable
private fun StockTable(rows: List<StockRowState>, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    val scroll = rememberScrollState()
    LazyColumn(modifier = modifier.horizontalScroll(scroll)) {
        item { StockTableHeader() }
        items(rows, key = { it.symbol }) { row ->
            StockTableRow(row = row, onClick = { onSelect(row.symbol) })
        }
    }
}

@Composable
private fun StockTableHeader() {
    Row(
        Modifier.background(Color(0xFFE7ECE9)).padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TableCell("Symbol", 76.dp, FontWeight.Bold)
        TableCell("Price", 92.dp, FontWeight.Bold)
        TableCell("Last overnight", 172.dp, FontWeight.Bold)
        TableCell("Pre-market", 172.dp, FontWeight.Bold)
        TableCell("Last after-hours", 172.dp, FontWeight.Bold)
        TableCell("Technical", 100.dp, FontWeight.Bold)
        TableCell("Projected range", 146.dp, FontWeight.Bold)
        TableCell("Confidence", 92.dp, FontWeight.Bold)
        TableCell("Holding", 138.dp, FontWeight.Bold)
    }
}

@Composable
private fun StockTableRow(row: StockRowState, onClick: () -> Unit) {
    val result = row.analysis
    val quote = result?.quote
    val technicalColor = Color(DirectionPalette.argb(result?.direction ?: Direction.NEUTRAL_INSUFFICIENT_DATA))
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TableCell(row.symbol, 76.dp, FontWeight.Bold)
        TableCell(
            quote?.price?.let { String.format(Locale.US, "$%,.2f", it) } ?: "Unavailable",
            92.dp,
            flashArgb = row.priceFlashArgb,
        )
        OvernightTableCell(quote, row.lastOvernight, 172.dp, row.overnightFlashArgb)
        TableCell(
            quote?.preMarketGridLine()?.removePrefix("Pre-market: ") ?: "Unavailable",
            172.dp,
            color = quote?.preMarketColor() ?: Color(0xFF6B6B72),
            flashArgb = row.preMarketFlashArgb,
        )
        TableCell(
            quote?.afterHoursGridLine()?.substringAfter(": ")
                ?: row.lastAfterHours?.gridLine("After-hours (last)")?.substringAfter(": ")
                ?: "Unavailable",
            172.dp,
            color = quote?.afterHoursColor() ?: Color(0xFF6B6B72),
            flashArgb = row.afterHoursFlashArgb,
        )
        TableCell(recommendationLabel(result?.recommendation), 100.dp, FontWeight.Bold, technicalColor)
        TableCell(priceRangeText(result), 146.dp, color = technicalColor)
        TableCell(result?.let { "${it.confidence}%" } ?: "Unavailable", 92.dp, FontWeight.Bold, technicalColor)
        TableCell(holdingText(row), 138.dp)
    }
}

@Composable
private fun TableCell(
    text: String,
    width: androidx.compose.ui.unit.Dp,
    fontWeight: FontWeight? = null,
    color: Color = Color(0xFF3F3F45),
    flashArgb: Long? = null,
) {
    val flashColor = flashArgb?.let { Color(it) } ?: Color.Transparent
    Text(
        text = text,
        modifier = Modifier.width(width).background(flashColor).padding(horizontal = 8.dp, vertical = 2.dp),
        color = color,
        fontWeight = fontWeight,
        fontSize = 12.sp,
        maxLines = 2,
    )
}

@Composable
private fun OvernightTableCell(
    quote: com.wolffentp.stockanalyzer.domain.Quote?,
    retained: StoredSessionSnapshot?,
    width: androidx.compose.ui.unit.Dp,
    flashArgb: Long?,
) {
    OvernightRefreshText(
        quote = quote,
        retained = retained,
        flashArgb = flashArgb,
        modifier = Modifier.width(width).padding(horizontal = 8.dp),
        label = false,
    )
}

@Composable
private fun OvernightRefreshText(
    quote: com.wolffentp.stockanalyzer.domain.Quote?,
    retained: StoredSessionSnapshot?,
    flashArgb: Long?,
    modifier: Modifier = Modifier,
    label: Boolean = true,
) {
    val flashColor = flashArgb?.let { Color(it) } ?: Color.Transparent
    Text(
        text = if (label) quote?.overnightGridLine() ?: retained?.gridLine("Overnight (last)") ?: "Overnight: unavailable"
        else quote?.overnightGridLine()?.substringAfter(": ")
            ?: retained?.gridLine("Overnight (last)")?.substringAfter(": ")
            ?: "Unavailable",
        modifier = modifier.background(flashColor).padding(vertical = 2.dp),
        color = quote?.overnightColor() ?: retained?.sessionColor() ?: Color(0xFF6B6B72),
        fontSize = 12.sp,
        maxLines = 2,
    )
}

@Composable
private fun StockGridCard(
    row: StockRowState,
    horizon: Horizon,
    onClick: () -> Unit,
    onEditHolding: () -> Unit,
    onDelete: () -> Unit,
) {
    val result = row.analysis
    val quote = result?.quote
    val color = Color(DirectionPalette.argb(result?.direction ?: Direction.NEUTRAL_INSUFFICIENT_DATA))
    Card(
        modifier = Modifier.fillMaxWidth().heightIn(min = 250.dp).clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(row.symbol, style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.weight(1f))
                Text(horizon.label, color = Color(0xFF606067), fontWeight = FontWeight.Bold)
            }
            RefreshingMarketText(
                text = quote?.price?.let { String.format(Locale.US, "$%,.2f", it) } ?: "Price unavailable",
                flashArgb = row.priceFlashArgb,
                fontSize = 21,
            )
            OvernightRefreshText(quote, row.lastOvernight, row.overnightFlashArgb)
            RefreshingMarketText(
                text = quote?.preMarketGridLine() ?: "Pre-market: unavailable",
                flashArgb = row.preMarketFlashArgb,
                color = quote?.preMarketColor() ?: Color(0xFF6B6B72),
            )
            RefreshingMarketText(
                text = quote?.afterHoursGridLine()
                    ?: row.lastAfterHours?.gridLine("After-hours (last)")
                    ?: "After-hours: unavailable",
                flashArgb = row.afterHoursFlashArgb,
                color = quote?.afterHoursColor() ?: row.lastAfterHours?.sessionColor() ?: Color(0xFF6B6B72),
            )
            Text("Predictive analysis: ${recommendationLabel(result?.recommendation)}", color = color, fontWeight = FontWeight.Bold)
            Text("Projected ${horizon.label} range: ${priceRangeText(result)}", color = Color(0xFF3F3F45), fontSize = 13.sp)
            Text(
                row.modelReview?.let { "Local ${it.model}: ${it.recommendation} · ${String.format(Locale.US, "$%,.2f-$%,.2f", it.low, it.high)}" }
                    ?: row.modelError?.let { "Local model unavailable; technical result retained" }
                    ?: "Local model review off",
                color = Color(0xFF606067),
                fontSize = 12.sp,
            )
            Text(directionLabel(result?.direction), color = color, fontWeight = FontWeight.Bold)
            Text(result?.let { "Confidence ${it.confidence}%" } ?: "Not calculated", color = color)
            Text(holdingText(row), color = Color(0xFF3F3F45), fontSize = 13.sp)
            Text("Source ${result?.lastDataTimestamp.formatTimestamp()}", color = Color(0xFF606067), fontSize = 12.sp)
            if (row.error != null) Text(row.error, color = color, fontSize = 13.sp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                IconButton(onClick = onEditHolding) {
                    Icon(Icons.Default.Edit, contentDescription = "Log ${row.symbol} holding")
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete ${row.symbol} from watchlist")
                }
            }
        }
    }
}

@Composable
private fun RefreshingMarketText(
    text: String,
    flashArgb: Long?,
    color: Color = Color(0xFF3F3F45),
    fontSize: Int = 12,
) {
    Text(
        text = text,
        modifier = Modifier.background(flashArgb?.let { Color(it) } ?: Color.Transparent).padding(vertical = 2.dp),
        color = color,
        fontSize = fontSize.sp,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelSettingsDialog(
    settings: ModelSettings,
    endpointOptions: List<String>,
    modelOptions: List<String>,
    status: String?,
    onDismiss: () -> Unit,
    onFindEndpoints: () -> Unit,
    onDiscoverModels: () -> Unit,
    onSave: (Boolean, String, String, String) -> Boolean,
) {
    var enabled by remember { mutableStateOf(settings.enabled) }
    var endpoint by remember { mutableStateOf(settings.endpoint) }
    var model by remember { mutableStateOf(settings.model) }
    var finnhubApiKey by remember { mutableStateOf(settings.finnhubApiKey) }
    var invalid by remember { mutableStateOf(false) }
    var endpointExpanded by remember { mutableStateOf(false) }
    var modelExpanded by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Free local model") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Use Ollama review", Modifier.weight(1f))
                    Switch(checked = enabled, onCheckedChange = { enabled = it; invalid = false })
                }
                ExposedDropdownMenuBox(
                    expanded = endpointExpanded,
                    onExpandedChange = { endpointExpanded = !endpointExpanded },
                ) {
                    OutlinedTextField(
                        value = endpoint,
                        onValueChange = { endpoint = it; invalid = false },
                        label = { Text("Ollama endpoint") },
                        placeholder = { Text("http://192.168.1.10:11434") },
                        singleLine = true,
                        isError = invalid,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = endpointExpanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor(),
                    )
                    ExposedDropdownMenu(
                        expanded = endpointExpanded,
                        onDismissRequest = { endpointExpanded = false },
                    ) {
                        endpointOptions.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option) },
                                onClick = {
                                    endpoint = option
                                    endpointExpanded = false
                                    invalid = false
                                },
                            )
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onFindEndpoints) { Text("Find endpoints") }
                }
                ExposedDropdownMenuBox(
                    expanded = modelExpanded,
                    onExpandedChange = { modelExpanded = !modelExpanded },
                ) {
                    OutlinedTextField(
                        value = model,
                        onValueChange = { model = it; invalid = false },
                        label = { Text("Installed model") },
                        placeholder = { Text("qwen3:4b") },
                        singleLine = true,
                        isError = invalid,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelExpanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor(),
                    )
                    ExposedDropdownMenu(
                        expanded = modelExpanded,
                        onDismissRequest = { modelExpanded = false },
                    ) {
                        modelOptions.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option) },
                                onClick = {
                                    model = option
                                    modelExpanded = false
                                    invalid = false
                                },
                            )
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDiscoverModels) { Text("Discover models") }
                }
                status?.takeIf { it.isNotBlank() }?.let {
                    Text(it, fontSize = 13.sp, color = Color(0xFF52625E))
                }
                OutlinedTextField(
                    value = finnhubApiKey,
                    onValueChange = { finnhubApiKey = it },
                    label = { Text("Finnhub API key (optional)") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("Finnhub requires your own key. Its quote API supplies regular quote data, but does not publish a separate overnight field.", fontSize = 13.sp)
                Text("Analysis stays on your devices. The validated technical result remains available when Ollama is offline.", fontSize = 13.sp)
            }
        },
        confirmButton = {
            TextButton(onClick = { invalid = !onSave(enabled, endpoint, model, finnhubApiKey) }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun HoldingDialog(
    row: StockRowState,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Boolean,
    onClear: () -> Unit,
) {
    var quantity by remember(row.symbol) { mutableStateOf(row.quantity?.displayNumber().orEmpty()) }
    var averageCost by remember(row.symbol) { mutableStateOf(row.averageCost?.displayNumber().orEmpty()) }
    var invalid by remember(row.symbol) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Log ${row.symbol} holding") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Saved locally on this device. Holdings do not affect movement analysis.")
                OutlinedTextField(
                    value = quantity,
                    onValueChange = { quantity = it; invalid = false },
                    label = { Text("Shares") },
                    singleLine = true,
                    isError = invalid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                OutlinedTextField(
                    value = averageCost,
                    onValueChange = { averageCost = it; invalid = false },
                    label = { Text("Average cost per share (optional)") },
                    singleLine = true,
                    isError = invalid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                if (invalid) Text("Enter positive shares and a nonnegative average cost.", color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(onClick = { invalid = !onSave(quantity, averageCost) }) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (row.quantity != null) TextButton(onClick = onClear) { Text("Clear holding") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

@Composable
private fun ConfirmationDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AnalysisDetail(row: StockRowState, onBack: () -> Unit) {
    Scaffold(topBar = {
        TopAppBar(
            title = { Text("${row.symbol} analysis") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        )
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (row.analysis == null) {
                item {
                    Text("Live data unavailable", style = MaterialTheme.typography.headlineLarge)
                    Text(row.error ?: "No timestamped provider data was returned. No prediction was generated.")
                    Text("Provider: Not available\nConfidence: Not calculated\nSignals: Not calculated")
                }
            } else {
                val result = row.analysis
                item { DetailHeader(result) }
                item { DetailSection("Holding", holdingText(row)) }
                item { DetailSection("Predictive action and range", recommendationText(result)) }
                item { DetailSection("Free local model review", modelReviewText(row)) }
                item { DetailSection("Source", sourceText(result)) }
                item { DetailSection("Indicators", indicatorText(result)) }
                item { DetailSection("Signal weights", signalText(result)) }
                item { DetailSection("News research", newsText(result)) }
                item { DetailSection("Gaps and limitations", result.warnings.ifEmpty { listOf("None reported") }.joinToString("\n") { "- $it" }) }
                item { DetailSection("Final reason", result.reason) }
                item { Spacer(Modifier.height(12.dp)) }
            }
        }
    }
}

private fun modelReviewText(row: StockRowState): String = row.modelReview?.let { review ->
    "Model: ${review.model}\nRecommendation: ${review.recommendation}\nProjected range: ${String.format(Locale.US, "$%,.2f - $%,.2f", review.low, review.high)}\nRationale: ${review.rationale}\nThis secondary local-model review does not replace the validated technical baseline."
} ?: row.modelError?.let { "$it. The validated technical baseline is retained." }
    ?: "Disabled. Configure an Ollama server and installed free model in Settings."

@Composable
private fun DetailHeader(result: AnalysisResult) {
    val color = Color(DirectionPalette.argb(result.direction))
    Column {
        Text(directionLabel(result.direction), style = MaterialTheme.typography.headlineLarge, color = color)
        Text("Confidence ${result.confidence}%", color = color, fontWeight = FontWeight.Bold)
        Text("Probabilistic ${result.horizon.label} projection. Not financial advice.")
    }
}

@Composable
private fun DetailSection(title: String, body: String) {
    Column(Modifier.fillMaxWidth()) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(4.dp))
        Text(body, color = Color(0xFF3F3F45))
    }
}

private fun sourceText(result: AnalysisResult) = buildString {
    appendLine("Provider: ${result.provider}")
    appendLine("Retrieved at: ${result.retrievedAt.formatTimestamp()}")
    appendLine("Latest source timestamp: ${result.lastDataTimestamp.formatTimestamp()}")
    appendLine("Source age: ${result.sourceAgeMinutes?.let { "$it minutes" } ?: "Unavailable"}")
    appendLine("Candle interval: ${result.candleIntervalMinutes} minute(s)")
    appendLine(result.quote?.overnightGridLine() ?: "Overnight: unavailable")
    appendLine("Pre/After market: ${result.quote?.extendedSessionSummary() ?: "Unavailable"}")
    append("Latest quote: ${result.quote?.let { String.format(Locale.US, "$%,.2f at %s", it.price, it.timestamp.formatTimestamp()) } ?: "Unsupported / unavailable"}")
}

private fun indicatorText(result: AnalysisResult): String {
    val values = result.indicators ?: return "Indicators were not calculated because live-data validation failed."
    fun Double?.display() = this?.let { String.format(Locale.US, "%.4f", it) } ?: "Unsupported / unavailable"
    return "Momentum: ${values.momentumPercent.display()}%\nShort moving average: ${values.shortMovingAverage.display()}\nLong moving average: ${values.longMovingAverage.display()}\nRelative volume: ${values.relativeVolume.display()}\nRSI: ${values.rsi.display()}\nMACD: ${values.macd.display()}\nVWAP: ${values.vwap.display()}\nFresh news sentiment average: ${values.sentimentAverage.display()}"
}

private fun signalText(result: AnalysisResult) = buildString {
    result.signals.forEach { signal ->
        appendLine("${signal.name}: value ${signal.value?.let { String.format(Locale.US, "%.3f", it) } ?: "unused"} x weight ${String.format(Locale.US, "%.2f", signal.weight)} = ${signal.contribution?.let { String.format(Locale.US, "%.3f", it) } ?: "unused"}")
    }
    append("Confidence: absolute normalized weighted score x 100 = ${result.confidence}%")
}

private fun newsText(result: AnalysisResult): String {
    val news = result.news ?: return "No timestamped news was returned for this refresh."
    if (news.items.isEmpty()) return "${news.provider} returned no sourced articles at ${news.retrievedAt.formatTimestamp()}."
    return buildString {
        appendLine("Provider: ${news.provider} · Retrieved ${news.retrievedAt.formatTimestamp()}")
        news.items.forEach { article ->
            appendLine()
            appendLine(article.headline)
            append("${article.source} · ${article.publishedAt.formatTimestamp()} · Score ${String.format(Locale.US, "%+.3f", article.score)} · ${article.scoringMethod}")
        }
    }
}

private fun directionLabel(direction: Direction?) = when (direction) {
    Direction.UP -> "UP (probabilistic)"
    Direction.DOWN -> "DOWN (probabilistic)"
    Direction.NEUTRAL -> "NEUTRAL (probabilistic)"
    Direction.NEUTRAL_INSUFFICIENT_DATA -> "NEUTRAL / INSUFFICIENT DATA"
    null -> "LIVE DATA UNAVAILABLE"
}

private fun recommendationLabel(recommendation: Recommendation?) = when (recommendation) {
    Recommendation.BUY -> "BUY"
    Recommendation.SELL -> "SELL"
    Recommendation.HOLD -> "HOLD"
    Recommendation.UNAVAILABLE, null -> "UNAVAILABLE"
}

private fun priceRangeText(result: AnalysisResult?): String = result?.projectedPriceRange?.let { range ->
    String.format(Locale.US, "$%,.2f - $%,.2f", range.low, range.high)
} ?: "Unavailable"

private fun recommendationText(result: AnalysisResult) = buildString {
    appendLine("Analysis: ${recommendationLabel(result.recommendation)}")
    appendLine("Projected ${result.horizon.label} price range: ${priceRangeText(result)}")
    append("The action follows the validated weighted signal score. The range estimates recent realized movement over the selected horizon and is not a guaranteed target or order price.")
}

private fun holdingText(row: StockRowState): String {
    val quantity = row.quantity ?: return "No holding logged"
    val cost = row.averageCost?.let { " @ ${String.format(Locale.US, "$%,.2f", it)} avg" }.orEmpty()
    val liveValue = row.analysis?.quote?.price?.let { price ->
        " · Live value ${String.format(Locale.US, "$%,.2f", quantity * price)}"
    }.orEmpty()
    return "${quantity.displayNumber()} shares$cost$liveValue"
}

private fun Double.displayNumber(): String = String.format(Locale.US, "%.4f", this).trimEnd('0').trimEnd('.')

private fun Instant?.formatTimestamp(): String = this?.atZone(ZoneId.systemDefault())?.format(TIMESTAMP_FORMAT) ?: "Not available"
private fun Instant?.formatTimeOnly(): String = this?.atZone(ZoneId.systemDefault())?.format(TIME_FORMAT) ?: "not yet"

private fun com.wolffentp.stockanalyzer.domain.Quote.extendedSessionSummary(): String {
    val pre = sessionText("Pre", preMarketPrice, preMarketChangePercent)
    val afterLabel = if (marketSession == MarketSession.AFTER_HOURS && !afterHoursIsPrior) "After" else "After (last)"
    val after = sessionText(afterLabel, afterHoursPrice, afterHoursChangePercent)
    return when {
        pre != null && after != null -> "$pre | $after"
        pre != null -> pre
        after != null -> after
        else -> "Unavailable"
    }
}

private fun com.wolffentp.stockanalyzer.domain.Quote.preMarketGridLine(): String =
    "${if (marketSession == MarketSession.PRE_MARKET) "Pre-market" else "Pre-market (prior)"}: ${gridSessionText(preMarketPrice, preMarketChange, preMarketChangePercent)}"

private fun com.wolffentp.stockanalyzer.domain.Quote.overnightGridLine(): String = when {
    overnightPrice != null -> "${if (marketSession == MarketSession.OVERNIGHT && !overnightIsPrior) "Overnight" else "Overnight (last)"}: ${gridSessionText(overnightPrice, overnightChange, overnightChangePercent)}"
    marketSession == MarketSession.OVERNIGHT -> "Overnight: not published by Yahoo"
    else -> "Overnight: unavailable"
}

private fun com.wolffentp.stockanalyzer.domain.Quote.overnightColor(): Color = when {
    (overnightChange ?: overnightPrice?.minus(price) ?: 0.0) > 0.00005 -> Color(0xFF16803C)
    (overnightChange ?: overnightPrice?.minus(price) ?: 0.0) < -0.00005 -> Color(0xFFC62828)
    else -> Color(0xFF6B6B72)
}

private fun com.wolffentp.stockanalyzer.domain.Quote.preMarketColor(): Color = when {
    (preMarketChange ?: preMarketPrice?.minus(price) ?: 0.0) > 0.00005 -> Color(0xFFC28C00)
    (preMarketChange ?: preMarketPrice?.minus(price) ?: 0.0) < -0.00005 -> Color(0xFFC28C00)
    else -> Color(0xFF6B6B72)
}

private fun com.wolffentp.stockanalyzer.domain.Quote.afterHoursColor(): Color = when {
    (afterHoursChange ?: afterHoursPrice?.minus(price) ?: 0.0) > 0.00005 -> Color(0xFFC28C00)
    (afterHoursChange ?: afterHoursPrice?.minus(price) ?: 0.0) < -0.00005 -> Color(0xFFC28C00)
    else -> Color(0xFF6B6B72)
}

private fun com.wolffentp.stockanalyzer.domain.Quote.afterHoursGridLine(): String =
    "${if (marketSession == MarketSession.AFTER_HOURS && !afterHoursIsPrior) "After-hours" else "After-hours (last)"}: ${gridSessionText(afterHoursPrice, afterHoursChange, afterHoursChangePercent)}"

private fun com.wolffentp.stockanalyzer.domain.Quote.gridSessionText(sessionPrice: Double?, change: Double?, percent: Double?): String {
    if (sessionPrice == null && change == null && percent == null) return "unavailable"
    val resolvedChange = change ?: sessionPrice?.let { it - price }
    val resolvedPrice = sessionPrice ?: resolvedChange?.let { price + it }
    val priceText = resolvedPrice?.let { String.format(Locale.US, "$%,.2f", it) } ?: "-"
    val deltaText = resolvedChange?.let { String.format(Locale.US, "%+.2f", it).replace("+", "+$").replace("-", "-$") } ?: "-"
    val percentText = percent?.let { formatSignedPercent(it) } ?: "-"
    return "$priceText ($deltaText, $percentText)"
}

private fun StoredSessionSnapshot.gridLine(label: String): String {
    val priceText = String.format(Locale.US, "$%,.2f", price)
    val deltaText = String.format(Locale.US, "%+.2f", change).replace("+", "+$").replace("-", "-$")
    val percentText = percent?.let { formatSignedPercent(it) } ?: "-"
    return "$label: $priceText ($deltaText, $percentText)"
}

private fun StoredSessionSnapshot.sessionColor(): Color = when {
    change > 0.00005 -> Color(0xFF16803C)
    change < -0.00005 -> Color(0xFFC62828)
    else -> Color(0xFF6B6B72)
}

private fun sessionText(label: String, price: Double?, changePercent: Double?): String? {
    if (price == null && changePercent == null) return null
    val priceText = price?.let { String.format(Locale.US, "$%,.2f", it) } ?: "-"
    val percentText = changePercent?.let { formatSignedPercent(it) } ?: "-"
    return "$label $priceText ($percentText)"
}

private fun formatSignedPercent(value: Double): String {
    val normalized = if (abs(value) < 0.00005) 0.0 else value
    return String.format(Locale.US, "%+.2f%%", normalized)
}

private val TIMESTAMP_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z")
private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")