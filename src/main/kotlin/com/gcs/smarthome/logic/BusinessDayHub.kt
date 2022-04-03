package com.gcs.smarthome.logic

import com.gcs.smarthome.data.model.BusinessDay
import com.gcs.smarthome.data.repository.BusinessDayRepository
import com.gcs.smarthome.logic.cqrs.EventPublisher
import com.google.common.util.concurrent.AtomicDouble
import io.micrometer.core.instrument.Tags
import mu.KotlinLogging
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import reactor.core.Disposable
import reactor.core.Disposables
import reactor.core.publisher.Flux
import reactor.core.scheduler.Schedulers
import java.time.Duration
import java.time.LocalDate
import javax.annotation.PreDestroy

@Service
class BusinessDayHub (private val repository: BusinessDayRepository,
                      private val eventPublisher: EventPublisher,
                      private val meterService: MeterService,
                      private val txManager: PlatformTransactionManager,
                      private val smartHomeTaskScheduler: SmartHomeTaskScheduler) {

    private val logger = KotlinLogging.logger {  }
    private val tokens: Disposable.Composite = Disposables.composite()
    private val secondsInDay = AtomicDouble()

    val secondsSinceDayStart: Long
        get() = secondsInDay.toLong()

    @EventListener(classes = [ApplicationReadyEvent::class])
    fun initialize() {

        secondsInDay.set(smartHomeTaskScheduler.time.toSecondOfDay().toDouble())

        val (_, storage, _) = meterService.createOrGetGauge(BUSINESS_DAY_UP_GAUGE, Tags.empty()) { secondsInDay }

        val subscriptions = mutableListOf<Disposable>()

        subscriptions += Flux
            .interval(Duration.ofSeconds(0), Duration.ofSeconds(5L), Schedulers.parallel())
            .map { smartHomeTaskScheduler.time.toSecondOfDay().toDouble() }
            .subscribe { storage.set(it) }

        subscriptions += smartHomeTaskScheduler
            .schedule("1 0 0 * * *", LocalDate::class, true)
            .subscribe(this::initNewBusinessDay)

        subscriptions += smartHomeTaskScheduler
            .schedule("59 59 23 * * *", LocalDate::class)
            .subscribe(this::closeBusinessDay)

        tokens.addAll(subscriptions)
    }

    @PreDestroy
    fun destroy() {
        tokens.dispose()
    }

    private fun initNewBusinessDay(currentDay: LocalDate) {
        val (id, date) = createOrGetCurrentBusinessDay(currentDay)
        logger.info { "broadcasting business day information for $date} = $id" }
        eventPublisher.broadcastEvent(BusinessDayOpenEvent(id, date))
    }

    private fun closeBusinessDay(currentDay: LocalDate) {
        val (id, date) = createOrGetCurrentBusinessDay(currentDay)
        logger.info { "broadcasting close of business day information for $date} = $id" }
        eventPublisher.broadcastEvent(BusinessDayCloseEvent(id, date))
    }

    private fun createOrGetCurrentBusinessDay(currentDay: LocalDate): Pair<Short, LocalDate> {
        txManager.getTransaction(TransactionDefinition.withDefaults()).let {
            val businessDay = repository.findFirstByReferenceEquals(currentDay)
            val result = if (businessDay.isEmpty) {
                val newBusinessDay = BusinessDay(currentDay)
                logger.info { "no business day found for ${newBusinessDay.reference} - creating new one..." }
                repository.save(newBusinessDay)
            } else {
                logger.info { "business day found for ${businessDay.get().reference} - reusing..." }
                businessDay.get()
            }
            txManager.commit(it)
            return Pair(result.id, result.reference)
        }
    }

    data class BusinessDayOpenEvent(val businessDayId: Short, val date: LocalDate)
    data class BusinessDayCloseEvent(val businessDayId: Short, val date: LocalDate)

    companion object {
        const val BUSINESS_DAY_UP_GAUGE = "business_day_up"
    }
}