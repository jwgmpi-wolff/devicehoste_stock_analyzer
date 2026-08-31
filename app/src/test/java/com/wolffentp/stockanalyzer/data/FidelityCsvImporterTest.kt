package com.wolffentp.stockanalyzer.data

import org.junit.Assert.assertEquals
import org.junit.Test

class FidelityCsvImporterTest {
    @Test
    fun `aggregates positions and total basis across accounts`() {
        val result = FidelityCsvImporter.import(
            """
            Account number,Account name,Symbol,Description,Quantity,Last price,Current value,Cost basis total,Average cost basis,Type
            X1,Individual,MSFT,Microsoft,10,$500,$5000,$3000,$300,Margin
            X2,IRA,MSFT,Microsoft,2,$500,$1000,$800,$400,Cash
            X2,IRA,NVDA,Nvidia,3,$200,$600,$450,$150,Cash
            X2,IRA,SPAXX**,Money market,,,,,,Cash
            """.trimIndent(),
        )

        assertEquals(3, result.positionCount)
        assertEquals(listOf("MSFT", "NVDA"), result.holdings.map { it.symbol })
        val microsoft = result.holdings.first()
        assertEquals(12.0, microsoft.quantity, 0.000001)
        assertEquals(316.666666, microsoft.averageCost!!, 0.000001)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects a non Fidelity export`() {
        FidelityCsvImporter.import("Timestamp,Transaction Type,Asset\n2026-01-01,Buy,BTC")
    }
}