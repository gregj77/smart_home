package com.gcs.smarthome.logic

import com.gcs.smarthome.data.model.DeviceType
import com.gcs.smarthome.data.model.ReferenceType
import com.gcs.smarthome.logic.message.BusinessDayOpenEvent
import com.gcs.smarthome.logic.message.ElectricReadingEvent
import com.google.common.util.concurrent.AtomicDouble
import io.micrometer.core.instrument.Tags
import mu.KotlinLogging
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import reactor.core.Disposables
import reactor.core.publisher.Flux
import reactor.core.publisher.Sinks
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import javax.annotation.PostConstruct
import javax.annotation.PreDestroy

@Service
class EnergyStoreReportingService(private val meterService: MeterService) {

    private val logger = KotlinLogging.logger {  }

    private val energyReadings = Sinks.many()
        .multicast()
        .onBackpressureBuffer<ElectricReadingEvent>()

    private val referenceReadings = Sinks.many()
        .multicast()
        .onBackpressureBuffer<NewReferenceReading>()

    private val businessDaysStream = Sinks.many()
        .multicast()
        .onBackpressureBuffer<LocalDate>()

    private val subscriptions = Disposables.composite()

    @PostConstruct
    fun initialize() {

        val energyImportStream = energyReadings
            .asFlux()
            .filter { it.deviceType == DeviceType.POWER_METER_IMPORT }
            .map(ElectricReadingEvent::value)
        val energyExportStream = energyReadings
            .asFlux()
            .filter { it.deviceType == DeviceType.POWER_METER_EXPORT }
            .map(ElectricReadingEvent::value)
        val energyProductionStream = energyReadings
            .asFlux()
            .filter{ it.deviceType == DeviceType.POWER_METER_PRODUCTION }
            .map(ElectricReadingEvent::value)

        val referenceReadingStream = referenceReadings
            .asFlux()
            .filter(NewReferenceReading::isPowerReading)
            .startWith(NewReferenceReading(LocalDateTime.now(), ReferenceType.POWER_READING, BigDecimal.ZERO, null, BigDecimal.ZERO, null, BigDecimal.ZERO))
            .filter{ it.exportReadingValue != null && it.importReadingValue != null}

        val referenceDayReadingStream = referenceReadings
            .asFlux()
            .filter(NewReferenceReading::isPowerReading)
            .map { it.readingDate.toLocalDate() }

        val (id1, powerWarehouseReadingStorage, _) = meterService.createOrGetGauge(POWER_STORAGE_READING, Tags.empty()) { AtomicDouble(0.0) }
        val (id2, totalElectricityImported, _) = meterService.createOrGetGauge(TOTAL_ELECTRICITY_IMPORTED, Tags.empty()) { AtomicDouble(0.0) }
        val (id3, totalElectricityExported, _) = meterService.createOrGetGauge(TOTAL_ELECTRICITY_EXPORTED, Tags.empty()) { AtomicDouble(0.0) }
        val (id4, electricityImportedSinceLastReading, _) = meterService.createOrGetGauge(ELECTRICITY_IMPORTED_SINCE_LAST_READING, Tags.empty()) { AtomicDouble(0.0) }
        val (id5, electricityExportedSinceLastReading, _) = meterService.createOrGetGauge(ELECTRICITY_EXPORTED_SINCE_LAST_READING, Tags.empty()) { AtomicDouble(0.0) }
        val (id6, totalElectricityProduced, _) = meterService.createOrGetGauge(TOTAL_ELECTRICITY_PRODUCED, Tags.empty()) { AtomicDouble(0.0) }
        val (id7, daysSinceLastReading, _) = meterService.createOrGetGauge(DAYS_SINCE_LAST_READING, Tags.empty()) { AtomicDouble(0.0) }

        subscriptions.add(
            Flux
                .combineLatest(energyImportStream, energyExportStream, energyProductionStream, referenceReadingStream) {
                    val totalImport = it[0] as Double
                    val totalExport = it[1] as Double
                    val totalProduction = it[2] as Double
                    val referenceReading = it[3] as NewReferenceReading
                    val lastStorageValue = referenceReading.value.toDouble()
                    val referenceImport = referenceReading.importReadingValue!!.toDouble()
                    val referenceExport = referenceReading.exportReadingValue!!.toDouble()

                    totalElectricityImported.set(totalImport)
                    totalElectricityExported.set(totalExport)
                    totalElectricityProduced.set(totalProduction)

                    electricityImportedSinceLastReading.set(totalImport - referenceImport)
                    electricityExportedSinceLastReading.set(totalExport - referenceExport)

                    powerWarehouseReadingStorage.set(lastStorageValue)
                }
                .subscribe())

        subscriptions.add(
            Flux
                .combineLatest(referenceDayReadingStream, businessDaysStream.asFlux()) { refDate, currentDate ->
                (currentDate.toEpochDay() - refDate.toEpochDay()).toDouble()
            }
            .subscribe {
                daysSinceLastReading.set(it)
                logger.debug { "days since last reading: $it" }
            })

        subscriptions.add {
            meterService.removeMeters(listOf(id1, id2, id3, id4, id5, id6, id7))
        }
    }

    @PreDestroy
    fun onDestroy() {
        subscriptions.dispose()
    }

    @EventListener
    fun onBusinessDayStart(day: BusinessDayOpenEvent) {
        logger.info { "initializing reporting service for $day" }
        businessDaysStream.tryEmitNext(day.date)
    }

    @EventListener
    fun onNewReferenceElectricityReading(args: NewReferenceReading) {
        logger.info { "got power reading $args" }
        referenceReadings.tryEmitNext(args)
    }

    @EventListener
    fun onNewMeterReading(args: ElectricReadingEvent) {
        energyReadings.tryEmitNext(args)
    }

    companion object {
        const val POWER_STORAGE_READING = "last_power_storage_reading"
        const val TOTAL_ELECTRICITY_IMPORTED = "total_electricity_imported"
        const val TOTAL_ELECTRICITY_EXPORTED = "total_electricity_exported"
        const val TOTAL_ELECTRICITY_PRODUCED = "total_electricity_produced"
        const val ELECTRICITY_IMPORTED_SINCE_LAST_READING = "electricity_imported_since_last_reading"
        const val ELECTRICITY_EXPORTED_SINCE_LAST_READING = "electricity_exported_since_last_reading"
        const val DAYS_SINCE_LAST_READING = "days_since_last_reading"
    }
}