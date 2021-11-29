package com.gcs.smarthome.data.model

import java.math.BigDecimal
import java.time.LocalDate

interface DeviceReadingMonthlyReport {
    val value: BigDecimal
    val deviceType: DeviceType
    val year: Int
    val month: Int
}