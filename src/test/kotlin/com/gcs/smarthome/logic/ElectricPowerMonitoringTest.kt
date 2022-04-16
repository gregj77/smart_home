package com.gcs.smarthome.logic

import com.gcs.smarthome.config.EventingConfiguration
import com.gcs.smarthome.data.model.BusinessDay
import com.gcs.smarthome.data.model.DeviceType
import com.gcs.smarthome.logic.cqrs.EventPublisher
import com.gcs.smarthome.logic.message.BusinessDayOpenEvent
import com.gcs.smarthome.logic.message.ElectricReadingEvent
import com.google.common.util.concurrent.AtomicDouble
import com.ninjasquad.springmockk.MockkBean
import io.micrometer.core.instrument.MeterRegistry
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.ThrowingConsumer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.context.event.EventListener
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.junit.jupiter.SpringExtension
import reactor.core.publisher.Flux
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

@ExtendWith(SpringExtension::class)
@ContextConfiguration( classes = [ ElectricPowerMonitoringTest.ScopedConfig::class ])
internal class ElectricPowerMonitoringTest {

    private val businessDay = BusinessDay(LocalDate.of(2022, 1, 1))
    private val openEvent = BusinessDayOpenEvent(1, businessDay.reference)

    @Autowired
    lateinit var victimFactory: (Flux<out ElectricReading>, BusinessDayOpenEvent) -> ElectricPowerMonitoring

    @Autowired
    lateinit var testHub: TestHub

    lateinit var victim: ElectricPowerMonitoring

    @MockkBean
    lateinit var registry: MeterRegistry

    @AfterEach
    fun tearDown() {
        victim.onDestroy()
        testHub.readingEvents.clear()
        testHub.onHandleStoreLatestReading = null
        testHub.onHandleLatestDeviceReading = null
        testHub.onHandleDailyDeltaDeviceReading = null
    }

    @Test
    fun `sequence of single type instant readings will update corresponding gauge`() {
        val readings = arrayListOf<ElectricReading>(
            ElectricReading.InstantProductionPower(BigDecimal.valueOf(1L), "KWh", LocalDateTime.now(), "simple"),
            ElectricReading.InstantProductionPower(BigDecimal.valueOf(2L), "KWh", LocalDateTime.now(), "simple"),
            ElectricReading.InstantProductionPower(BigDecimal.valueOf(3L), "KWh", LocalDateTime.now(), "simple"),
        )

        val meterMock = mockk<AtomicDouble>()
        every { registry.hint(AtomicDouble::class).gauge(any(), any(), any<AtomicDouble>(), any()) } answers { meterMock }
        val readingValues = mutableListOf<Double>()
        every { meterMock.set(capture(readingValues)) } just Runs

        victim = victimFactory(Flux.fromIterable(readings), openEvent)

        assertThat(readingValues)
            .hasSize(3)
            .containsExactly(1.0, 2.0, 3.0)
    }

    @Test
    fun `sequence of two types instant readings will update proper gauge`() {
        val readings = arrayListOf<ElectricReading>(
            ElectricReading.InstantProductionPower(BigDecimal.valueOf(1L), "kW", LocalDateTime.now(), "first"),
            ElectricReading.InstantProductionPower(BigDecimal.valueOf(4L), "kW", LocalDateTime.now(), "second"),
            ElectricReading.InstantProductionPower(BigDecimal.valueOf(2L), "kW", LocalDateTime.now(), "first"),
            ElectricReading.InstantProductionPower(BigDecimal.valueOf(5L), "kW", LocalDateTime.now(), "second"),
            ElectricReading.InstantProductionPower(BigDecimal.valueOf(3L), "kW", LocalDateTime.now(), "first"),
            ElectricReading.InstantProductionPower(BigDecimal.valueOf(6L), "kW", LocalDateTime.now(), "second"),
        )

        val firstMeterMock = mockk<AtomicDouble>()
        every { registry.hint(AtomicDouble::class).gauge(eq("first"), any(), any<AtomicDouble>(), any()) } answers { firstMeterMock }
        val firstReadingValues = mutableListOf<Double>()
        every { firstMeterMock.set(capture(firstReadingValues)) } just Runs

        val secondMeterMock = mockk<AtomicDouble>()
        every { registry.hint(AtomicDouble::class).gauge(eq("second"), any(), any<AtomicDouble>(), any()) } answers { secondMeterMock }
        val secondReadingValues = mutableListOf<Double>()
        every { secondMeterMock.set(capture(secondReadingValues)) } just Runs


        victim = victimFactory(Flux.fromIterable(readings), openEvent)

        assertThat(firstReadingValues)
            .hasSize(3)
            .containsExactly(1.0, 2.0, 3.0)

        assertThat(secondReadingValues)
            .hasSize(3)
            .containsExactly(4.0, 5.0, 6.0)
    }

