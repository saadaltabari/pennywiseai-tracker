package com.pennywiseai.tracker.data.manager

import com.pennywiseai.tracker.core.Constants
import com.pennywiseai.tracker.core.TimeConstants
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

data class SmsScanParamsInput(
    val forceResync: Boolean = false,
    val lastScanTimestamp: Long? = null,
    val scanMonths: Int,
    val scanAllTime: Boolean,
    val scanUseCustomDate: Boolean,
    val scanCustomDateMillis: Long? = null,
    val scanUseDays: Boolean = false,
    val scanDays: Int = 1,
    val lastScanPeriod: Int? = null,
    val nowMillis: Long,
)

data class SmsScanParams(
    val scanStartTime: Long,
    val needsFullScan: Boolean,
)

object SmsScanParamsCalculator {

    val SUPPORTED_SCAN_DAY_PERIODS = listOf(1, 2, 7)

    private const val LEGACY_SCAN_PERIOD_3_DAYS = -5
    private const val LEGACY_SCAN_PERIOD_2_WEEKS = -7

    fun compute(input: SmsScanParamsInput, zoneId: ZoneId = ZoneId.systemDefault()): SmsScanParams {
        val lastScanTimestamp = input.lastScanTimestamp ?: 0L
        val lastScanPeriod = input.lastScanPeriod ?: 0
        val now = input.nowMillis

        val scanAllTimeToggled = input.scanAllTime &&
            lastScanPeriod != Constants.SmsProcessing.SCAN_PERIOD_ALL_TIME
        val scanAllTimeToggledOff = !input.scanAllTime &&
            lastScanPeriod == Constants.SmsProcessing.SCAN_PERIOD_ALL_TIME
        val customDateToggled = input.scanUseCustomDate &&
            lastScanPeriod != Constants.SmsProcessing.SCAN_PERIOD_CUSTOM_DATE
        val customDateToggledOff = !input.scanUseCustomDate &&
            lastScanPeriod == Constants.SmsProcessing.SCAN_PERIOD_CUSTOM_DATE
        val daysToggled = input.scanUseDays && !isDayScanPeriodSentinel(lastScanPeriod)
        val daysToggledOff = !input.scanUseDays && isDayScanPeriodSentinel(lastScanPeriod)
        val dayPeriodIncreased = input.scanUseDays &&
            isDayScanPeriodSentinel(lastScanPeriod) &&
            isDayPeriodIncreased(lastScanPeriod, input.scanDays)
        val monthPeriodIncreased = !input.scanUseDays && !input.scanUseCustomDate && !input.scanAllTime &&
            lastScanPeriod >= 0 && input.scanMonths > lastScanPeriod
        val needsFullScan = input.forceResync || lastScanTimestamp == 0L ||
            monthPeriodIncreased || dayPeriodIncreased ||
            scanAllTimeToggled || scanAllTimeToggledOff ||
            customDateToggled || customDateToggledOff ||
            daysToggled || daysToggledOff

        val periodLimit = scanPeriodStartTime(
            scanAllTime = input.scanAllTime,
            scanUseCustomDate = input.scanUseCustomDate,
            scanCustomDateMillis = input.scanCustomDateMillis,
            scanUseDays = input.scanUseDays,
            scanDays = input.scanDays,
            scanMonths = input.scanMonths,
            nowMillis = now,
            zoneId = zoneId,
        )

        val scanStartTime = if (needsFullScan) {
            periodLimit
        } else {
            val threeDaysAgo = now - TimeConstants.MILLIS_PER_3_DAYS
            maxOf(minOf(lastScanTimestamp, threeDaysAgo), periodLimit)
        }

        return SmsScanParams(scanStartTime = scanStartTime, needsFullScan = needsFullScan)
    }

    fun resolveLastScanPeriod(
        scanAllTime: Boolean,
        scanUseCustomDate: Boolean,
        scanUseDays: Boolean,
        scanDays: Int,
        scanMonths: Int,
    ): Int = when {
        scanAllTime -> Constants.SmsProcessing.SCAN_PERIOD_ALL_TIME
        scanUseCustomDate -> Constants.SmsProcessing.SCAN_PERIOD_CUSTOM_DATE
        scanUseDays -> daysToScanPeriodSentinel(scanDays)
        else -> scanMonths
    }

    fun scanPeriodStartTime(
        scanAllTime: Boolean,
        scanUseCustomDate: Boolean,
        scanCustomDateMillis: Long?,
        scanUseDays: Boolean,
        scanDays: Int,
        scanMonths: Int,
        nowMillis: Long,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): Long {
        if (scanAllTime) return 0L

        if (scanUseCustomDate) {
            return scanCustomDateMillis
                ?: monthBasedScanStartTime(scanMonths, nowMillis, zoneId)
        }

        if (scanUseDays) {
            return dayBasedScanStartTime(scanDays, nowMillis, zoneId)
        }

        return monthBasedScanStartTime(scanMonths, nowMillis, zoneId)
    }

    fun monthBasedScanStartTime(
        scanMonths: Int,
        nowMillis: Long,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): Long {
        return Instant.ofEpochMilli(nowMillis)
            .atZone(zoneId)
            .toLocalDate()
            .minusMonths(scanMonths.toLong())
            .atStartOfDay(zoneId)
            .toInstant()
            .toEpochMilli()
    }

    fun dayBasedScanStartTime(
        scanDays: Int,
        nowMillis: Long,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): Long {
        return Instant.ofEpochMilli(nowMillis)
            .atZone(zoneId)
            .toLocalDate()
            .minusDays(scanDays.toLong())
            .atStartOfDay(zoneId)
            .toInstant()
            .toEpochMilli()
    }

    fun daysToScanPeriodSentinel(days: Int): Int = when (days) {
        1 -> Constants.SmsProcessing.SCAN_PERIOD_1_DAY
        2 -> Constants.SmsProcessing.SCAN_PERIOD_2_DAYS
        7 -> Constants.SmsProcessing.SCAN_PERIOD_1_WEEK
        3 -> LEGACY_SCAN_PERIOD_3_DAYS
        14 -> LEGACY_SCAN_PERIOD_2_WEEKS
        else -> Constants.SmsProcessing.SCAN_PERIOD_1_DAY
    }

    fun scanPeriodSentinelToDays(sentinel: Int): Int? = when (sentinel) {
        Constants.SmsProcessing.SCAN_PERIOD_1_DAY -> 1
        Constants.SmsProcessing.SCAN_PERIOD_2_DAYS -> 2
        Constants.SmsProcessing.SCAN_PERIOD_1_WEEK -> 7
        LEGACY_SCAN_PERIOD_3_DAYS -> 3
        LEGACY_SCAN_PERIOD_2_WEEKS -> 14
        else -> null
    }

    fun isDayScanPeriodSentinel(sentinel: Int): Boolean =
        scanPeriodSentinelToDays(sentinel) != null

    private fun isDayPeriodIncreased(lastPeriod: Int, newDays: Int): Boolean {
        val lastDays = scanPeriodSentinelToDays(lastPeriod) ?: return true
        return newDays > lastDays
    }

    fun normalizePickerDateToLocalStartOfDay(
        pickerMillis: Long,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): Long {
        return Instant.ofEpochMilli(pickerMillis)
            .atZone(ZoneOffset.UTC)
            .toLocalDate()
            .atStartOfDay(zoneId)
            .toInstant()
            .toEpochMilli()
    }
}
