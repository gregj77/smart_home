package com.gcs.smarthome.logic

import com.gcs.smarthome.data.model.DeviceType
import com.gcs.smarthome.data.model.ReferenceState
import com.gcs.smarthome.data.model.ReferenceType
import com.gcs.smarthome.data.repository.BusinessDayRepository
import com.gcs.smarthome.data.repository.ReferenceStateRepository
import com.gcs.smarthome.logic.cqrs.EventPublisher
import com.gcs.smarthome.logic.cqrs.GenericCommand
import com.gcs.smarthome.logic.message.BusinessDayOpenEvent
import com.gcs.smarthome.web.Reading
import mu.KotlinLogging
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.LocalTime
import javax.transaction.Transactional

@Service
class ReferenceStateHub(private val repository: ReferenceStateRepository,
                        private val businessDayRepository: BusinessDayRepository,
                        private val eventPublisher: EventPublisher,
                        private val localDateTimeSupplier: () -> LocalDateTime) {
    private val logger = KotlinLogging.logger {  }

    private var businessDayId: Short = 0

    @EventListener
    @Transactional
    fun onStoreLatestReferenceReadingCommand(args: StoreLatestReferenceCommand) {
        args.handle {
            val id = handleLatestReferenceReading(it)?.let { result ->

                val allEntries = repository.findAllByReferenceTypeOrderByBusinessDayDesc(result.referenceType)

                if (allEntries.first().id == result.id) {
                    val event = NewReferenceReading(
                        result.createdOn,
                        result.referenceType,
                        result.value,
                        result.importReading?.id,
                        result.importReading?.value,
                        result.exportReading?.id,
                        result.exportReading?.value
                    )
                    logger.info { "broadcasting update event $event" }
                    eventPublisher.broadcastEvent(event)
                }

                result.id.toInt()
            }
            id ?: -1
        }
    }

    @EventListener
    fun onNewBusinessDay(args: BusinessDayOpenEvent) {
        businessDayId = args.businessDayId
        ReferenceType
            .values()
            .map { repository.findFirstByReferenceTypeOrderByBusinessDayDesc(it) }
            .filterNot { it.isEmpty }
            .map { it.get() }
            .forEach {
                val event = NewReferenceReading(it.createdOn, it.referenceType, it.value, it.importReading?.id, it.importReading?.value, it.exportReading?.id, it.exportReading?.value)
                logger.info { "broadcasting $event..." }
                eventPublisher.broadcastEvent(event)
            }
    }

    private fun handleLatestReferenceReading(reading: Reading): ReferenceState? {
        logger.info { "received new reading - $reading" }
        val businessDay = businessDayRepository.findFirstByReferenceEquals(reading.readingDate)
        if (businessDay.isEmpty) {
            logger.warn { "could not find matching business day for ${reading.readingDate}" }
            return null
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
                repository.save(ReferenceState(reading.value, reading.type, localDateTimeSupplier(), importReading, exportReading, businessDay.get()))
            }
            else -> {
                logger.info { "storing new reference state reading..." }
                repository.save(ReferenceState(reading.value, reading.type, localDateTimeSupplier(), null, null, businessDay.get()))
            }
        }

        logger.info { "stored reference state reading : ${entry.id}" }
        return entry
    }
    class StoreLatestReferenceCommand(reading: Reading) : GenericCommand<Reading, Int>(reading)

    companion object {
        fun newReferenceReading(reading: Reading) = StoreLatestReferenceCommand(reading)
    }
}

data class NewReferenceReading(
    val readingDate: LocalDateTime,
    val referenceType: ReferenceType,
    val value: BigDecimal,
    val importReadingId: Int?,
    val importReadingValue: BigDecimal?,
    val exportReadingId: Int?,
    val exportReadingValue: BigDecimal?
) {
    fun isPowerReading() = referenceType == ReferenceType.POWER_READING
}