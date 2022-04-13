package com.gcs.smarthome.logic

import com.gcs.smarthome.data.model.DeviceType
import com.gcs.smarthome.logic.cqrs.EventPublisher
import com.google.common.util.concurrent.AtomicDouble
import io.micrometer.core.instrument.Tags
import mu.KotlinLogging
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import reactor.core.Disposables
import reactor.core.publisher.Flux
import reactor.util.retry.RetrySpec
import java.math.BigDecimal
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.annotation.PostConstruct
import javax.annotation.PreDestroy

@Service
class ElectricPowerMonitoring(
    private val dataStream: Flux<out ElectricReading>,
    private val eventPublisher: EventPublisher,
    private val meterService: MeterService
    ) {

    private val logger = KotlinLogging.logger {  }

    private val isInitialized = AtomicBoolean(false)
    private val persistableReadingToken = Disposables.swap()
    private val currentPowerToken = Disposables.swap()
    private val businessDay = AtomicInteger(0)

    @EventListener
    fun onNewBusinessDay(day: BusinessDayHub.BusinessDayOpenEvent) {
        logger.info { "business day starting $day" }
        businessDay.set(day.businessDayId.toInt())
        persistableReadingToken.update(initializePersistableDataStream(dataStream))
        if (!isInitialized.get()) {
            isInitialized.set(true)
            currentPowerToken.update(initializeInstantDataStream(dataStream))
        }
    }

    @PostConstruct
    private fun onInitialize() {
        logger.info { "starting instant data monitoring..." }
    }

    @PreDestroy
    private fun onDestroy() {
        persistableReadingToken.dispose()
        currentPowerToken.dispose()
    }

    private fun initializePersistableDataStream(stream: Flux<out ElectricReading>) =
        stream
            .filter { it is PersistableReading }
            .groupBy {
                it as PersistableReading
                Pair(it.alias, it.deviceType)
            }
            .flatMap {
                logger.info { "received first entry for ${it.key().first}.${it.key().second} for current business day, requesting initial reading..." }
                val lastReadingArgs = DeviceReadingHub.queryLatestDeviceReading(it.key().second)
                val readDeltaArgs = DeviceReadingHub.queryDailyDeltaDeviceReading(it.key().second, businessDay.get())

                eventPublisher
                    .query(lastReadingArgs)
                    .zipWith(eventPublisher.query(readDeltaArgs))
                    .retryWhen(RetrySpec.fixedDelay(10L, Duration.ofSeconds(5L)))
                    .map { readingAndDelta -> PersistenceContext(it.key().first, it.key().second, readingAndDelta.t1, readingAndDelta.t2, it)}
            }
            .flatMap { ctx ->
                logger.info { "counter configured, starting to calculate increments for ${ctx.alias}.${ctx.deviceType}..." }
                var lastReading = ctx.reading
                var isFirstReading = true
                eventPublisher.broadcastEvent(ElectricReadingEvent(ctx.deviceType, ctx.alias, ctx.reading.toDouble(), ctx.delta.toDouble()))
                ctx
                    .stream
                    .filter { it.value > lastReading || isFirstReading}
                    .map { reading ->
                        isFirstReading = false
                        val delta = (reading.value - lastReading).toDouble()
                        logger.debug { "delta since last reading of ${ctx.alias} is $delta" }
                        lastReading = reading.value
                        ReadingWithDelta(reading, delta, ctx.deviceType)
                    }
            }
            .subscribe(this::processNewReading)
                { err -> logger.error { "failure while processing stream - ${err.message} <${err.javaClass.name}>\n${err.stackTrace}" } }

    private fun initializeInstantDataStream(stream: Flux<out ElectricReading>) =
        stream
            .filter { it is InstantReading }
            .groupBy { it.alias }
            .map {
                logger.info { "configuring gauge for ${it.key()}" }
                val (_, storage, _) = meterService.createOrGetGauge(it.key(), Tags.empty()) { AtomicDouble() }
                InstantContext(it.key(), storage, it)
            }
            .flatMap { ctx ->
                logger.info { "gauge configured, starting to provide current readings for ${ctx.alias}..." }

                ctx
                    .stream
                    .scan(ctx.storage) { acc, reading ->
                        acc.set(reading.value.toDouble())
                        logger.debug { "current ${ctx.alias} => ${reading.value} ${reading.unit}" }
                        acc
                    }
                    .map(AtomicDouble::get)
            }
            .subscribe(
                {},
                { err -> logger.error { "failure while processing stream - ${err.message} <${err.javaClass.name}>\n${err.stackTrace}" } } )

    private fun processNewReading(reading: ReadingWithDelta) {
        eventPublisher
            .command(DeviceReadingHub.commandStoreReading(reading.deviceType, reading.reading))
            .retryWhen(RetrySpec.fixedDelay(5, Duration.ofSeconds(1)))
            .subscribe(
                { logger.debug { "stored ${reading.deviceType} reading with id = $it" } },
                { err -> logger.warn { "failed to save reading ${reading.deviceType} - ${err.message} <${err.javaClass.name}>" } } )


        eventPublisher.broadcastEvent(ElectricReadingEvent(reading.deviceType, reading.reading.alias, reading.reading.value.toDouble(), reading.delta))
    }

    private data class PersistenceContext(
        val alias: String,
        val deviceType: DeviceType,
        val reading: BigDecimal,
        val delta: BigDecimal,
        val stream: Flux<out ElectricReading>)

    private data class InstantContext(
        val alias: String,
        val storage: AtomicDouble,
        val stream: Flux<out ElectricReading>
    )

    private data class ReadingWithDelta(
        val reading: ElectricReading,
        val delta: Double,
        val deviceType: DeviceType)
}

data class ElectricReadingEvent(
    val deviceType: DeviceType,
    val alias: String,
    val value: Double,
    val deltaSinceLastReading: Double)
