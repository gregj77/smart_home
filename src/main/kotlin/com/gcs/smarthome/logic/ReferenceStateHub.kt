package com.gcs.smarthome.logic

import com.gcs.smarthome.data.model.DeviceType
import com.gcs.smarthome.data.model.ReferenceState
import com.gcs.smarthome.data.model.ReferenceType
import com.gcs.smarthome.data.repository.BusinessDayRepository
import com.gcs.smarthome.data.repository.ReferenceStateRepository
import com.gcs.smarthome.logic.cqrs.EventPublisher
import com.gcs.smarthome.logic.cqrs.GenericCommand
import com.gcs.smarthome.web.Reading
import mu.KotlinLogging
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.LocalTime
import javax.transaction.Transactional
import kotlin.math.log

@Service
class ReferenceStateHub(private val repository: ReferenceStateRepository,
                        private val businessDayRepository: BusinessDayRepository,
                        private val eventPublisher: EventPublisher) {
    private val logger = KotlinLogging.logger {  }

    @EventListener
    @Transactional
    fun onStoreNewReading(args: StoreLatestReferenceCommand) {
        args.applyEvent {
            val result = handleLatestReferenceReading(it)

            val allEntries = repository.findAllByReferenceTypeOrderByBusinessDayDesc(result.referenceType)

            if (allEntries.first().id == result.id) {
                logger.info { "broadcasting update event" }
                eventPublisher.broadcastEvent(NewReferenceReading(result.referenceType, result.value, result.importReading?.id, result.exportReading?.id))
            }

            result.id.toInt()
        }
    }

    @EventListener
    fun onNewBusinessDay(args: BusinessDayHub.BusinessDayOpenEvent) {
        ReferenceType
            .values()
            .map { repository.findFirstByReferenceTypeOrderByBusinessDayDesc(it) }
            .filterNot { it.isEmpty }
            .map { it.get() }
            .forEach {
                val event = NewReferenceReading(it.referenceType, it.value, it.importReading?.id, it.exportReading?.id)
                logger.info { "broadcasting $event..." }
                eventPublisher.broadcastEvent(event)
            }
    }

    protected fun handleLatestReferenceReading(reading: Reading): ReferenceState {
        logger.info { "received new reading - $reading" }
        val businessDay = businessDayRepository.findFirstByReferenceEquals(reading.readingDate)
        if (businessDay.isEmpty) {
            logger.warn { "could not find matching business day for ${reading.readingDate}" }
            throw IllegalArgumentException("no business day found for ${reading.readingDate}")
        }

        logger.info { "found business day for ${reading.readingDate} -> ${businessDay.get().id}" }

        val entry = when (reading.type) {
            ReferenceType.POWER_READING -> {
                val readings = businessDay.get()
                    .readings
                    .filter { it.deviceType == DeviceType.POWER_METER_EXPORT || it.deviceType == DeviceType.POWER_METER_IMPORT }
                    .sortedByDescending { it.createdOnTime }

                val exportReading = readings
                    .filter { it.deviceType == DeviceType.POWER_METER_EXPORT }
                    .firstOrNull { it.createdOnTime <= LocalTime.NOON}
                val importReading = readings
                    .filter { it.deviceType == DeviceType.POWER_METER_IMPORT }
                    .firstOrNull{ it.createdOnTime <= LocalTime.NOON }

                logger.info { "storing new reference state reading, import power: ${exportReading?.value} at ${exportReading?.createdOnTime}, export power: ${importReading?.value} at ${importReading?.createdOnTime}" }
                repository.save(ReferenceState(reading.value, reading.type, LocalDateTime.now(), importReading, exportReading, businessDay.get()))
            }
            else -> {
                logger.info { "storing new reference state reading..." }
                repository.save(ReferenceState(reading.value, reading.type, LocalDateTime.now(), null, null, businessDay.get()))
            }
        }

        logger.info { "stored reference state reading : ${entry.id}" }
        return entry
    }


    private interface EventInvoker<TPayload, TResult> {
        fun applyEvent(callback: (TPayload) -> TResult)
    }

    class StoreLatestReferenceCommand(reading: Reading) : GenericCommand<Reading, Int>(reading), EventInvoker<Reading, Int> {
        override fun applyEvent(callback: (Reading) -> Int) = execute(callback)
    }

    companion object {
        fun newReferenceReading(reading: Reading) = StoreLatestReferenceCommand(reading)
    }
}

data class NewReferenceReading(
    val referenceType: ReferenceType,
    val value: BigDecimal,
    val importReadingValue: Int?,
    val exportReadingId: Int?
) {
    fun isPowerReading() = referenceType == ReferenceType.POWER_READING
}