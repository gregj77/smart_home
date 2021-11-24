package com.gcs.smarthome.logic

import com.gcs.smarthome.data.model.BusinessDay
import com.gcs.smarthome.data.repository.BusinessDayRepository
import com.gcs.smarthome.logic.cqrs.EventPublisher
import com.google.common.util.concurrent.AtomicDouble
import mu.KotlinLogging
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.concurrent.atomic.AtomicReference
import javax.transaction.Transactional

@Service
class BusinessDayHub (private val repository: BusinessDayRepository, private val eventPublisher: EventPublisher) {
    private val logger = KotlinLogging.logger {  }
    private val businessDayDuration = AtomicDouble()

    @EventListener(classes = [ApplicationReadyEvent::class])
    fun initialize() {
        initNewBusinessDay()
        eventPublisher.broadcastEvent(ReportingService.commandRequestGauge("business_day_up", businessDayDuration))
        businessDayDuration.set(LocalTime.now().toSecondOfDay().toDouble())
    }

    @Scheduled(cron = "1 0 0 * * *")
    protected fun initNewBusinessDay() {
        val (id, date) = createOrGetCurrentBusinessDay()
        logger.info { "broadcasting business day information for $date} = $id" }
        eventPublisher.broadcastEvent(BusinessDayAvailableEvent(id, date))
        businessDayDuration.set(LocalTime.now().toSecondOfDay().toDouble())
    }

    @Scheduled(cron = "59 59 23 * * *")
    protected fun closeBusinessDay() {
        val (id, date) = createOrGetCurrentBusinessDay()
        logger.info { "broadcasting close of business day information for $date} = $id" }
        eventPublisher.broadcastEvent(BusinessDayCloseEvent(id, date))
    }

    @Scheduled(cron = "55 * * * * *")
    protected fun trackBusinessDayProgress() {
        businessDayDuration.set(LocalTime.now().toSecondOfDay().toDouble())
    }

    @Transactional
    protected fun createOrGetCurrentBusinessDay(): Pair<Short, LocalDate> {
        val businessDay = repository.findFirstByReferenceEquals(LocalDate.now())
        val result = if (businessDay.isEmpty) {
            val newBusinessDay = BusinessDay(LocalDate.now())
            logger.info { "no business day found for ${newBusinessDay.reference} - creating new one..." }
            repository.save(newBusinessDay)
        } else {
            logger.info { "business day found for ${businessDay.get().reference} - reusing..." }
            businessDay.get()
        }

        return Pair(result.id, result.reference)
    }

    data class BusinessDayAvailableEvent(val businessDayId: Short, val date: LocalDate)
    data class BusinessDayCloseEvent(val businessDayId: Short, val date: LocalDate)
}