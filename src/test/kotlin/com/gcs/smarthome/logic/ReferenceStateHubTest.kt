package com.gcs.smarthome.logic

import com.gcs.smarthome.config.EventingConfiguration
import com.gcs.smarthome.config.MonitoringConfiguration
import com.gcs.smarthome.config.SchedulerConfiguration
import com.gcs.smarthome.data.model.*
import com.gcs.smarthome.data.repository.BusinessDayRepository
import com.gcs.smarthome.data.repository.ReferenceStateRepository
import com.gcs.smarthome.logic.cqrs.EventPublisher
import com.gcs.smarthome.logic.message.BusinessDayOpenEvent
import com.gcs.smarthome.web.Reading
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.event.EventListener
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.junit.jupiter.SpringExtension
import reactor.test.scheduler.VirtualTimeScheduler
import java.math.BigDecimal
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.*

@ExtendWith(SpringExtension::class)
@EnableConfigurationProperties(value = [MonitoringConfiguration::class])
@ContextConfiguration(initializers = [ConfigDataApplicationContextInitializer::class], classes = [ReferenceStateHubTest.ScopedConfig::class])
internal class ReferenceStateHubTest {

    @Autowired
    private lateinit var victim: ReferenceStateHub

    @Autowired
    private lateinit var hub: TestHub

    @Autowired
    private lateinit var businessDayRepository: BusinessDayRepository

    @Autowired
    private lateinit var referenceStateRepository: ReferenceStateRepository

    @Autowired
    private lateinit var localDateTime: () -> LocalDateTime

    @Autowired
    private lateinit var eventPublisher: EventPublisher

    @Test
    @DirtiesContext
    fun `when initialized will propagate events with most recent readings`() {

        every { referenceStateRepository.findFirstByReferenceTypeOrderByBusinessDayDesc(eq(ReferenceType.POWER_READING)) } answers {
            Optional.of(ReferenceState(BigDecimal.valueOf(1.0), ReferenceType.POWER_READING, localDateTime().minusDays(1L), null, null, BusinessDay(localDateTime().minusDays(1L).toLocalDate())))
        }

        every { referenceStateRepository.findFirstByReferenceTypeOrderByBusinessDayDesc(eq(ReferenceType.GAS_READING)) } answers {
            Optional.of(ReferenceState(BigDecimal.valueOf(2.0), ReferenceType.GAS_READING, localDateTime().minusDays(1L), null, null, BusinessDay(localDateTime().minusDays(1L).toLocalDate())))
        }

        every { referenceStateRepository.findFirstByReferenceTypeOrderByBusinessDayDesc(eq(ReferenceType.GARDEN_WATER_READING)) } answers {
            Optional.of(ReferenceState(BigDecimal.valueOf(3.0), ReferenceType.GARDEN_WATER_READING, localDateTime().minusDays(1L), null, null, BusinessDay(localDateTime().minusDays(1L).toLocalDate())))
        }

        every { referenceStateRepository.findFirstByReferenceTypeOrderByBusinessDayDesc(eq(ReferenceType.HOME_WATER_READING)) } answers {
            Optional.of(ReferenceState(BigDecimal.valueOf(3.0), ReferenceType.HOME_WATER_READING, localDateTime().minusDays(1L), null, null, BusinessDay(localDateTime().minusDays(1L).toLocalDate())))
        }

        victim.onNewBusinessDay(BusinessDayOpenEvent(1, localDateTime().toLocalDate()))

        assertThat(hub.events).hasSize(4)
        assertThat(hub.events.map { it.referenceType }).containsExactlyInAnyOrder(ReferenceType.HOME_WATER_READING, ReferenceType.GAS_READING, ReferenceType.GARDEN_WATER_READING, ReferenceType.POWER_READING)
    }

