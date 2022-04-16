package com.gcs.smarthome.logic

import com.gcs.smarthome.data.model.DeviceType
import com.gcs.smarthome.logic.cqrs.EventPublisher
import com.gcs.smarthome.logic.message.BusinessDayOpenEvent
import com.gcs.smarthome.logic.message.ElectricReadingEvent
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
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicBoolean
import javax.annotation.PostConstruct
import javax.annotation.PreDestroy

@Service
class ElectricPowerMonitoring(
    private val dataStream: Flux<out ElectricReading>,
    private val eventPublisher: EventPublisher,
    private val meterService: MeterService
    ) {

    companion object {
        internal val logger = KotlinLogging.logger { }
    }

    private val isInitialized = AtomicBoolean(false)
    private val persistableReadingToken = Disposables.swap()
    private val currentPowerToken = Disposables.swap()

    @EventListener
    fun onNewBusinessDay(day: BusinessDayOpenEvent) {
        logger.info { "business day starting $day" }
        persistableReadingToken.update(initializePersistableDataStream(dataStream, day.businessDayId, day.date))
        if (!isInitialized.get()) {
            isInitialized.set(true)
            currentPowerToken.update(initializeInstantDataStream(dataStream))
        }
    }

    @PostConstruct
    fun onInitialize() {
        logger.info { "starting instant data monitoring..." }
    }

    @PreDestroy
    fun onDestroy() {
        persistableReadingToken.dispose()
        currentPowerToken.dispose()
    }

    private fun initializePersistableDataStream(
        stream: Flux<out ElectricReading>,
        businessDayId: Short,
        currentDate: LocalDate
    ) =
        stream
            .filter { it is PersistableReading }
            .groupBy {
                it as PersistableReading
                Pair(it.alias, it.deviceType)
            }
            .flatMap { groupedReadings ->
                val alias = groupedReadings.key().first
                val deviceType = groupedReadings.key().second
                logger.info { "received first entry for $alias.$deviceType for $currentDate, requesting initial reading..." }
                val lastReadingArgs = DeviceReadingHub.queryLatestDeviceReading(deviceType)
                val readDeltaArgs = DeviceReadingHub.queryDailyDeltaDeviceReading(deviceType, businessDayId.toInt())

                eventPublisher
                    .queryMany(lastReadingArgs, readDeltaArgs)
                    .map { readingAndDelta ->
                        val initialReading = readingAndDelta.t1
                        val delta = readingAndDelta.t2
                        logger.info { "starting to calculate increments for $alias.$deviceType - reference reading $initialReading, today's delta: $delta" }

                        groupedReadings
                            .scan(ReadingAggregationContext(deviceType, initialReading)) { acc, reading -> acc.updateWithReading(reading) }
                            .filter { it.isUpdated }
                    }
                    .flux()
                    .flatMap { it }
            }
            .subscribe(this::processNewReading)
                { err -> logger.error { "failure while processing stream - ${err.message} <${err.javaClass.name}>\n${err.stackTrace}" } }

    private fun processNewReading(reading: ReadingAggregationContext) {
        eventPublisher.broadcastEvent(ElectricReadingEvent(reading.deviceType, reading.alias, reading.currentReading.toDouble(), reading.delta.toDouble()))

        eventPublisher
            .command(DeviceReadingHub.commandStoreReading(reading.deviceType, reading.reading))
            .retryWhen(RetrySpec.fixedDelay(5, Duration.ofSeconds(1)))
            .subscribe(
                { logger.debug { "${reading.reading.id}) stored ${reading.alias}.${reading.deviceType} reading ${reading.reading.formattedReading} with id = $it" } },
                { err -> logger.warn { "${reading.reading.id}) failed to save reading ${reading.deviceType} - ${err.message} <${err.javaClass.name}>" } } )
    }
    private fun initializeInstantDataStream(stream: Flux<out ElectricReading>) =
        stream
            .filter { it is InstantReading }
            .groupBy { it.alias }
            .flatMap {
                logger.info { "configuring gauge for ${it.key()}" }
                val (_, storage, _) = meterService.createOrGetGauge(it.key(), Tags.empty()) { AtomicDouble() }

                val updateAndLog: (AtomicDouble, ElectricReading) -> AtomicDouble = { acc, reading ->
                    acc.set(reading.value.toDouble())
                    logger.debug { "${reading.id}) ${it.key()} => ${reading.formattedReading}" }
                    acc
                }

                it.reduce(storage, updateAndLog)
            }
            .subscribe(
                { },
                { err -> logger.error { "failure while processing stream - ${err.message} <${err.javaClass.name}>\n${err.stackTrace}" } } )

    private data class ReadingAggregationContext(val deviceType: DeviceType, private var _previousReading: BigDecimal) {

        private var _isUpdated = false
        private var _delta = BigDecimal.ZERO
        private lateinit var _reading: ElectricReading

        private var onUpdateFunc: (ElectricReading, BigDecimal) -> Unit = this::doFirstUpdate

        val isUpdated: Boolean
            get() = _isUpdated
        val currentReading: BigDecimal
            get() = _reading.value
        val delta: BigDecimal
            get() = _delta
        val reading: ElectricReading
            get() = _reading
        val alias: String
            get() = _reading.alias

        fun updateWithReading(newReading: ElectricReading): ReadingAggregationContext {
            _delta = newReading.value - _previousReading
            _reading = newReading
            onUpdateFunc(newReading, _delta)
            _previousReading = newReading.value
            return this
        }

        private fun doFirstUpdate(newReading: ElectricReading, delta: BigDecimal) {
            logger.debug { "${newReading.id}) setting up initial reading of ${newReading.alias}.$deviceType with delta = $delta [${newReading.formattedReading}]" }
            onUpdateFunc = this::doUpdate
            _isUpdated = true
        }

        private fun doUpdate(newReading: ElectricReading, delta: BigDecimal) {
            _isUpdated = if (delta > BigDecimal.ZERO) {
                logger.debug { "${newReading.id}) delta since last reading of ${newReading.alias}.$deviceType is $delta [${newReading.formattedReading}]" }
                true
            } else {
                logger.debug { "${newReading.id}) no change in reading of ${newReading.alias}.$deviceType [${newReading.formattedReading}]" }
                false
            }
        }
    }
}

