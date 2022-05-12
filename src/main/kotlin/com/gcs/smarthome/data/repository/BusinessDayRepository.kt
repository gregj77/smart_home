package com.gcs.smarthome.data.repository

import com.gcs.smarthome.data.model.BusinessDay
import com.gcs.smarthome.data.model.DeviceReadingDailyReport
import com.gcs.smarthome.data.model.DeviceReadingMonthlyReport
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

    @Query("SELECT MAX(value) AS VALUE, (MAX(value) - MIN(value)) AS dailyDelta, device_type as deviceType, bd.reference AS date " +
            "FROM device_reading dr JOIN business_day bd ON dr.business_day_id = bd.id " +
            "WHERE bd.reference BETWEEN :start AND :end " +
            "AND dr.device_type IN (:types) " +
            "GROUP BY bd.reference, dr.device_type " +
            "ORDER BY bd.reference ASC", nativeQuery = true)
    fun loadDeviceDailyReport(
        @Param("types") types: Collection<Int>,
        @Param("start") start: LocalDate = LocalDate.now().minusDays(31),
        @Param("end") end: LocalDate = LocalDate.now().minusDays(1)): List<DeviceReadingDailyReport>

    @Query("WITH reportRange (firstDay, lastDay, year_and_month) AS\n" +
            "         (\n" +
            "             SELECT rs.id as firstDay, re.id as lastDay, rng.year_and_month\n" +
            "             FROM business_day rs,\n" +
            "                  business_day re,\n" +
            "                  (\n" +
            "                      SELECT MIN(reference) AS range_start, MAX(reference) AS range_end, year_and_month\n" +
            "                      FROM business_day\n" +
            "                      GROUP BY year_and_month\n" +
            "                  ) AS rng\n" +
            "             WHERE rs.reference = rng.range_start\n" +
            "               AND re.reference = rng.range_end\n" +
            "         )\n" +
            "SELECT (MAX(value) - MIN(value)) AS value, dr.device_type AS deviceType, (year_and_month DIV 100) AS year, (year_and_month MOD 100) AS month\n" +
            "FROM device_reading dr, reportRange rng\n" +
            "WHERE dr.business_day_id IN (rng.firstDay, rng.lastDay)\n" +
            "AND dr.device_type IN (:types)\n" +
            "GROUP BY year_and_month, device_type\n" +
            "ORDER BY year_and_month desc, device_type", nativeQuery = true)
    fun loadDeviceMonthlyReport(
        @Param("types") types: Collection<Int>): List<DeviceReadingMonthlyReport>
}