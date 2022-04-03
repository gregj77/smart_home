package com.gcs.smarthome.logic

import com.gcs.smarthome.config.EventingConfiguration
import com.gcs.smarthome.data.repository.BusinessDayRepository
import com.gcs.smarthome.testutils.VirtualTaskScheduler
import com.ninjasquad.springmockk.MockkBean
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleConfig
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.scheduling.TaskScheduler
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.junit.jupiter.SpringExtension
import org.springframework.transaction.PlatformTransactionManager
import reactor.test.scheduler.VirtualTimeScheduler
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.*

@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [BusinessDayHubTest.ScopedConfig::class])
class BusinessDayHubTest {

    @Autowired
    lateinit var victim : BusinessDayHub

    @MockkBean
    lateinit var repository: BusinessDayRepository

    @MockkBean
    lateinit var txManager: PlatformTransactionManager

    @BeforeEach
    fun setUp() {
    }

    @Test
    fun initialize() {
    }

    @Configuration
    @EnableScheduling
    @Import(value = [BusinessDayHub::class, SmartHomeTaskScheduler::class, EventingConfiguration::class, MeterService::class])
    class ScopedConfig {
        val testTime = OffsetDateTime.parse("2022-01-01T12:00:00Z", DateTimeFormatter.ISO_DATE_TIME).toInstant()
        final val scheduler = VirtualTimeScheduler.create()
        final val taskScheduler = VirtualTaskScheduler(scheduler)

        @Bean
        fun taskScheduler() : TaskScheduler {
            TimeZone.setDefault(TimeZone.getTimeZone("CET"))
            scheduler.advanceTimeTo(testTime)
            return taskScheduler
        }

        @Bean
        fun meterRegistry() : MeterRegistry = SimpleMeterRegistry()
    }
}