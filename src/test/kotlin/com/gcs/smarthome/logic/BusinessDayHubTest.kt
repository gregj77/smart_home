package com.gcs.smarthome.logic

import com.gcs.smarthome.config.EventingConfiguration
import com.gcs.smarthome.data.model.BusinessDay
import com.gcs.smarthome.data.repository.BusinessDayRepository
import com.gcs.smarthome.testutils.VirtualTaskScheduler
import com.ninjasquad.springmockk.MockkBean
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.*
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.InstanceOfAssertFactories
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.ApplicationListener
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.context.event.EventListener
import org.springframework.scheduling.TaskScheduler
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.junit.jupiter.SpringExtension
import org.springframework.transaction.PlatformTransactionManager
import reactor.test.scheduler.VirtualTimeScheduler
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.*

@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [BusinessDayHubTest.ScopedConfig::class])
class BusinessDayHubTest  {

    @Autowired
    lateinit var victim: BusinessDayHub

    @MockkBean
    lateinit var repository: BusinessDayRepository

    @MockkBean
    lateinit var txManager: PlatformTransactionManager

    @Autowired
    lateinit var eventPublisher: ApplicationEventPublisher

    @Autowired
    lateinit var testEvents: TestEventListener

    @Autowired
    lateinit var timeScheduler: VirtualTimeScheduler

    @Test
    fun `business day hub will produce day start & end events as required`() {
        // setup infrastructure
        every { txManager.getTransaction(any()) } returns mockk(relaxed = true)
        every { txManager.commit(any()) } returns Unit

        val dateArg = CapturingSlot<LocalDate>()
        val businessDay = mutableListOf<BusinessDay>()

        every { repository.findFirstByReferenceEquals(capture(dateArg)) } returns
                Optional.empty() andThenAnswer
                { Optional.of(businessDay[0]) } andThen
                Optional.empty() andThenAnswer
                { Optional.of(businessDay[1]) } andThenThrows
                NotImplementedError("this is not expected!")

        every {
            hint(BusinessDay::class)
            repository.save(capture(businessDay)) } answers { businessDay.lastOrNull()!! }

        // raise application ready event
        val event = mockk<ApplicationReadyEvent>(relaxed = true)
        eventPublisher.publishEvent(event)

        assertThat(victim.secondsSinceDayStart).isEqualTo(LocalTime.of(13, 0, 0).toSecondOfDay().toLong())

        // advance time to current business day is closed, new one is created and completed
        timeScheduler.advanceTimeBy(
            Duration
                .ofHours(10)
                .plusMinutes(59)
                .plusSeconds(59)
                .plusMillis(1)
                .plusDays(1))

        assertThat(testEvents.events).hasSize(5)
        assertThat(testEvents.events[0]).isInstanceOf(ApplicationReadyEvent::class.java)
        assertThat(testEvents.events[1])
            .isInstanceOf(BusinessDayHub.BusinessDayOpenEvent::class.java)
            .asInstanceOf(InstanceOfAssertFactories.type(BusinessDayHub.BusinessDayOpenEvent::class.java))
            .extracting(BusinessDayHub.BusinessDayOpenEvent::date)
            .isEqualTo(LocalDate.of(2022, 1, 1))
        assertThat(testEvents.events[2])
            .isInstanceOf(BusinessDayHub.BusinessDayCloseEvent::class.java)
            .asInstanceOf(InstanceOfAssertFactories.type(BusinessDayHub.BusinessDayCloseEvent::class.java))
            .extracting(BusinessDayHub.BusinessDayCloseEvent::date)
            .isEqualTo(LocalDate.of(2022, 1, 1))

        assertThat(testEvents.events[3])
            .isInstanceOf(BusinessDayHub.BusinessDayOpenEvent::class.java)
            .asInstanceOf(InstanceOfAssertFactories.type(BusinessDayHub.BusinessDayOpenEvent::class.java))
            .extracting(BusinessDayHub.BusinessDayOpenEvent::date)
            .isEqualTo(LocalDate.of(2022, 1, 2))
        assertThat(testEvents.events[4])
            .isInstanceOf(BusinessDayHub.BusinessDayCloseEvent::class.java)
            .asInstanceOf(InstanceOfAssertFactories.type(BusinessDayHub.BusinessDayCloseEvent::class.java))
            .extracting(BusinessDayHub.BusinessDayCloseEvent::date)
            .isEqualTo(LocalDate.of(2022, 1, 2))

        assertThat(victim.secondsSinceDayStart).isEqualTo(LocalTime.of(23, 59, 55).toSecondOfDay().toLong())

        verify(exactly = 2) {
            hint(BusinessDay::class)
            repository.save(any())
        }
        verify(exactly = 4) { repository.findFirstByReferenceEquals(any()) }

        confirmVerified(repository)
    }

    @Configuration
    @EnableScheduling
    @Import(value = [BusinessDayHub::class, SmartHomeTaskScheduler::class, EventingConfiguration::class, MeterService::class])
    class ScopedConfig {
        val testTime = OffsetDateTime.parse("2022-01-01T12:00:00Z", DateTimeFormatter.ISO_DATE_TIME).toInstant()
        final val scheduler = VirtualTimeScheduler.create()
        final val taskScheduler = VirtualTaskScheduler(scheduler)

        @Bean
        fun timeScheduler() : VirtualTimeScheduler = scheduler

        @Bean
        fun taskScheduler(): TaskScheduler {
            TimeZone.setDefault(TimeZone.getTimeZone("CET"))
            scheduler.advanceTimeTo(testTime)
            return taskScheduler
        }

        @Bean
        fun meterRegistry(): MeterRegistry = SimpleMeterRegistry()

        @Bean
        fun myListener() : TestEventListener = TestEventListener()

        @Bean
        fun currentTime(smartHomeTaskScheduler: SmartHomeTaskScheduler) : () -> LocalDate {
            return { smartHomeTaskScheduler.date }
        }
    }

    class TestEventListener : ApplicationListener<ApplicationReadyEvent> {

        val events = mutableListOf<Any>()

        override fun onApplicationEvent(event: ApplicationReadyEvent) {
            events.add(event)
        }

        @EventListener
        fun onBusinessDayStart(day: BusinessDayHub.BusinessDayOpenEvent) {
            events.add(day)
        }

        @EventListener
        fun onBusinessDayEnd(day: BusinessDayHub.BusinessDayCloseEvent) {
            events.add(day)
        }
    }
}