package com.wolffentp.stockanalyzer.domain

data class HoldingInput(val quantity: Double, val averageCost: Double?)

object HoldingInputParser {
    fun parse(quantityText: String, averageCostText: String): HoldingInput? {
        val quantity = quantityText.toDoubleOrNull()
        val averageCost = averageCostText.takeIf { it.isNotBlank() }?.toDoubleOrNull()
        if (quantity == null || !quantity.isFinite() || quantity <= 0.0) return null
        if (averageCostText.isNotBlank() && (averageCost == null || !averageCost.isFinite() || averageCost < 0.0)) return null
        return HoldingInput(quantity, averageCost)
    }
}