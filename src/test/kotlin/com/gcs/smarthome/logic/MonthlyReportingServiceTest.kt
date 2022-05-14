package com.gcs.smarthome.logic

import com.gcs.gRPCModbusAdapter.service.Query
import com.gcs.gRPCModbusAdapter.service.Response
import com.gcs.smarthome.config.EventingConfiguration
import com.gcs.smarthome.config.MonitoringConfiguration
import com.gcs.smarthome.config.SchedulerConfiguration
import com.gcs.smarthome.data.model.DeviceReadingMonthlyReport
import com.gcs.smarthome.data.model.DeviceType
import com.gcs.smarthome.data.repository.BusinessDayRepository
import com.gcs.smarthome.testutils.VirtualTaskScheduler
import com.google.common.util.concurrent.AtomicDouble
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.CapturingSlot
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.junit.jupiter.SpringExtension
import reactor.core.publisher.Flux
import reactor.core.publisher.Sinks
import reactor.core.scheduler.Scheduler
import reactor.test.scheduler.VirtualTimeScheduler
import java.math.BigDecimal
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

@ActiveProfiles("grpc")
@ExtendWith(SpringExtension::class)
@EnableConfigurationProperties(value = [MonitoringConfiguration::class])
@ContextConfiguration(initializers = [ConfigDataApplicationContextInitializer::class], classes = [MonthlyReportingServiceTest.ScopedConfig::class])
internal class MonthlyReportingServiceTest {

    @Autowired
    private lateinit var victimFactory: () -> MonthlyReportingService

    @Autowired
    private lateinit var scheduler: Scheduler

    @Autowired
    private lateinit var businessDayRepository: BusinessDayRepository

    @Autowired
    private lateinit var meterService: MeterService

    @Test
    @DirtiesContext
    fun `monthly reporting service will load all available reports on start`() {
        val victim = victimFactory()
        val slot = CapturingSlot<List<Int>>()
        every { businessDayRepository.loadDeviceMonthlyReport(
            capture(slot),
            isNull())
        } answers {
            listOf(
                MonthlyReport(BigDecimal.valueOf(1.0), DeviceType.POWER_METER_PRODUCTION, 2021, 12),
                MonthlyReport(BigDecimal.valueOf(1.1), DeviceType.POWER_METER_EXPORT, 2021, 12),
                MonthlyReport(BigDecimal.valueOf(1.3), DeviceType.POWER_METER_IMPORT, 2021, 12),
                MonthlyReport(BigDecimal.valueOf(2.0), DeviceType.POWER_METER_PRODUCTION, 2022, 1),
                MonthlyReport(BigDecimal.valueOf(2.1), DeviceType.POWER_METER_EXPORT, 2022, 1),
                MonthlyReport(BigDecimal.valueOf(2.3), DeviceType.POWER_METER_IMPORT, 2022, 1),
            )
        }

        victim.onInitialize()

        assertThat(slot.captured).containsExactlyInAnyOrder(0, 1, 2)
        val meters = meterService.listMeterIdsBy{true}
        assertThat(meters).hasSize(6)

        assertThat(meters.all { it.name.startsWith("monthly_") }).isTrue
        assertThat(meters.count { it.tags.single { t -> t.key == "date" }.value == "2022-01" }).isEqualTo(3)
        assertThat(meters.count { it.tags.single { t -> t.key == "date" }.value == "2021-12" }).isEqualTo(3)
        assertThat(meters.count { it.tags.single { t -> t.key == "year" }.value == "2022" }).isEqualTo(3)
        assertThat(meters.count { it.tags.single { t -> t.key == "year" }.value == "2021" }).isEqualTo(3)

        victim.onDestroy()
    }

