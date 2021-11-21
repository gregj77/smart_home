package com.gcs.smarthome.data.repository

import com.gcs.smarthome.data.model.DeviceReading
import com.gcs.smarthome.data.model.DeviceReadingDailyReport
import com.gcs.smarthome.data.model.DeviceType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository
import java.util.*

@Repository
interface DeviceReadingRepository : JpaRepository<DeviceReading, Int> {
    fun findFirstByDeviceTypeOrderByIdDesc(deviceType: DeviceType): Optional<DeviceReading>
}