    @ParameterizedTest
    @DirtiesContext
    @MethodSource("referenceReadingTypes")
    fun `storing new reading will save entry in DB and will broadcast proper event`(readingType: ReferenceType) {
        every { referenceStateRepository.findFirstByReferenceTypeOrderByBusinessDayDesc(any()) } returns Optional.empty()

        victim.onNewBusinessDay(BusinessDayOpenEvent(1, localDateTime().toLocalDate()))

        every { businessDayRepository.findFirstByReferenceEquals(eq(localDateTime().toLocalDate())) } returns Optional.of(BusinessDay(localDateTime().toLocalDate()))
        every {
            hint(ReferenceState::class)
            referenceStateRepository.save(any())
        } returnsArgument 0
        every { referenceStateRepository.findAllByReferenceTypeOrderByBusinessDayDesc(eq(readingType)) } answers {
            listOf(ReferenceState(BigDecimal.valueOf(1.0), readingType, localDateTime(), null, null, BusinessDay(localDateTime().toLocalDate())))
        }

        eventPublisher.command(ReferenceStateHub.newReferenceReading(Reading(readingType, localDateTime().toLocalDate(), BigDecimal.valueOf(1.0)))).block(
            Duration.ofSeconds(10))

        assertThat(hub.events).hasSize(1)
        assertThat(hub.events[0].referenceType).isEqualTo(readingType)
        assertThat(hub.events[0].value).isEqualTo(BigDecimal.valueOf(1.0))

        verify(exactly = 1) {
            hint(ReferenceState::class)
            referenceStateRepository.save(any())
        }
        verify(exactly = 4) {
            referenceStateRepository.findFirstByReferenceTypeOrderByBusinessDayDesc(any())
        }
        verify(exactly = 1) {
            referenceStateRepository.findAllByReferenceTypeOrderByBusinessDayDesc(eq(readingType))
        }
        verify {
            referenceStateRepository.hashCode()
        }

        confirmVerified(referenceStateRepository)
    }

    @DirtiesContext
    @Test
    fun `attempt to save reading on non existing business day will fail`() {
        every { referenceStateRepository.findFirstByReferenceTypeOrderByBusinessDayDesc(any()) } returns Optional.empty()

        victim.onNewBusinessDay(BusinessDayOpenEvent(1, localDateTime().toLocalDate()))

        every { businessDayRepository.findFirstByReferenceEquals(eq(localDateTime().toLocalDate().minusDays(1L))) } returns Optional.empty()

        val result = eventPublisher
            .command(ReferenceStateHub.newReferenceReading(Reading(ReferenceType.POWER_READING, localDateTime().minusDays(1).toLocalDate(), BigDecimal.valueOf(1.0))))
            .block(Duration.ofSeconds(10))

        assertThat(result).isEqualTo(-1)
        assertThat(hub.events).hasSize(0)
    }