    @Test
    fun `first persistable reading in business day, without any further update, will store single reading only `() {
        val readings = arrayListOf<ElectricReading>(
            ElectricReading.ImportedPower(BigDecimal.valueOf(100L), "kWh", LocalDateTime.now(), "first"),
            ElectricReading.ImportedPower(BigDecimal.valueOf(100L), "kWh", LocalDateTime.now(), "first"),
            ElectricReading.ImportedPower(BigDecimal.valueOf(100L), "kWh", LocalDateTime.now(), "first")
        )

        testHub.onHandleLatestDeviceReading = { BigDecimal.valueOf(99L) }
        testHub.onHandleDailyDeltaDeviceReading = { BigDecimal.valueOf(1L) }
        val storedReadings = mutableListOf<Int>()
        testHub.onHandleStoreLatestReading = { storedReadings.add(it.second.id.toInt()); it.second.id.toInt() }

        victim = victimFactory(Flux.fromIterable(readings), openEvent)

        Thread.sleep(500L)

        assertThat(testHub.readingEvents)
            .hasSize(1)
            .element(0)
            .satisfies(ThrowingConsumer {
                assertThat(it.value).isEqualTo(100.0)
                assertThat(it.deviceType).isEqualTo(DeviceType.POWER_METER_IMPORT)
                assertThat(it.alias).isEqualTo("first")
                assertThat(it.deltaSinceLastReading).isEqualTo(1.0)
            })

        assertThat(storedReadings)
            .hasSize(1)
            .element(0)
            .isEqualTo(readings[0].id.toInt())
    }

    @Test
    fun `a few readings in business day with updated value will store all values`() {
        val readings = arrayListOf<ElectricReading>(
            ElectricReading.ImportedPower(BigDecimal.valueOf(100L), "kWh", LocalDateTime.now(), "first"),
            ElectricReading.ImportedPower(BigDecimal.valueOf(101L), "kWh", LocalDateTime.now(), "first"),
            ElectricReading.ImportedPower(BigDecimal.valueOf(103L), "kWh", LocalDateTime.now(), "first")
        )

        testHub.onHandleLatestDeviceReading = { BigDecimal.valueOf(100L) }
        testHub.onHandleDailyDeltaDeviceReading = { BigDecimal.valueOf(0L) }
        val storedReadings = mutableListOf<Int>()
        testHub.onHandleStoreLatestReading = { storedReadings.add(it.second.id.toInt()); it.second.id.toInt() }

        victim = victimFactory(Flux.fromIterable(readings), openEvent)

        Thread.sleep(500L)

        assertThat(testHub.readingEvents)
            .hasSize(3)
            .element(0)
                .satisfies(ThrowingConsumer {
                    assertThat(it.value).isEqualTo(100.0)
                    assertThat(it.deviceType).isEqualTo(DeviceType.POWER_METER_IMPORT)
                    assertThat(it.alias).isEqualTo("first")
                    assertThat(it.deltaSinceLastReading).isEqualTo(0.0)
                })

        assertThat(testHub.readingEvents)
            .element(1)
            .satisfies(ThrowingConsumer {
                assertThat(it.value).isEqualTo(101.0)
                assertThat(it.deviceType).isEqualTo(DeviceType.POWER_METER_IMPORT)
                assertThat(it.alias).isEqualTo("first")
                assertThat(it.deltaSinceLastReading).isEqualTo(1.0)
            })

        assertThat(testHub.readingEvents)
            .element(2)
            .satisfies(ThrowingConsumer {
                assertThat(it.value).isEqualTo(103.0)
                assertThat(it.deviceType).isEqualTo(DeviceType.POWER_METER_IMPORT)
                assertThat(it.alias).isEqualTo("first")
                assertThat(it.deltaSinceLastReading).isEqualTo(2.0)
            })

        assertThat(storedReadings)
            .hasSize(3)

        assertThat(storedReadings)
            .element(0)
            .isEqualTo(readings[0].id.toInt())

        assertThat(storedReadings)
            .element(1)
            .isEqualTo(readings[1].id.toInt())

        assertThat(storedReadings)
            .element(2)
            .isEqualTo(readings[2].id.toInt())
    }

    @Configuration
    @Import(value = [EventingConfiguration::class, MeterService::class])
    class ScopedConfig {

        @Bean
        fun electricPowerMonitoringFactory(eventPublisher: EventPublisher, meterService: MeterService): (Flux<out ElectricReading>, BusinessDayOpenEvent) -> ElectricPowerMonitoring {
            return { readingsStream, openEvent ->
                val result = ElectricPowerMonitoring(readingsStream, eventPublisher, meterService)
                result.onInitialize()
                result.onNewBusinessDay(openEvent)
                result
            }
        }

        @Bean
        fun testHub() = TestHub()
    }

    class TestHub {
        val readingEvents = mutableListOf<ElectricReadingEvent>()

        var onHandleLatestDeviceReading: ((DeviceType) -> BigDecimal)? = null
        var onHandleDailyDeltaDeviceReading: ((Pair<DeviceType, Int>) -> BigDecimal)? = null
        var onHandleStoreLatestReading: ((Pair<DeviceType, ElectricReading>) -> Int)? = null

        @EventListener
        fun handleLatestDeviceReadingQuery(event: DeviceReadingHub.LatestDeviceReadingQuery) {

            event.handle {
                onHandleLatestDeviceReading?.invoke(it) ?: BigDecimal.ZERO
            }
        }

        @EventListener
        fun handleDailyDeltaDeviceReadingQuery(event: DeviceReadingHub.DailyDeltaDeviceReadingQuery) {
            event.handle {
                onHandleDailyDeltaDeviceReading?.invoke(it) ?: BigDecimal.ZERO
            }
        }

        @EventListener
        fun handleStoreLatestReading(cmd: DeviceReadingHub.StoreLatestReadingCommand) {
            cmd.handle {
                onHandleStoreLatestReading?.invoke(it) ?: it.second.id.toInt()
            }
        }

        @EventListener
        fun onNewMeterReading(args: ElectricReadingEvent) {
            readingEvents.add(args)
        }
    }
}