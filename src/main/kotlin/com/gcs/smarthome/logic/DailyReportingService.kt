package com.gcs.smarthome.logic

import com.gcs.smarthome.data.model.DeviceType
import com.gcs.smarthome.data.repository.BusinessDayRepository
import com.gcs.smarthome.logic.message.BusinessDayOpenEvent
import com.gcs.smarthome.logic.message.ElectricReadingEvent
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.ImmutableTag
import io.micrometer.core.instrument.Meter
import mu.KLogger
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import reactor.core.Disposables
import reactor.core.publisher.Flux
import reactor.core.publisher.Sinks
import reactor.core.scheduler.Scheduler
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import javax.annotation.PostConstruct
import javax.annotation.PreDestroy

@Service
class DailyReportingService(
    private val businessDayRepository: BusinessDayRepository,
    private val config: ElectricPowerMonitoringConfig.ConfigurationResult,
    private val scheduler: Scheduler,
    private val meterService: MeterService,
    private val localDateProvider: () -> LocalDate,
    @Value("\${reportingService.maxDaysInHistory:30}") private val maxDaysInHistory: Long
) {

    private val deviceTypes = mapOf<Int, (Double, Double, LocalDate) -> ElectricReadingEvent>(
        DeviceType.POWER_METER_EXPORT.ordinal to { value, delta, date ->
            ElectricReadingEvent(
                DeviceType.POWER_METER_EXPORT,
                config.metricsMapping[DeviceType.POWER_METER_EXPORT]!!,
                value,
                delta,
                LocalDateTime.of(date, LocalTime.MIDNIGHT)
            )
        },
        DeviceType.POWER_METER_IMPORT.ordinal to { value, delta, date ->
            ElectricReadingEvent(
                DeviceType.POWER_METER_IMPORT,
                config.metricsMapping[DeviceType.POWER_METER_IMPORT]!!,
                value,
                delta,
                LocalDateTime.of(date, LocalTime.MIDNIGHT)
            )
        },
        DeviceType.POWER_METER_PRODUCTION.ordinal to { value, delta, date ->
            ElectricReadingEvent(
                DeviceType.POWER_METER_PRODUCTION,
                config.metricsMapping[DeviceType.POWER_METER_PRODUCTION]!!,
                value,
                delta,
                LocalDateTime.of(date, LocalTime.MIDNIGHT)
            )
        },
    )

    private val logger = KotlinLogging.logger { }

    private val dailyReadings = Sinks.many()
        .multicast()
        .onBackpressureBuffer<ElectricReadingEvent>()

    private val subscriptions = Disposables.composite()

    @PostConstruct
    fun onInitialize() {

        val streams = dailyReadings
            .asFlux()
            .filter(this::filterOnlyFresh)
            .groupBy { Pair(it.deviceType, it.timestamp.toLocalDate()) }
            .map { configureStreamContext(it.key().first, it.key().second, it) }

        subscriptions.add(Flux.merge(streams).subscribe())
    }

    @PreDestroy
    fun onDestroy() {
        subscriptions.dispose()
    }

    @EventListener
    fun onBusinessDayStart(day: BusinessDayOpenEvent) {
        logger.info { "initializing reporting service for $day" }

        val now = localDateProvider()
        businessDayRepository
            .loadDeviceDailyReport(deviceTypes.keys, now.minusDays(maxDaysInHistory), now)
            .map { deviceTypes[it.deviceType.ordinal]!!.invoke(it.value.toDouble(), it.dailyDelta.toDouble(), it.date) }
            .forEach { dailyReadings.tryEmitNext(it) }
    }

    @EventListener
    fun onNewMeterReading(args: ElectricReadingEvent) {
        dailyReadings.tryEmitNext(args)
    }

    private fun filterOnlyFresh(event: ElectricReadingEvent): Boolean {
        val isInRange = localDateProvider().toEpochDay() - event.timestamp.toLocalDate().toEpochDay() <= maxDaysInHistory
        if (!isInRange) {
            logger.info { "discarding too old reading $event" }
        }
        return isInRange
    }

    private fun configureStreamContext(device: DeviceType, readingDate: LocalDate, events: Flux<ElectricReadingEvent>): Flux<ElectricReadingEvent> {
        var lastTime = LocalDateTime.MIN
        val maxMinutes = ((maxDaysInHistory - (localDateProvider().toEpochDay() - readingDate.toEpochDay())) * 60L * 24L ) + 1L

        logger.info { "configuring new stream context for $device [$readingDate] - valid for next ${maxMinutes / 60L} hours" }
        val counterContext = createDailyCounter(device, readingDate)

        return events
            .filter {
                val result = it.timestamp > lastTime
                lastTime = it.timestamp
                result
            }
            .take(Duration.ofMinutes(maxMinutes), scheduler)
            .doOnNext(counterContext::update)
            .doOnCancel(counterContext::close)
            .doOnTerminate(counterContext::close)
    }

    private fun createDailyCounter(deviceType: DeviceType, date: LocalDate): DailyCounterContext {

        val dailyKey = "daily_${config.metricsMapping[deviceType]}"
        val dailyCounter = meterService.withDayTag(date) {
            meterService.createOrGetCounter(dailyKey, it.and(
                ImmutableTag(
                    MeterService.tagTypeName,
                    MeterService.tagTypeDailyValue
                )
            ), 0.0)
        }

        return DailyCounterContext(dailyCounter.first, dailyCounter.second, logger) {
            meterService.removeMeters(listOf(dailyCounter.first))
        }
    }

    private class DailyCounterContext(private val id: Meter.Id, private val counter: Counter, private val logger: KLogger, private val onDispose: () -> Unit): AutoCloseable {
        fun update(event: ElectricReadingEvent) {
            counter.increment(event.deltaSinceLastReading)
            logger.debug{ "update counter $id by ${event.deltaSinceLastReading} to ${counter.count()} "}
        }

        override fun close() {
            logger.info { "counter $id is expired - removing it!" }
            onDispose()
        }
    }
}