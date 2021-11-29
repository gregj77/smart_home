package com.gcs.smarthome.logic

import com.google.common.util.concurrent.AtomicDouble
import io.micrometer.core.instrument.*
import mu.KotlinLogging
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap
import java.util.function.Predicate

@Component
class MeterService(private val meterRegistry: MeterRegistry) {
    private val logger = KotlinLogging.logger {  }
    private val dailyDateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val meters: ConcurrentMap<Meter.Id, Any> = ConcurrentHashMap()

    fun cleanupDailyMeters(date: LocalDate) {
        synchronized(meters) {
            val currentDayTag = tagForDailyMeter(date).first()
            val toRemove = meters
                .keys
                .filter { filterByTag(currentDayTag).test(it) }
                .toList()

            removeMeters(toRemove)
        }
    }

    fun listMeterIdsBy(filter: Predicate<Meter.Id>) : MutableList<Meter.Id> = meters.keys.filter { filter.test(it) }.toMutableList()

    fun createOrGetCounter(counterName: String, tags: Tags, initialReading: Double): Triple<Meter.Id, Counter, Boolean> {
        val key = Meter.Id(counterName, tags, null, null, Meter.Type.COUNTER)
        var createdNew = false
        val counter = meters.computeIfAbsent(key) { id ->
            val counter = meterRegistry.counter(id.name, id.tags)
            counter.increment(initialReading)
            logger.info { "request to create counter $id with initial value of $initialReading completed" }
            createdNew = true
            counter
        } as Counter

        return Triple(key, counter, createdNew)
    }

    fun createOrGetGauge(gaugeName: String, tags: Tags, storageSupplier: () -> AtomicDouble): Triple<Meter.Id, AtomicDouble, Boolean> {
        val key = Meter.Id(gaugeName, tags, null, null, Meter.Type.GAUGE)
        var createdNew = false
        val gauge = meters.computeIfAbsent(key) { id ->
            val storage = storageSupplier()
            val gauge = meterRegistry.gauge(id.name, id.tags, storage) { storage.get() }
            logger.info { "request to create gauge $id completed" }
            createdNew = true
            gauge
        } as AtomicDouble
        return Triple(key, gauge, createdNew)
    }

    fun <X, T: Triple<Meter.Id, X, Boolean>> withDayTag( day: LocalDate, callWithContext: (Tags) -> Triple<Meter.Id, X, Boolean> ): Triple<Meter.Id, X, Boolean> {
        val initialTags = tagForDailyMeter(day)
        return callWithContext(initialTags)
    }

    fun <X, T: Triple<Meter.Id, X, Boolean>> withCurrentDayTag( callWithContext: (Tags) -> Triple<Meter.Id, X, Boolean> ): Triple<Meter.Id, X, Boolean> {
        return withDayTag<X, T>(LocalDate.now(), callWithContext);
    }

    private fun tagForDailyMeter(date: LocalDate) =
        Tags.of("date", date.format(dailyDateFormat))

    fun removeMeters(meterIds: Collection<Meter.Id>) {
        synchronized (meters) {
            meterIds.forEach {
                logger.info { "removing meter $it" }
                meters.remove(it)
                meterRegistry.remove(it)
            }
        }
    }

    companion object {
        const val tagTypeName = "tagType"
        const val tagTypeDailyValue = "daily"
        const val tagTypeMonthlyValue = "monthly"
        const val tagTypeYearlyValue = "yearly"

        fun filterByTag(toCompare: Tag) : Predicate<Meter.Id> {
            return Predicate<Meter.Id> { id -> id.tags.any { it == toCompare } }
        }

        fun filterByDailyTag() : Predicate<Meter.Id> {
            return Predicate<Meter.Id> { id -> id.tags.any { it.key == tagTypeName && it.value == tagTypeDailyValue }}
        }

        fun filterByMonthlyTag() : Predicate<Meter.Id> {
            return Predicate<Meter.Id> { id -> id.tags.any { it.key == tagTypeName && it.value == tagTypeMonthlyValue }}
        }

        fun filterByYearlyTag() : Predicate<Meter.Id> {
            return Predicate<Meter.Id> { id -> id.tags.any { it.key == tagTypeName && it.value == tagTypeYearlyValue }}
        }

        fun filterByType(type: Meter.Type) : Predicate<Meter.Id> {
            return Predicate<Meter.Id> { id -> id.type == type}
        }
    }
}