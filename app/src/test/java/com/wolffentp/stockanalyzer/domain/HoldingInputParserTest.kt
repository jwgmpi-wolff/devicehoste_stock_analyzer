package com.wolffentp.stockanalyzer.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HoldingInputParserTest {
    @Test
    fun acceptsFractionalSharesAndOptionalAverageCost() {
        assertEquals(HoldingInput(0.125, 310.50), HoldingInputParser.parse("0.125", "310.50"))
        assertEquals(HoldingInput(3.0, null), HoldingInputParser.parse("3", ""))
    }

    @Test
    fun rejectsValuesThatCannotRepresentARealHolding() {
        assertNull(HoldingInputParser.parse("0", "100"))
        assertNull(HoldingInputParser.parse("-1", "100"))
        assertNull(HoldingInputParser.parse("NaN", "100"))
        assertNull(HoldingInputParser.parse("2", "-1"))
        assertNull(HoldingInputParser.parse("2", "not-a-number"))
    }
}