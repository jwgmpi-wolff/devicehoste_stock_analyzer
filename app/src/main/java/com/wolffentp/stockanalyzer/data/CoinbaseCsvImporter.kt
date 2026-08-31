package com.wolffentp.stockanalyzer.data

import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

data class CoinbaseHoldingEstimate(
    val symbol: String,
    val quantity: Double,
    val averageCost: Double?,
)

data class CoinbaseImportResult(
    val holdings: List<CoinbaseHoldingEstimate>,
    val transactionCount: Int,
)

const val COINBASE_CSV_SOURCE = "Coinbase CSV estimate"

object CoinbaseCsvImporter {
    fun import(csv: String): CoinbaseImportResult {
        val rows = parseRows(csv)
        require(rows.isNotEmpty()) { "The Coinbase CSV is empty." }
        val headerIndex = rows.indexOfFirst(::isCoinbaseHeader)
        require(headerIndex >= 0) { "The selected file is not a Coinbase transaction CSV." }
        val header = rows[headerIndex].map(::normalizeHeader)
        val columns = header.withIndex().associate { it.value to it.index }
        REQUIRED_COLUMNS.forEach { column ->
            require(columns.containsKey(column)) { "Missing Coinbase column: $column" }
        }

        val transactions = rows.drop(headerIndex + 1).mapNotNull { row ->
            val type = row.value(columns, TYPE).trim().lowercase()
            val asset = row.value(columns, ASSET).trim().uppercase()
            val quantity = row.value(columns, QUANTITY).numberOrNull()?.let(::abs)
            if (type.isBlank() || asset.isBlank() || quantity == null || quantity == 0.0) return@mapNotNull null
            Transaction(
                timestamp = parseTimestamp(row.value(columns, TIMESTAMP)),
                type = type,
                asset = asset,
                quantity = quantity,
                spotPrice = row.value(columns, SPOT_PRICE).numberOrNull(),
                subtotal = row.value(columns, SUBTOTAL).numberOrNull()?.let(::abs),
                total = row.value(columns, TOTAL).numberOrNull()?.let(::abs),
                fees = row.value(columns, FEES).numberOrNull()?.let(::abs) ?: 0.0,
            )
        }.sortedBy(Transaction::timestamp)

        val balances = linkedMapOf<String, Balance>()
        transactions.forEach { transaction ->
            val balance = balances.getOrPut(transaction.asset) { Balance() }
            when {
                transaction.type.isDisposal() -> balance.dispose(transaction.quantity)
                transaction.type.isAcquisition() -> balance.acquire(transaction.quantity, transaction.acquisitionCost())
            }
        }

        val holdings = balances.mapNotNull { (asset, balance) ->
            if (balance.quantity <= QUANTITY_EPSILON) null
            else CoinbaseHoldingEstimate(
                symbol = "$asset-USD",
                quantity = balance.quantity,
                averageCost = balance.knownCost.takeIf { balance.costBasisComplete }?.div(balance.quantity),
            )
        }
        return CoinbaseImportResult(holdings, transactions.size)
    }

    private fun parseRows(csv: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        val row = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var index = 0
        while (index < csv.length) {
            val character = csv[index]
            when {
                quoted && character == '"' && index + 1 < csv.length && csv[index + 1] == '"' -> {
                    field.append('"')
                    index++
                }
                character == '"' -> quoted = !quoted
                !quoted && character == ',' -> {
                    row += field.toString()
                    field.clear()
                }
                !quoted && (character == '\r' || character == '\n') -> {
                    if (character == '\r' && index + 1 < csv.length && csv[index + 1] == '\n') index++
                    row += field.toString()
                    field.clear()
                    if (row.any(String::isNotBlank)) rows += row.toList()
                    row.clear()
                }
                else -> field.append(character)
            }
            index++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) {
            row += field.toString()
            if (row.any(String::isNotBlank)) rows += row.toList()
        }
        require(!quoted) { "The Coinbase CSV contains an unterminated quoted value." }
        return rows
    }

    private fun normalizeHeader(value: String): String {
        val normalized = value.trim().removePrefix("\uFEFF").lowercase()
        return HEADER_ALIASES[normalized] ?: normalized
    }

