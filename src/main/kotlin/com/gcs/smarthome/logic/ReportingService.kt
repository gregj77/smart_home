package com.gcs.smarthome.logic

import com.gcs.smarthome.data.model.DeviceType
import com.gcs.smarthome.data.repository.BusinessDayRepository
import com.gcs.smarthome.logic.MeterService.Companion.tagTypeDailyValue
import com.gcs.smarthome.logic.MeterService.Companion.tagTypeMonthlyValue
import com.gcs.smarthome.logic.MeterService.Companion.tagTypeName
import com.gcs.smarthome.logic.message.BusinessDayCloseEvent
import com.gcs.smarthome.logic.message.BusinessDayOpenEvent
import com.gcs.smarthome.logic.message.ElectricReadingEvent
import com.google.common.util.concurrent.AtomicDouble
import io.micrometer.core.instrument.*
import mu.KotlinLogging
import org.springframework.context.event.EventListener
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import javax.annotation.PostConstruct

//@Service
class ReportingServiceOld(
    private val businessDayRepository: BusinessDayRepository,
    private val cfg : ElectricPowerMonitoringConfig.ConfigurationResult,
    private val meterService: MeterService) {

    private val logger = KotlinLogging.logger {  }
    private val deviceTypes = listOf(
        DeviceType.POWER_METER_EXPORT.ordinal, DeviceType.POWER_METER_IMPORT.ordinal, DeviceType.POWER_METER_PRODUCTION.ordinal)

    private val powerWarehouseReadingStorage = AtomicDouble(0.0)

    private val totalElectricityImported = AtomicDouble(0.0)
    private val totalElectricityExported = AtomicDouble(0.0)
    private val electricityImportedSinceLastReading = AtomicDouble(0.0)
    private val electricityExportedSinceLastReading = AtomicDouble(0.0)

    private val importOffset = AtomicDouble(0.0)
    private val exportOffset = AtomicDouble(0.0)

    private val counters = ConcurrentHashMap<String, Counter>()
    private val businessDay = AtomicReference<LocalDate>()

    private val monthlyReportKey = AtomicReference<String>("")

    private val readingUpdateEventMap = mapOf<DeviceType, (Double) -> Unit>(
        DeviceType.POWER_METER_IMPORT to {
            onRefreshGauges(
                DeviceType.POWER_METER_IMPORT,
                it,
                importOffset,
                totalElectricityImported,
                electricityImportedSinceLastReading)
         },
        DeviceType.POWER_METER_EXPORT to {
            onRefreshGauges(
                DeviceType.POWER_METER_EXPORT,
                it,
                exportOffset,
                totalElectricityExported,
                electricityExportedSinceLastReading)
        }
    )

    @PostConstruct
    fun initialize() {
        meterService.createOrGetGauge(POWER_STORAGE_READING, Tags.empty()) { powerWarehouseReadingStorage }
        meterService.createOrGetGauge(TOTAL_ELECTRICITY_IMPORTED, Tags.empty()) { totalElectricityImported }
        meterService.createOrGetGauge(TOTAL_ELECTRICITY_EXPORTED, Tags.empty()) { totalElectricityExported }
        meterService.createOrGetGauge(ELECTRICITY_IMPORTED_SINCE_LAST_READING, Tags.empty()) { electricityImportedSinceLastReading }
        meterService.createOrGetGauge(ELECTRICITY_EXPORTED_SINCE_LAST_READING, Tags.empty()) { electricityExportedSinceLastReading }
    }


    @EventListener
    fun onBusinessDayStart(day: BusinessDayOpenEvent) {
        logger.info { "initializing reporting service for $day" }
        businessDay.set(day.date)

        monthlyReportKey.set("${day.date.year}-${day.date.month}")

        initializeDailyCounters()
        initializeMonthlyCounters()
    }

    @EventListener
    fun onBusinessDayEnd(day: BusinessDayCloseEvent) {
        logger.info { "closing business day for $day" }
        counters.clear()
        meterService.cleanupDailyMeters(day.date)
    }

    @EventListener(condition = "#args.isPowerReading()")
    fun onNewReferenceElectricityReading(args: NewReferenceReading) {
        logger.info { "got power reading $args" }
        powerWarehouseReadingStorage.set(args.value.toDouble())
        importOffset.set((args.importReadingValue ?: BigDecimal.ZERO).toDouble())
        exportOffset.set((args.exportReadingValue ?: BigDecimal.ZERO).toDouble())

        readingUpdateEventMap[DeviceType.POWER_METER_EXPORT]?.invoke(totalElectricityExported.get())
        readingUpdateEventMap[DeviceType.POWER_METER_IMPORT]?.invoke(totalElectricityImported.get())

        logger.info { "setting up initial storage settings for ${args.referenceType}: " +
                "warehouse= ${powerWarehouseReadingStorage.get()}, " +
                "importAtReadingTime= ${importOffset.get()}, " +
                "exportAtReadingTime= ${exportOffset.get()}, " +
                "totalExport= ${totalElectricityExported.get()}, " +
                "totalImport= ${totalElectricityImported.get()}" }
    }

 //   @EventListener
    fun onNewMeterReading(args: ElectricReadingEvent) {
        readingUpdateEventMap[args.deviceType]?.invoke(args.value)

        val dailyKey = "daily_${args.alias}"
        val monthlyKey = "monthly_${args.alias}"
        val dailyCounter = counters.computeIfAbsent(dailyKey) { key ->
            val dailyCounter = meterService.withDayTag(businessDay.get()) {
                meterService.createOrGetCounter(key, it.and(ImmutableTag(tagTypeName, tagTypeDailyValue)), 0.0)
            }
            logger.info { "created first counter $key for business day ${businessDay.get()}" }
            dailyCounter.second
        }
        dailyCounter.increment(args.deltaSinceLastReading)
        counters[monthlyKey]?.increment(args.deltaSinceLastReading)

    }

    private fun onRefreshGauges(deviceType: DeviceType, currentReading: Double, offset: AtomicDouble, total: AtomicDouble, adjusted: AtomicDouble) {
        total.set(currentReading)
        adjusted.set(currentReading - offset.get())
        logger.debug { "updating totals for $deviceType - total: $currentReading, adjusted: ${adjusted.get()}" }
    }

    private fun initializeDailyCounters() {
        val report = businessDayRepository
            .loadDeviceDailyReport(deviceTypes)
            .groupBy { it.deviceType }

        logger.info { "updateDailyCounters triggered ${LocalDateTime.now()} - loaded report with ${report.keys} deviceTypes" }
        val dailyTag = ImmutableTag(tagTypeName, tagTypeDailyValue)

        val dailyMeters = meterService.listMeterIdsBy(MeterService.filterByTag(dailyTag))

        report.forEach { (type, freshReports) ->
            cfg.metricsMapping[type]?.let { alias ->
                freshReports.forEach { report ->
                    val (id, _, _) = meterService.withDayTag(report.date) {
                        meterService.createOrGetCounter("daily_$alias", it.and(dailyTag), report.value.toDouble())
                    }
                    dailyMeters.remove(id)
                }
            }
        }
        meterService.removeMeters(dailyMeters)
    }

    private fun initializeMonthlyCounters() {
        val report = businessDayRepository
            .loadDeviceMonthlyReport(deviceTypes)
            .groupBy { it.deviceType }

        val monthly = meterService.listMeterIdsBy(MeterService.filterByMonthlyTag())
        meterService.removeMeters(monthly)
        logger.info { "updateMonthlyCounters triggered ${LocalDateTime.now()} - loaded report with ${report.keys} deviceTypes" }
        report.forEach { (deviceType, reports) ->

            reports.forEach { report ->
                val tags = Tags.of(ImmutableTag(tagTypeName, tagTypeMonthlyValue), ImmutableTag("date", "${report.year}-${report.month.format(2)}"))
                val (id, counter, _ ) = meterService.createOrGetCounter("monthly_${cfg.metricsMapping[deviceType]}", tags, report.value.toDouble())
                if (report.year == businessDay.get().year && report.month == businessDay.get().monthValue) {
                    counters[id.name] = counter
                }
            }
        }

    }

    companion object {
        const val POWER_STORAGE_READING = "last_power_storage_reading"
        const val TOTAL_ELECTRICITY_IMPORTED = "total_electricity_imported"
        const val TOTAL_ELECTRICITY_EXPORTED = "total_electricity_exported"
        const val ELECTRICITY_IMPORTED_SINCE_LAST_READING = "electricity_imported_since_last_reading"
        const val ELECTRICITY_EXPORTED_SINCE_LAST_READING = "electricity_exported_since_last_reading"
    }

    fun Number.format(pad: Int): String = "%0${pad}d".format(this)
}