    @Test
    @DirtiesContext
    fun `monthly reporting service will update meters twice per hour`() {
        val victim = victimFactory()
        every { businessDayRepository.loadDeviceMonthlyReport(
            any(),
            isNull())
        } answers {
            listOf(MonthlyReport(BigDecimal.valueOf(1.0), DeviceType.POWER_METER_IMPORT, 2022, 1))
        }
        every { businessDayRepository.loadDeviceMonthlyReport(any(), eq(202201)) } answers {
            listOf(MonthlyReport(BigDecimal.valueOf(2.0), DeviceType.POWER_METER_IMPORT, 2022, 1))
        } andThenAnswer {
            listOf(MonthlyReport(BigDecimal.valueOf(3.0), DeviceType.POWER_METER_IMPORT, 2022, 1))
        } andThenThrows ( Exception("not expected!") )

        victim.onInitialize()

        val meters = meterService.listMeterIdsBy{true}
        assertThat(meters).hasSize(1)
        val meter = meterService.meterById(meters[0]) as AtomicDouble
        assertThat(meter.get()).isEqualTo(1.0)
        (scheduler as VirtualTimeScheduler).advanceTimeBy(Duration.ofHours(1L))
        assertThat(meter.get()).isEqualTo(3.0)

        victim.onDestroy()
    }

    @Test
    @DirtiesContext
    fun `monthly reporting service will refresh all meters at month's start`() {
        val victim = victimFactory()
        every { businessDayRepository.loadDeviceMonthlyReport(
            any(),
            isNull())
        } answers {
            listOf(MonthlyReport(BigDecimal.valueOf(1.0), DeviceType.POWER_METER_IMPORT, 2022, 1))
        } andThenAnswer {
            listOf(MonthlyReport(BigDecimal.valueOf(2.0), DeviceType.POWER_METER_IMPORT, 2022, 2))
        } andThenThrows(Exception("ups!"))

        every { businessDayRepository.loadDeviceMonthlyReport(any(), matchNullable<Int> { it in 202201..202202 }) } answers {
            val yearAndMonth = it.invocation.args[1] as Int?
            val year = yearAndMonth?.div(100)!!
            val month = yearAndMonth.mod(100)

            listOf(MonthlyReport(BigDecimal.valueOf(1.0), DeviceType.POWER_METER_IMPORT, year, month))
        }

        victim.onInitialize()

        var meters = meterService.listMeterIdsBy{true}
        assertThat(meters).hasSize(1)
        (scheduler as VirtualTimeScheduler).advanceTimeBy(Duration.ofDays(1L))
        meters = meterService.listMeterIdsBy{true}
        assertThat(meters).hasSize(2)
        assertThat(meters[0].tags.singleOrNull{ it.value == "2022-01" }).isNotNull
        assertThat(meters[1].tags.singleOrNull{ it.value == "2022-02" }).isNotNull

        victim.onDestroy()
    }

    @TestConfiguration
    @Import(value = [
        EventingConfiguration::class,
        ElectricPowerMonitoringConfig::class,
        SchedulerConfiguration::class,
        MeterService::class,
        SimpleMeterRegistry::class,
        SmartHomeTaskScheduler::class,
        VirtualTaskScheduler::class,
    ])
    class ScopedConfig {
        private var sink = Sinks.many().multicast().onBackpressureBuffer<Response>()
        private var scheduler: VirtualTimeScheduler = VirtualTimeScheduler.create()
        private var businessDayRepository = mockk<BusinessDayRepository>()

        @Bean
        fun scheduler(tz: ZoneId) : VirtualTimeScheduler {

            scheduler.advanceTimeTo(LocalDateTime.of(2022, 1, 31, 13, 0,0).toInstant(ZoneOffset.UTC))
            return scheduler
        }

        @Bean
        fun responseSink() = sink

        @Bean
        fun responseStreamFactory(): (Query) -> Flux<Response> {
            return { sink.asFlux() }
        }

        @Bean
        fun businessDayRepository() = businessDayRepository

        @Bean
        fun reportingServiceFactory(businessDayRepository: BusinessDayRepository, cfg: ElectricPowerMonitoringConfig.ConfigurationResult, meterService: MeterService, taskScheduler: SmartHomeTaskScheduler) : () -> MonthlyReportingService {
            return { MonthlyReportingService(businessDayRepository, cfg, meterService, taskScheduler)}
        }
    }

    data class MonthlyReport(
        override val value: BigDecimal,
        override val deviceType: DeviceType,
        override val year: Int,
        override val month: Int
    ) : DeviceReadingMonthlyReport
}