    @DirtiesContext
    @Test
    fun `storing power reading will reference existing values`() {
        every { referenceStateRepository.findFirstByReferenceTypeOrderByBusinessDayDesc(any()) } returns Optional.empty()

        victim.onNewBusinessDay(BusinessDayOpenEvent(1, localDateTime().toLocalDate()))

        val currentDay = mockk<BusinessDay>()
        every { currentDay.reference } returns localDateTime().toLocalDate()
        every { currentDay.readings } answers {
            listOf(
                DeviceReading(DeviceType.POWER_METER_IMPORT, BigDecimal.valueOf(1.0), localDateTime().minusSeconds(1).toLocalTime(), 0),
                DeviceReading(DeviceType.POWER_METER_IMPORT, BigDecimal.valueOf(2.0), localDateTime().toLocalTime(), 0),
                DeviceReading(DeviceType.POWER_METER_IMPORT, BigDecimal.valueOf(3.0), localDateTime().plusSeconds(1).toLocalTime(), 0),
                DeviceReading(DeviceType.POWER_METER_EXPORT, BigDecimal.valueOf(4.0), localDateTime().minusHours(6).toLocalTime(), 0),
                DeviceReading(DeviceType.POWER_METER_EXPORT, BigDecimal.valueOf(5.0), localDateTime().toLocalTime(), 0),
                DeviceReading(DeviceType.POWER_METER_EXPORT, BigDecimal.valueOf(6.0), localDateTime().plusHours(6).toLocalTime(), 0),
                DeviceReading(DeviceType.HOME_WATER_METER_IMPORT, BigDecimal.valueOf(7.0), localDateTime().toLocalTime(), 0),
                DeviceReading(DeviceType.GARDEN_WATER_METER_IMPORT, BigDecimal.valueOf(8.0), localDateTime().toLocalTime(), 0),
                DeviceReading(DeviceType.GAS_METER_IMPORT, BigDecimal.valueOf(9.0), localDateTime().toLocalTime(), 0),
            )
        }

        every { businessDayRepository.findFirstByReferenceEquals(eq(localDateTime().toLocalDate())) } returns Optional.of(currentDay)
        every {
            hint(ReferenceState::class)
            referenceStateRepository.save(any())
        } returnsArgument 0
        every { referenceStateRepository.findAllByReferenceTypeOrderByBusinessDayDesc(eq(ReferenceType.POWER_READING)) } answers {
            listOf(ReferenceState(BigDecimal.valueOf(1.0), ReferenceType.POWER_READING, localDateTime(), null, null, BusinessDay(localDateTime().toLocalDate())))
        }

        eventPublisher.command(ReferenceStateHub.newReferenceReading(Reading(ReferenceType.POWER_READING, localDateTime().toLocalDate(), BigDecimal.valueOf(1.0)))).block(
            Duration.ofSeconds(10))

        assertThat(hub.events).hasSize(1)
        assertThat(hub.events[0].referenceType).isEqualTo(ReferenceType.POWER_READING)
        assertThat(hub.events[0].value).isEqualTo(BigDecimal.valueOf(1.0))
        assertThat(hub.events[0].exportReadingValue!!.toDouble()).isEqualTo(5.0)
        assertThat(hub.events[0].importReadingValue!!.toDouble()).isEqualTo(2.0)

        verify(exactly = 1) {
            hint(ReferenceState::class)
            referenceStateRepository.save(any())
        }
        verify(exactly = 4) {
            referenceStateRepository.findFirstByReferenceTypeOrderByBusinessDayDesc(any())
        }
        verify(exactly = 1) {
            referenceStateRepository.findAllByReferenceTypeOrderByBusinessDayDesc(eq(ReferenceType.POWER_READING))
        }
        verify {
            referenceStateRepository.hashCode()
        }

        confirmVerified(referenceStateRepository)
    }


    companion object {
        @JvmStatic
        fun referenceReadingTypes() = listOf(
            Arguments.of(ReferenceType.GAS_READING),
            Arguments.of(ReferenceType.GARDEN_WATER_READING),
            Arguments.of(ReferenceType.HOME_WATER_READING)
        )
    }

    @TestConfiguration
    @Import(value = [
        ReferenceStateHub::class,
        SchedulerConfiguration::class,
        EventingConfiguration::class,
    ])
    class ScopedConfig {
        private var referenceStateRepository = mockk<ReferenceStateRepository>()
        private var businessDayRepository = mockk<BusinessDayRepository>()
        private var scheduler: VirtualTimeScheduler = VirtualTimeScheduler.create()

        @Bean
        fun referenceStateRepository() = referenceStateRepository

        @Bean
        fun businessDayRepository() = businessDayRepository

        @Bean
        fun scheduler(tz: ZoneId) : VirtualTimeScheduler {
            scheduler.advanceTimeTo(LocalDateTime.of(2022, 1, 31, 11, 0,0).toInstant(ZoneOffset.UTC))
            return scheduler
        }

        @Bean
        fun testHub() = TestHub()
    }

    class TestHub {
        val events = mutableListOf<NewReferenceReading>()

        @EventListener
        fun onEvent(args: NewReferenceReading) {
            events.add(args)
        }

    }
}