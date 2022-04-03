package com.gcs.smarthome.logic

import com.gcs.gRPCModbusAdapter.service.Query
import com.gcs.gRPCModbusAdapter.service.Response
import com.gcs.smarthome.config.EventingConfiguration
import com.gcs.smarthome.config.MonitoringConfiguration
import com.gcs.smarthome.config.SchedulerConfiguration
import com.gcs.smarthome.data.model.DeviceReadingDailyReport
import com.gcs.smarthome.data.model.DeviceType
import com.gcs.smarthome.data.repository.BusinessDayRepository
import com.gcs.smarthome.logic.message.BusinessDayOpenEvent
import com.gcs.smarthome.logic.message.ElectricReadingEvent
import com.ninjasquad.springmockk.MockkBean
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
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
import java.time.*
import java.time.format.DateTimeFormatter

@ActiveProfiles("grpc")
@ExtendWith(SpringExtension::class)
@EnableConfigurationProperties(value = [MonitoringConfiguration::class])
@ContextConfiguration(initializers = [ConfigDataApplicationContextInitializer::class], classes = [DailyReportingServiceTest.ScopedConfig::class])
class DailyReportingServiceTest(@Value("\${reportingService.maxDaysInHistory:30}") private val maxDaysInHistory: Long) {

    @Autowired
    private lateinit var victim: DailyReportingService

    @Autowired
    private lateinit var scheduler: Scheduler

    @MockkBean
    lateinit var businessDayRepository: BusinessDayRepository

    @Autowired
    private lateinit var localDateProvider: () -> LocalDate

    @Autowired
    private lateinit var localDateTimeProvider: () -> LocalDateTime

    @Autowired
    private lateinit var meterRegistry: MeterRegistry

    @Autowired
    private lateinit var meterService: MeterService

    @BeforeEach
    fun setUp() {
    }

    @Test
    @DirtiesContext
    fun `daily reporting will filter out readings outside history range`() {
        every { businessDayRepository.loadDeviceDailyReport(any(), any(), any()) } answers {
            listOf(
                localDateProvider().minusDays(maxDaysInHistory),
                localDateProvider().minusDays(maxDaysInHistory + 1),
                localDateProvider().minusDays(maxDaysInHistory - 1))
                .map { Reading(BigDecimal.ZERO, BigDecimal.ZERO, DeviceType.POWER_METER_IMPORT, it) }
        }

        victim.onBusinessDayStart(BusinessDayOpenEvent(1, localDateProvider()))

        assertThat(meterRegistry.meters).hasSize(2)
        assertThat(meterRegistry.meters[0].id.getTag("date")).isEqualTo(DateTimeFormatter.ofPattern("yyyy-MM-dd").format(localDateProvider().minusDays(maxDaysInHistory)))
        assertThat(meterRegistry.meters[1].id.getTag("date")).isEqualTo(DateTimeFormatter.ofPattern("yyyy-MM-dd").format(localDateProvider().minusDays(maxDaysInHistory - 1)))
    }

    @Test
    @DirtiesContext
    fun `daily reporting will remove outdated readings`() {
        every { businessDayRepository.loadDeviceDailyReport(any(), any(), any()) } answers {
            listOf(localDateProvider().minusDays(maxDaysInHistory)).map { Reading(BigDecimal.ZERO, BigDecimal.ZERO, DeviceType.POWER_METER_IMPORT, it) }
        }

        victim.onBusinessDayStart(BusinessDayOpenEvent(1, localDateProvider()))

        assertThat(meterRegistry.meters).hasSize(1)
        (scheduler as VirtualTimeScheduler).advanceTimeBy(Duration.ofDays(1L))
        assertThat(meterRegistry.meters).isEmpty()
    }

    @Test
    @DirtiesContext
    fun `daily will create meters by their type`() {
        every { businessDayRepository.loadDeviceDailyReport(any(), any(), any()) } answers {
            listOf(DeviceType.POWER_METER_IMPORT, DeviceType.POWER_METER_EXPORT, DeviceType.POWER_METER_PRODUCTION)
                .map { Reading(BigDecimal.ZERO, BigDecimal.ZERO, it, localDateProvider()) }
        }

        victim.onBusinessDayStart(BusinessDayOpenEvent(1, localDateProvider()))

        assertThat(meterRegistry.meters).hasSize(3)
        assertThat(meterService.listMeterIdsBy {
            it.name.startsWith("daily_main_import")
        }).hasSize(1)
        assertThat(meterService.listMeterIdsBy {
            it.name.startsWith("daily_main_export")
        }).hasSize(1)
        assertThat(meterService.listMeterIdsBy {
            it.name.startsWith("daily_production")
        }).hasSize(1)
    }

