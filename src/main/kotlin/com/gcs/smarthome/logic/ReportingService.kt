package com.gcs.smarthome.logic

import com.gcs.smarthome.data.model.DeviceType
import com.gcs.smarthome.data.repository.BusinessDayRepository
import com.gcs.smarthome.logic.cqrs.GenericCommand
import com.google.common.util.concurrent.AtomicDouble
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Meter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import mu.KotlinLogging
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap

@Service
class ReportingService(
    private val businessDayRepository: BusinessDayRepository,
    private val cfg : ElectricPowerMonitoringConfig.ConfigurationResult,
    private val meterRegistry: MeterRegistry) {

    private val logger = KotlinLogging.logger {  }
    private val deviceTypes = listOf(
        DeviceType.POWER_METER_EXPORT.ordinal, DeviceType.POWER_METER_IMPORT.ordinal, DeviceType.POWER_METER_PRODUCTION.ordinal)
    private val dailyDateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val meters: ConcurrentMap<Meter.Id, Any> = ConcurrentHashMap()


    @EventListener
    fun onBusinessDayStart(day: BusinessDayHub.BusinessDayAvailableEvent) {
        logger.info { "initializing reporting service for $day" }
        synchronized(meters) {
            updateDailyCounters()
        }
    }

    @EventListener
    fun onBusinessDayEnd(day: BusinessDayHub.BusinessDayCloseEvent) {
        logger.info { "closing business day for $day" }
        synchronized(meters) {
            val currentDayTag = tagForDailyMeter(day.date).first()
            val toRemove = meters
                .keys
                .filter { it.type == Meter.Type.COUNTER }
                .filter { it.tags.any { tag -> tag == currentDayTag } }
                .toList()

            internalRemoveMeters(toRemove)
        }
    }

    protected fun updateDailyCounters() {
        val report = businessDayRepository
            .loadDeviceDailyReport(deviceTypes)
            .groupBy { it.deviceType }

        logger.info { "updateDailyCounters triggered ${LocalDateTime.now()} - loaded report with ${report.keys} deviceTypes" }

        val existingTags = meters
            .keys
            .filter { it.type == Meter.Type.COUNTER }
            .toMutableList()

        report.forEach { (type, freshReports) ->
            cfg.metricsMapping[type]?.let { alias ->
                freshReports.forEach { report ->
                    val id = internalRequestCounter(alias, report.date, report.value.toDouble())
                    existingTags.remove(id)
                }
            }
        }

        internalRemoveMeters(existingTags)

    }

    private interface EventInvoker<TPayload, TResult> {
        fun applyEvent(callback: (TPayload) -> TResult)
    }

    @EventListener
    fun onHandleRequestCounterCommand(cmd: RequestCounterCommand) {
        cmd.applyEvent { internalRequestCounter(it.first, LocalDate.now(), it.second) }
    }

    @EventListener
    fun onHandleRequestGaugeCommand(cmd: RequestGaugeCommand) {
        cmd.applyEvent {
            val key = Meter.Id(it.first, it.third, null, null, Meter.Type.GAUGE)
            meters.computeIfAbsent(key) { id ->
                val storage = it.second
                val gauge = meterRegistry.gauge(id.name, id.tags, storage) { storage.get() }
                logger.info { "request to create gauge $id completed" }
                gauge
            }
            key
        }
    }

    @EventListener
    fun onHandleUpdateCounterCommand(cmd: UpdateCounterCommand) {
        cmd.applyEvent {
            meters.computeIfPresent(it.first) { id, counter ->
                counter as Counter
                counter.increment(it.second)
                logger.debug { "counter $id incremented by ${it.second}" }
                counter
            }
        }
    }

    private fun internalRequestCounter(name: String, date: LocalDate, reading: Double): Meter.Id {
        val key = Meter.Id(name, tagForDailyMeter(date), null, null, Meter.Type.COUNTER)
        meters.computeIfAbsent(key) { id ->
            val counter = meterRegistry.counter(id.name, id.tags)
            counter.increment(reading)
            logger.info { "request to create counter $id with initial value of $reading completed" }
            counter
        }
        return key
    }

    private fun internalRemoveMeters(meterIds: Collection<Meter.Id>) {
        meterIds.forEach {
            logger.info { "removing meter $it" }
            meters.remove(it)
            meterRegistry.remove(it)
        }
    }

    private fun tagForDailyMeter(date: LocalDate) =
        Tags.of("date", date.format(dailyDateFormat))

    class RequestCounterCommand(counterName: String, initialReading: Double) :
        GenericCommand<Pair<String, Double>, Meter.Id>(Pair(counterName, initialReading)), EventInvoker<Pair<String, Double>, Meter.Id> {

        override fun applyEvent(callback: (Pair<String, Double>) -> Meter.Id) = execute(callback)
    }

    class UpdateCounterCommand(counterId: Meter.Id, delta: Double) :
        GenericCommand<Pair<Meter.Id, Double>, Unit>(Pair(counterId, delta)), EventInvoker<Pair<Meter.Id, Double>, Unit> {
        override fun applyEvent(callback: (Pair<Meter.Id, Double>) -> Unit) = execute(callback)
    }

    class RequestGaugeCommand(gaugeName: String, storage: AtomicDouble, tags: Tags) :
        GenericCommand<Triple<String, AtomicDouble, Tags>, Meter.Id>(Triple(gaugeName, storage, tags)), EventInvoker<Triple<String, AtomicDouble, Tags>, Meter.Id> {
        override fun applyEvent(callback: (Triple<String, AtomicDouble, Tags>) -> Meter.Id) = execute(callback)
    }

    companion object {
        fun commandRequestCounter(counterName: String, initialReading: Double) = RequestCounterCommand(counterName, initialReading)
        fun commandUpdateCounter(id: Meter.Id, delta: Double) = UpdateCounterCommand(id, delta)
        fun commandRequestGauge(gaugeName: String, storage: AtomicDouble, tags: Tags = Tags.empty()) = RequestGaugeCommand(gaugeName, storage, tags)
    }
}