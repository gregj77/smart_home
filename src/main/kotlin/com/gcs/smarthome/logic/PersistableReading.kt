package com.gcs.smarthome.logic

import com.gcs.smarthome.data.model.DeviceType

internal interface PersistableReading {
    val deviceType: DeviceType
}