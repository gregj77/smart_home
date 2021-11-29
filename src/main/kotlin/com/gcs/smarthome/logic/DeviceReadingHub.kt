package com.gcs.smarthome.logic

import com.gcs.smarthome.data.model.DeviceReading
import com.gcs.smarthome.data.model.DeviceType
import com.gcs.smarthome.data.repository.DeviceReadingRepository
import com.gcs.smarthome.logic.cqrs.GenericCommand
import com.gcs.smarthome.logic.cqrs.GenericQuery
import mu.KotlinLogging
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.util.concurrent.atomic.AtomicReference
import javax.transaction.Transactional

@Service
class DeviceReadingHub(private val repository: DeviceReadingRepository) {

    private val logger = KotlinLogging.logger {  }
    private val businessDay: AtomicReference<Short> = AtomicReference(-1)

    @EventListener
    fun onNewBusinessDayAssigned(event: BusinessDayHub.BusinessDayOpenEvent) {
        logger.info { "business starting - $event" }
        businessDay.set(event.businessDayId)
    }

    @EventListener
    fun handleLatestDeviceReadingQuery(event: LatestDeviceReadingQuery) {
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
    fun handleDailyDeltaDeviceReadingQuery(event: DailyDeltaDeviceReadingQuery) {
        event.applyEvent {
            val reading = repository.getDailyReadingDelta(it.first, it.second.toShort()) ?: BigDecimal.ZERO
            logger.debug { "device reading delta for ${it.first} = $reading" }
            reading
        }
    }

    @EventListener
    @Transactional
    fun handleStoreLatestReading(cmd: StoreLatestReadingCommand) {
        cmd.applyEvent {
            if (businessDay.get() > 0) {
                repository.save(DeviceReading(it.first, it.second.value, it.second.time.toLocalTime(), businessDay.get())).id
            } else {
                throw IllegalStateException("business day not initialized!")
            }
        }
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