    private fun isCoinbaseHeader(row: List<String>): Boolean {
        val columns = row.map(::normalizeHeader).toSet()
        return REQUIRED_COLUMNS.all(columns::contains)
    }

    private fun List<String>.value(columns: Map<String, Int>, name: String): String =
        columns[name]?.let { getOrNull(it) }.orEmpty()

    private fun String.numberOrNull(): Double? = trim()
        .replace("$", "")
        .replace(",", "")
        .takeIf(String::isNotBlank)
        ?.toDoubleOrNull()
        ?.takeIf(Double::isFinite)

    private fun parseTimestamp(value: String): Instant = runCatching { Instant.parse(value.trim()) }
        .recoverCatching { OffsetDateTime.parse(value.trim()).toInstant() }
        .recoverCatching { ZonedDateTime.parse(value.trim(), COINBASE_TIMESTAMP_FORMAT).toInstant() }
        .recoverCatching { LocalDateTime.parse(value.trim()).toInstant(ZoneOffset.UTC) }
        .getOrDefault(Instant.EPOCH)

    private fun String.isDisposal() = DISPOSAL_TYPES.any(::contains)
    private fun String.isAcquisition() = ACQUISITION_TYPES.any(::contains)

    private fun Transaction.acquisitionCost(): Double? = when {
        total != null && total > 0.0 -> total
        subtotal != null && subtotal > 0.0 -> subtotal + fees
        spotPrice != null && spotPrice > 0.0 -> quantity * spotPrice + fees
        else -> null
    }

    private data class Transaction(
        val timestamp: Instant,
        val type: String,
        val asset: String,
        val quantity: Double,
        val spotPrice: Double?,
        val subtotal: Double?,
        val total: Double?,
        val fees: Double,
    )

    private data class Balance(
        var quantity: Double = 0.0,
        var knownCost: Double = 0.0,
        var costBasisComplete: Boolean = true,
    ) {
        fun acquire(acquiredQuantity: Double, cost: Double?) {
            quantity += acquiredQuantity
            if (cost == null) costBasisComplete = false else knownCost += cost
        }

        fun dispose(disposedQuantity: Double) {
            if (quantity <= QUANTITY_EPSILON) return
            val removedQuantity = disposedQuantity.coerceAtMost(quantity)
            knownCost -= knownCost * (removedQuantity / quantity)
            quantity -= removedQuantity
            if (quantity <= QUANTITY_EPSILON) {
                quantity = 0.0
                knownCost = 0.0
                costBasisComplete = true
            }
        }
    }

    private const val TIMESTAMP = "timestamp"
    private const val TYPE = "transaction type"
    private const val ASSET = "asset"
    private const val QUANTITY = "quantity transacted"
    private const val SPOT_PRICE = "spot price at transaction"
    private const val SUBTOTAL = "subtotal"
    private const val TOTAL = "total (inclusive of fees and/or spread)"
    private const val FEES = "fees and/or spread"
    private const val QUANTITY_EPSILON = 0.000000001
    private val REQUIRED_COLUMNS = setOf(TYPE, ASSET, QUANTITY)
    private val ACQUISITION_TYPES = setOf("buy", "receive", "reward", "learning reward", "staking income")
    private val DISPOSAL_TYPES = setOf("sell", "send", "withdraw")
    private val COINBASE_TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z", Locale.US)
    private val HEADER_ALIASES = mapOf(
        "timestamp" to TIMESTAMP,
        "transaction type" to TYPE,
        "asset" to ASSET,
        "quantity transacted" to QUANTITY,
        "spot price at transaction" to SPOT_PRICE,
        "spot price at transaction ($)" to SPOT_PRICE,
        "price at transaction" to SPOT_PRICE,
        "price at transaction ($)" to SPOT_PRICE,
        "price at transaction (usd)" to SPOT_PRICE,
        "subtotal" to SUBTOTAL,
        "subtotal ($)" to SUBTOTAL,
        "total (inclusive of fees and/or spread)" to TOTAL,
        "total (inclusive of fees and/or spread) ($)" to TOTAL,
        "total (inclusive of fees)" to TOTAL,
        "fees and/or spread" to FEES,
        "fees and/or spread ($)" to FEES,
        "fees" to FEES,
    )
}