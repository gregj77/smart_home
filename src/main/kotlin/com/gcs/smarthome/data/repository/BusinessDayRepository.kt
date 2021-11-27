package com.gcs.smarthome.data.repository

import com.gcs.smarthome.data.model.BusinessDay
import com.gcs.smarthome.data.model.DeviceReadingDailyReport
import com.gcs.smarthome.data.model.DeviceType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.LocalDate
import java.util.*

@Repository
interface BusinessDayRepository : JpaRepository<BusinessDay, Short> {
    fun findFirstByReferenceEquals(now: LocalDate = LocalDate.now()) : Optional<BusinessDay>

    @Query("SELECT (MAX(value) - MIN(value)) AS VALUE, device_type as deviceType, bd.reference AS date " +
            "FROM device_reading dr JOIN business_day bd ON dr.business_day_id = bd.id " +
            "WHERE bd.reference BETWEEN :start AND :end " +
            "AND dr.device_type IN (:types) " +
            "GROUP BY bd.reference, dr.device_type " +
            "ORDER BY bd.reference ASC", nativeQuery = true)
    fun loadDeviceDailyReport(
        @Param("types") types: Collection<Int>,
        @Param("start") start: LocalDate = LocalDate.now().minusDays(31),
        @Param("end") end: LocalDate = LocalDate.now().minusDays(1)): List<DeviceReadingDailyReport>
}