    @Test
    @DirtiesContext
    fun `daily will update counter for valid date with increasign time only`() {
        every { businessDayRepository.loadDeviceDailyReport(any(), any(), any()) } answers {
            listOf(2L, 1L, 0L)
                .map { Reading(BigDecimal.valueOf(3 - it), BigDecimal.ZERO, DeviceType.POWER_METER_IMPORT, localDateProvider().minusDays(it)) }
        }

        victim.onBusinessDayStart(BusinessDayOpenEvent(1, localDateProvider()))

        assertThat(meterRegistry.meters).hasSize(3)
        assertThat(meterService.listMeterIdsBy {
            it.name.startsWith("daily_main_import")
        }).hasSize(3)

        val meter = meterRegistry.meters[2] as Counter
        assertThat(meter.id.tags.singleOrNull { it.value == "2022-01-01" }).isNotNull()

        (scheduler as VirtualTimeScheduler).advanceTimeBy(Duration.ofMinutes(1L))
        victim.onNewMeterReading(ElectricReadingEvent(DeviceType.POWER_METER_IMPORT, "daily_main_import_02", 3.1, 0.1, localDateTimeProvider()))
        assertThat(meter.count()).isEqualTo(0.1)
        (scheduler as VirtualTimeScheduler).advanceTimeBy(Duration.ofMinutes(1L))
        victim.onNewMeterReading(ElectricReadingEvent(DeviceType.POWER_METER_IMPORT, "daily_main_import_02", 4.1, 1.0, localDateTimeProvider()))
        assertThat(meter.count()).isEqualTo(1.1)

        // this one should be rejected - it has the same timestmap
        victim.onNewMeterReading(ElectricReadingEvent(DeviceType.POWER_METER_IMPORT, "daily_main_import_02", 5.1, 2.0, localDateTimeProvider()))
        assertThat(meter.count()).isEqualTo(1.1)
    }

    @Test
    @DirtiesContext
    fun `new supported reading will create meter if not ready yet`() {
        assertThat(meterRegistry.meters).hasSize(0)

        (scheduler as VirtualTimeScheduler).advanceTimeBy(Duration.ofMinutes(1L))
        victim.onNewMeterReading(ElectricReadingEvent(DeviceType.POWER_METER_IMPORT, "daily_main_import_02", 3.1, 0.1, localDateTimeProvider()))
        assertThat(meterRegistry.meters).hasSize(1)
        assertThat(meterRegistry.meters[0].id.tags.singleOrNull{ it.value == "2022-01-01" })
        assertThat((meterRegistry.meters[0] as Counter).count()).isEqualTo(0.1)

    }

    @TestConfiguration
    @Import(value = [
        EventingConfiguration::class,
        DailyReportingService::class,
        ElectricPowerMonitoringConfig::class,
        SchedulerConfiguration::class,
        MeterService::class,
        SimpleMeterRegistry::class,
    ])
    class ScopedConfig {

        private var sink = Sinks.many().multicast().onBackpressureBuffer<Response>()
        private var scheduler: VirtualTimeScheduler = VirtualTimeScheduler.create()

        @Bean
        fun scheduler(tz: ZoneId) : Scheduler {

            scheduler.advanceTimeTo(LocalDateTime.of(2022, 1, 1, 13, 0,0).toInstant(ZoneOffset.UTC))
            return scheduler
        }

        @Bean
        fun responseSink() = sink

        @Bean
        fun responseStreamFactory(): (Query) -> Flux<Response> {
            return { sink.asFlux() }
        }

    }

    class Reading(
        override val value: BigDecimal,
        override val dailyDelta: BigDecimal,
        override val deviceType: DeviceType,
        override val date: LocalDate
    ) : DeviceReadingDailyReport
}