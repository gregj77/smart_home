package com.gcs.smarthome.logic

import com.gcs.smarthome.data.model.DeviceReading
import com.gcs.smarthome.data.model.DeviceType
import com.gcs.smarthome.data.repository.DeviceReadingRepository
import com.gcs.smarthome.logic.cqrs.EventPublisher
import com.gcs.smarthome.logic.cqrs.GenericCommand
import com.gcs.smarthome.logic.cqrs.GenericQuery
import com.google.common.util.concurrent.AtomicDouble
import mu.KotlinLogging
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import reactor.util.function.Tuple2
import java.math.BigDecimal
import java.util.concurrent.atomic.AtomicReference
import javax.transaction.Transactional

@Service
class DeviceReadingHub(private val repository: DeviceReadingRepository, private val eventPublisher: EventPublisher) {

    private val logger = KotlinLogging.logger {  }
    private val businessDay: AtomicReference<Short> = AtomicReference(-1)
    private val totalElectricityImported = AtomicDouble()
    private val totalElectricityExported = AtomicDouble()
    private val electricityImportedSinceLastReading = AtomicDouble()
    private val electricityExportedSinceLastReading = AtomicDouble()

    private val importOffset = AtomicDouble()
    private val exportOffset = AtomicDouble()

    private val readingUpdateEventMap = mapOf<DeviceType, (BigDecimal) -> Unit>(
        DeviceType.POWER_METER_IMPORT to { onRefreshGauges(DeviceType.POWER_METER_IMPORT, it, importOffset, totalElectricityImported, electricityImportedSinceLastReading) },
        DeviceType.POWER_METER_EXPORT to { onRefreshGauges(DeviceType.POWER_METER_EXPORT, it, exportOffset, totalElectricityExported, electricityExportedSinceLastReading) }
    )

    @EventListener
    fun onNewBusinessDayAssigned(event: BusinessDayHub.BusinessDayOpenEvent) {
        logger.info { "business starting - $event" }
        businessDay.set(event.businessDayId)

        eventPublisher.command(ReportingService.commandRequestGauge("total_electricity_imported", totalElectricityImported))
        eventPublisher.command(ReportingService.commandRequestGauge("total_electricity_exported", totalElectricityExported))
        eventPublisher.command(ReportingService.commandRequestGauge("electricity_imported_since_last_reading", electricityImportedSinceLastReading))
        eventPublisher.command(ReportingService.commandRequestGauge("electricity_exported_since_last_reading", electricityExportedSinceLastReading))
    }

    @EventListener
    fun handleLatestDeviceReading(event: LatestDeviceReadingQuery) {
        event.applyEvent {
            val reading = repository
                .findFirstByDeviceTypeOrderByIdDesc(it)
                .map { rec -> rec.value }
                .orElse(BigDecimal.ZERO)

            logger.debug { "device reading for $it = $reading" }
            reading
        }
    }

    @EventListener
    fun handleDailyDeltaDeviceReading(event: DailyDeltaDeviceReadingQuery) {
        event.applyEvent {
            val reading = repository.getDailyReadingDelta(it.first, it.second.toShort()) ?: BigDecimal.ZERO
            logger.debug { "device reading delta for ${it.first} = $reading" }
            reading
        }
    }

    @EventListener(condition = "#args.isPowerReading()")
    fun onNewElectricityReading(args: NewReferenceReading) {
        logger.info { "got power reading $args" }
        val importValueAtTimeOfReading = args.importReadingValue?.let {
            repository.findById(it).get().value
        } ?: BigDecimal.ZERO

        val exportValueAtTimeOfReading = args.exportReadingId?.let {
            repository.findById(it).get().value
        } ?: BigDecimal.ZERO

        importOffset.set(importValueAtTimeOfReading.toDouble())
        exportOffset.set(exportValueAtTimeOfReading.toDouble())

        readingUpdateEventMap[DeviceType.POWER_METER_EXPORT]?.invoke(totalElectricityExported.get().toBigDecimal())
        readingUpdateEventMap[DeviceType.POWER_METER_IMPORT]?.invoke(totalElectricityImported.get().toBigDecimal())
    }


    @EventListener
    @Transactional
    fun handleStoreLatestReading(cmd: StoreLatestReadingCommand) {
        cmd.applyEvent {
            if (businessDay.get() > 0) {
                readingUpdateEventMap[it.first]?.invoke(it.second.value)
                repository.save(DeviceReading(it.first, it.second.value, it.second.time.toLocalTime(), businessDay.get())).id
            } else {
                throw IllegalStateException("business day not initialized!")
            }
        }
    }

    private fun onRefreshGauges(deviceType: DeviceType, currentReading: BigDecimal, offset: AtomicDouble, total: AtomicDouble, adjusted: AtomicDouble) {
        total.set(currentReading.toDouble())
        adjusted.set(currentReading.toDouble() - offset.get())
        logger.debug { "updating totals for $deviceType - total: $currentReading, adjusted: ${adjusted.get()}" }
    }

    class LatestDeviceReadingQuery(payload: DeviceType) : GenericQuery<DeviceType, BigDecimal>(payload), EventInvoker<DeviceType, BigDecimal> {
        override fun applyEvent(callback: (DeviceType) -> BigDecimal) = execute(callback)
    }

    class DailyDeltaDeviceReadingQuery(deviceType: DeviceType, businessDay: Int) : GenericQuery<Pair<DeviceType, Int>, BigDecimal>(
        Pair(deviceType, businessDay)
    ), EventInvoker<Pair<DeviceType, Int>, BigDecimal> {
        override fun applyEvent(callback: (Pair<DeviceType, Int>) -> BigDecimal) = execute(callback)
    }

    class StoreLatestReadingCommand(deviceType: DeviceType, reading: ElectricReading) : GenericCommand<Pair<DeviceType, ElectricReading>, Int>(
        Pair(deviceType, reading)
    ), EventInvoker<Pair<DeviceType, ElectricReading>, Int> {
        override fun applyEvent(callback: (Pair<DeviceType,ElectricReading>) -> Int) = execute(callback)
    }

    private interface EventInvoker<TPayload, TResult> {
        fun applyEvent(callback: (TPayload) -> TResult)
    }

    companion object {
        fun queryLatestDeviceReading(deviceType: DeviceType) = LatestDeviceReadingQuery(deviceType)
        fun queryDailyDeltaDeviceReading(deviceType: DeviceType, businessDay: Int) = DailyDeltaDeviceReadingQuery(deviceType, businessDay)
        fun commandStoreReading(deviceType: DeviceType, reading: ElectricReading) = StoreLatestReadingCommand(deviceType, reading)
    }
}