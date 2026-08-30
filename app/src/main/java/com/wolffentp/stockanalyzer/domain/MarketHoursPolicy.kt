package com.wolffentp.stockanalyzer.domain

import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId

object MarketHoursPolicy {
    private val easternTime = ZoneId.of("America/New_York")
    private const val OPEN_MINUTES = 9 * 60 + 30
    private const val CLOSE_MINUTES = 16 * 60
    private const val OFF_HOURS_INTRADAY_FRESHNESS = 18L * 60L
    private const val WEEKEND_INTRADAY_FRESHNESS = 72L * 60L

    fun effectiveFreshnessMinutes(horizon: Horizon, now: Instant): Long {
        if (horizon.isDaily) return horizon.freshnessMinutes
        val easternNow = now.atZone(easternTime)
        if (easternNow.dayOfWeek == DayOfWeek.SATURDAY || easternNow.dayOfWeek == DayOfWeek.SUNDAY) {
            return WEEKEND_INTRADAY_FRESHNESS
        }
        val minuteOfDay = easternNow.hour * 60 + easternNow.minute
        return if (minuteOfDay in OPEN_MINUTES until CLOSE_MINUTES) {
            horizon.freshnessMinutes
        } else {
            OFF_HOURS_INTRADAY_FRESHNESS
        }
    }
}
