package com.gcs.smarthome.data.repository

import com.gcs.smarthome.data.model.DeviceReading
import com.gcs.smarthome.data.model.DeviceReadingDailyReport
import com.gcs.smarthome.data.model.DeviceType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.util.*

@Repository
interface DeviceReadingRepository : JpaRepository<DeviceReading, Int> {
    fun findFirstByDeviceTypeOrderByIdDesc(deviceType: DeviceType): Optional<DeviceReading>

    @Query("SELECT (MAX(value) - MIN(value)) AS delta FROM DeviceReading WHERE deviceType = :deviceType AND businessDayId = :dayId")
    fun getDailyReadingDelta(@Param("deviceType") deviceType: DeviceType, @Param("dayId") businessDayId: Short): BigDecimal?
}