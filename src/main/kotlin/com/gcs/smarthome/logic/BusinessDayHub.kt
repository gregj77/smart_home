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
import java.time.LocalTime
import java.time.ZoneId
import javax.annotation.PreDestroy

@Service
class BusinessDayHub (private val repository: BusinessDayRepository,
                      private val eventPublisher: EventPublisher,
                      private val meterService: MeterService,
                      private val txManager: PlatformTransactionManager) {
    private val logger = KotlinLogging.logger {  }
    private var token: Disposable = Disposables.disposed()

    @EventListener(classes = [ApplicationReadyEvent::class])
    fun initialize() {
        initNewBusinessDay()
        val (_, storage, _) = meterService.createOrGetGauge(BUSINESS_DAY_UP_GAUGE, Tags.empty()) { AtomicDouble(LocalTime.now(ZoneId.systemDefault()).toSecondOfDay().toDouble()) }
        token = Flux
            .interval(Duration.ofSeconds(0), Duration.ofSeconds(5L), Schedulers.parallel())
            .map { LocalTime.now(ZoneId.systemDefault()).toSecondOfDay().toDouble() }
            .subscribe { storage.set(it) }
    }

    @PreDestroy
    fun destroy() {
        token.dispose()
    }

    @Scheduled(cron = "1 0 0 * * *")
    protected fun initNewBusinessDay() {
        val (id, date) = createOrGetCurrentBusinessDay()
        logger.info { "broadcasting business day information for $date} = $id" }
        eventPublisher.broadcastEvent(BusinessDayOpenEvent(id, date))
    }

    @Scheduled(cron = "59 59 23 * * *")
    protected fun closeBusinessDay() {
        val (id, date) = createOrGetCurrentBusinessDay()
        logger.info { "broadcasting close of business day information for $date} = $id" }
        eventPublisher.broadcastEvent(BusinessDayCloseEvent(id, date))
    }

    private fun createOrGetCurrentBusinessDay(): Pair<Short, LocalDate> {
        txManager.getTransaction(TransactionDefinition.withDefaults()).let {
            val businessDay = repository.findFirstByReferenceEquals(LocalDate.now())
            val result = if (businessDay.isEmpty) {
                val newBusinessDay = BusinessDay(LocalDate.now())
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