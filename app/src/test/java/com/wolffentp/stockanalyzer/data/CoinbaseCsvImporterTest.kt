package com.wolffentp.stockanalyzer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CoinbaseCsvImporterTest {
    @Test
    fun `estimates weighted average after purchases fees and partial sale`() {
        val result = CoinbaseCsvImporter.import(
            """
            Transactions
            User,Example User,example-id
            ID,Timestamp,Transaction Type,Asset,Quantity Transacted,Price Currency,Price at Transaction,Subtotal,Total (inclusive of fees and/or spread),Fees and/or Spread,Notes
            1,2024-01-01T00:00:00Z,Buy,BTC,1,USD,10000,10000,10100,100,First buy
            2,2024-02-01T00:00:00Z,Buy,BTC,1,USD,20000,20000,20200,200,Second buy
            3,2024-03-01T00:00:00Z,Sell,BTC,0.5,USD,30000,15000,14900,100,Partial sale
            """.trimIndent(),
        )

        assertEquals(3, result.transactionCount)
        assertEquals(1, result.holdings.size)
        assertEquals("BTC-USD", result.holdings.single().symbol)
        assertEquals(1.5, result.holdings.single().quantity, 0.000001)
        assertEquals(15150.0, result.holdings.single().averageCost!!, 0.000001)
    }

    @Test
    fun `retains unknown basis for receives and removes fully disposed assets`() {
        val result = CoinbaseCsvImporter.import(
            """
            Timestamp,Transaction Type,Asset,Quantity Transacted,Spot Price at Transaction ($),Subtotal ($),Total (inclusive of fees) ($),Fees ($)
            2024-01-01T00:00:00Z,Receive,ETH,2,,,,
            2024-01-02T00:00:00Z,Buy,SOL,3,50,150,151,1
            2024-01-03T00:00:00Z,Send,SOL,3,,,,
            """.trimIndent(),
        )

        assertEquals(listOf("ETH-USD"), result.holdings.map { it.symbol })
        assertEquals(2.0, result.holdings.single().quantity, 0.000001)
        assertNull(result.holdings.single().averageCost)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects non Coinbase CSV`() {
        CoinbaseCsvImporter.import("Symbol,Shares,Cost\nMSFT,1,100")
    }
}