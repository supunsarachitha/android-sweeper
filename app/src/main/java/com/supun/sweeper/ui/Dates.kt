package com.supun.sweeper.ui

import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The date style used everywhere on screen: "8 Nov 2026" (day, short month, year in the device language). */
private const val DAY_PATTERN = "d MMM yyyy"

/** "8 Nov 2026" for a time in milliseconds, in the phone's time zone. */
internal fun formatDay(
    millis: Long,
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
): String = DateTimeFormatter.ofPattern(DAY_PATTERN, locale).format(Instant.ofEpochMilli(millis).atZone(zone))

/** "8 Nov 2026" for the date a malware list was made (seconds since 1970, a UTC date); "unknown date" if invalid. */
internal fun formatListDate(createdAtSeconds: Long, locale: Locale = Locale.getDefault()): String =
    try {
        DateTimeFormatter.ofPattern(DAY_PATTERN, locale).format(Instant.ofEpochSecond(createdAtSeconds).atZone(ZoneOffset.UTC))
    } catch (e: RuntimeException) {
        "unknown date"
    }
