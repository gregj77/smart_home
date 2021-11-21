package com.gcs.smarthome.data.model

import java.math.BigDecimal
import java.time.LocalDate

interface DeviceReadingDailyReport {
    val value: BigDecimal
    val deviceType: DeviceType
    val date: LocalDate
}