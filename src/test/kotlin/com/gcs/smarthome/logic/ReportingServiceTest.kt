package com.gcs.smarthome.logic

import com.gcs.gRPCModbusAdapter.service.Query
import com.gcs.gRPCModbusAdapter.service.Response
import com.gcs.smarthome.config.EventingConfiguration
import com.gcs.smarthome.config.MonitoringConfiguration
import com.gcs.smarthome.config.SchedulerConfiguration
import com.gcs.smarthome.data.repository.BusinessDayRepository
import com.ninjasquad.springmockk.MockkBean
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.junit.jupiter.SpringExtension
import reactor.core.publisher.Flux
import reactor.core.publisher.Sinks
import reactor.core.scheduler.Scheduler
import reactor.test.scheduler.VirtualTimeScheduler
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit
import javax.annotation.PostConstruct
import kotlin.math.sin

@ActiveProfiles("grpc")
@ExtendWith(SpringExtension::class)
@EnableConfigurationProperties(value = [MonitoringConfiguration::class])
@ContextConfiguration(initializers = [ConfigDataApplicationContextInitializer::class], classes = [ReportingServiceTest.ScopedConfig::class])
class ReportingServiceTest {

    @BeforeEach
    fun setUp() {
    }

    @Test
    @DirtiesContext
    fun onInitialize() {
    }

    @TestConfiguration
    @Import(value = [EventingConfiguration::class, ReportingService::class, ElectricPowerMonitoringConfig::class, SchedulerConfiguration::class])
    class ScopedConfig {

        @MockkBean
        lateinit var businessDayRepository: BusinessDayRepository

        private var sink = Sinks.many().multicast().onBackpressureBuffer<Response>()
        private var scheduler: VirtualTimeScheduler = VirtualTimeScheduler.create()

        @Bean
        fun scheduler(tz: ZoneId) : Scheduler {

            //scheduler.advanceTimeTo(LocalDateTime.of(2022, 1, 1, 13, 0,0).toInstant(tz.))
            return scheduler
        }

        @Bean
        fun responseSink() = sink

        @Bean
        fun responseStreamFactory(): (Query) -> Flux<Response> {
            return { sink.asFlux() }
        }

    }
}