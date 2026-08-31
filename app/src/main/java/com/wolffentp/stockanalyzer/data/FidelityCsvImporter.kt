package com.wolffentp.stockanalyzer.data

import kotlin.math.abs

data class FidelityHoldingEstimate(val symbol: String, val quantity: Double, val averageCost: Double?)
data class FidelityImportResult(val holdings: List<FidelityHoldingEstimate>, val positionCount: Int)

const val FIDELITY_CSV_SOURCE = "Fidelity CSV"

object FidelityCsvImporter {
    fun import(csv: String): FidelityImportResult {
        val rows = parseRows(csv)
        require(rows.isNotEmpty()) { "The Fidelity CSV is empty." }
        val headerIndex = rows.indexOfFirst(::isFidelityHeader)
        require(headerIndex >= 0) { "The selected file is not a Fidelity positions CSV." }
        val columns = rows[headerIndex].map(::normalizeHeader).withIndex().associate { it.value to it.index }
        val positions = rows.drop(headerIndex + 1).mapNotNull { row ->
            val symbol = row.value(columns, SYMBOL).trim().uppercase()
            val quantity = row.value(columns, QUANTITY).numberOrNull()
            if (!SYMBOL_PATTERN.matches(symbol) || symbol.contains('*') || quantity == null || quantity <= 0.0) return@mapNotNull null
            Position(symbol, quantity, row.value(columns, COST_BASIS_TOTAL).numberOrNull()?.let(::abs))
        }
        val holdings = positions.groupBy(Position::symbol).map { (symbol, matching) ->
            val quantity = matching.sumOf(Position::quantity)
            val completeBasis = matching.all { it.costBasis != null }
            FidelityHoldingEstimate(
                symbol,
                quantity,
                matching.sumOf { it.costBasis ?: 0.0 }.takeIf { completeBasis }?.div(quantity),
            )
        }.sortedBy(FidelityHoldingEstimate::symbol)
        return FidelityImportResult(holdings, positions.size)
    }

    private fun isFidelityHeader(row: List<String>): Boolean {
        val columns = row.map(::normalizeHeader).toSet()
        return REQUIRED_COLUMNS.all(columns::contains)
    }

    private fun normalizeHeader(value: String) = value.trim().removePrefix("\uFEFF").lowercase()

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
                !quoted && character == ',' -> { row += field.toString(); field.clear() }
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
        require(!quoted) { "The Fidelity CSV contains an unterminated quoted value." }
        return rows
    }

    private fun List<String>.value(columns: Map<String, Int>, name: String): String =
        columns[name]?.let { getOrNull(it) }.orEmpty()

    private fun String.numberOrNull(): Double? = trim().replace("$", "").replace(",", "").replace("+", "")
        .takeIf(String::isNotBlank)?.toDoubleOrNull()?.takeIf(Double::isFinite)

    private data class Position(val symbol: String, val quantity: Double, val costBasis: Double?)

    private const val SYMBOL = "symbol"
    private const val QUANTITY = "quantity"
    private const val COST_BASIS_TOTAL = "cost basis total"
    private val REQUIRED_COLUMNS = setOf("account number", "account name", SYMBOL, QUANTITY, COST_BASIS_TOTAL)
    private val SYMBOL_PATTERN = Regex("^[A-Z0-9.-]{1,15}$")
}