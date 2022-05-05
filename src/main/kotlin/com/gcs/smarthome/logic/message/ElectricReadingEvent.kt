package com.gcs.smarthome.logic.message

import com.gcs.smarthome.data.model.DeviceType
import java.time.LocalDateTime

data class ElectricReadingEvent(
    val deviceType: DeviceType,
    val alias: String,
    val value: Double,
    val deltaSinceLastReading: Double,
    val timestamp: LocalDateTime)