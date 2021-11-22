package com.gcs.smarthome.logic

import com.gcs.smarthome.data.model.DeviceType
import com.gcs.smarthome.logic.cqrs.EventPublisher
import com.google.common.util.concurrent.AtomicDouble
import io.micrometer.core.instrument.Meter
import mu.KotlinLogging
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import reactor.core.Disposables
import reactor.core.publisher.Flux
import reactor.util.retry.RetrySpec
import java.math.BigDecimal
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import javax.annotation.PostConstruct
import javax.annotation.PreDestroy

@Service
class ElectricPowerMonitoring(
    private val dataStream: Flux<out ElectricReading>,
    private val eventPublisher: EventPublisher
    ) {

    private val logger = KotlinLogging.logger {  }

    private val isIntialized = AtomicBoolean(false)
    private val persistableReadingToken = Disposables.swap()
    private val currentPowerToken = Disposables.swap()

    @EventListener
    fun onNewBusinessDay(day: BusinessDayHub.BusinessDayAvailableEvent) {
        logger.info { "business day starting $day" }
        persistableReadingToken.update(initializePersistableDataStream(dataStream))
        if (!isIntialized.get()) {
            isIntialized.set(true)
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
                val args = DeviceReadingHub.queryLatestDeviceReading(it.key().second)
                eventPublisher
                    .query(args, it) { ctx, reading -> PersistenceContext(ctx.key().first, ctx.key().second, reading, ctx) }
                    .retryWhen(RetrySpec.fixedDelay(10L, Duration.ofSeconds(5L)))
            }
            .flatMap { ctx ->
                logger.info { "initial reading for ${ctx.alias}.${ctx.deviceType} = ${ctx.reading}, setting up counter..." }
                eventPublisher
                    .command(ReportingService.commandRequestCounter(ctx.alias, ctx.reading.toDouble()), ctx ) { c, id -> c.updateMeterId(id) }
                    .retryWhen(RetrySpec.fixedDelay(10L, Duration.ofSeconds(5L)))
            }
            .flatMap { ctx ->
                logger.info { "counter configured, starting to calculate increments for ${ctx.alias}.${ctx.deviceType}..." }
                var lastReading = ctx.reading
                ctx
                    .stream
                    .filter { it.value > lastReading}
                    .map { reading ->
                        val delta = (reading.value - lastReading).toDouble()
                        logger.debug { "delta since last reading of ${ctx.alias} is $delta" }
                        lastReading = reading.value
                        ReadingWithDelta(reading, delta, ctx.deviceType, ctx.meterId!!)
                    }
            }
            .subscribe(this::processNewReading)
                { err -> logger.error { "failure while processing stream - ${err.message} <${err.javaClass.name}>\n${err.stackTrace}" } }

    private fun initializeInstantDataStream(stream: Flux<out ElectricReading>) =
        stream
            .filter { it is InstantReading }
            .groupBy { it.alias }
            .flatMap {
                logger.info { "configuring gauge for ${it.key()}" }
                val storage = AtomicDouble()
                eventPublisher
                    .command(ReportingService.commandRequestGauge(it.key(), storage), it) { ctx, _ -> InstantContext(ctx.key(), storage, ctx) }
                    .retryWhen(RetrySpec.fixedDelay(10L, Duration.ofSeconds(5L)))
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
            .command(DeviceReadingHub.commandStoreReading(reading.deviceType, reading.value))
            .retryWhen(RetrySpec.fixedDelay(5, Duration.ofSeconds(1)))
            .subscribe(
                { logger.debug { "stored ${reading.deviceType} reading with id = $it" } },
                { err -> logger.warn { "failed to save reading ${reading.deviceType} - ${err.message} <${err.javaClass.name}>" } } )

        eventPublisher
            .command(ReportingService.commandUpdateCounter(reading.meterId, reading.delta))
            .subscribe(
                { logger.debug { "counter ${reading.meterId} updated" } },
                { err -> logger.warn { "failed to update counter ${reading.meterId} - ${err.message} <${err.javaClass.name}>" } } )
    }



    private data class PersistenceContext(
        val alias: String,
        val deviceType: DeviceType,
        val reading: BigDecimal,
        val stream: Flux<out ElectricReading>) {

        var meterId: Meter.Id? = null

        fun updateMeterId(id: Meter.Id): PersistenceContext {
            meterId = id
            return this
        }
    }

    private data class InstantContext(
        val alias: String,
        val storage: AtomicDouble,
        val stream: Flux<out ElectricReading>
    )

    private data class ReadingWithDelta(
        val value: ElectricReading,
        val delta: Double,
        val deviceType: DeviceType,
        val meterId: Meter.Id)
}