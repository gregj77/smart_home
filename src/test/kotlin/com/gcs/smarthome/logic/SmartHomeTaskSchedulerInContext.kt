package com.gcs.smarthome.logic

import mu.KotlinLogging
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Import
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.junit.jupiter.SpringExtension
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import kotlin.time.ExperimentalTime
import kotlin.time.measureTime

@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [SmartHomeTaskSchedulerInContext.ScopedConfig::class])
class SmartHomeTaskSchedulerInContext {

    private val logger = KotlinLogging.logger {  }

    @Autowired
    private lateinit var victim: SmartHomeTaskScheduler

    @OptIn(ExperimentalTime::class)
    @Test
    fun `scheduling with default spring's context works`() {
        val collected = mutableListOf<Long>()
        val duration = measureTime {
            val start = victim.time
            collected += victim
                .schedule("*/2 * * * * *", LocalTime::class)
                .take(5)
                .doOnNext { logger.info { "got next: $it" } }
                .map { start.until(it, ChronoUnit.SECONDS) }
                .buffer()
                .blockLast()!!
        }

        assertThat(collected)
            .hasSize(5)

        collected.drop(1).forEachIndexed { idx, _ ->
            assertThat(collected[idx + 1] - collected[idx]).isEqualTo(2L)
        }

        assertThat(duration.inWholeSeconds).isGreaterThanOrEqualTo(2 * 4) //rounding to 1 second when scheduling
    }

    @TestConfiguration
    @EnableScheduling
    @Import(value = [TaskSchedulingAutoConfiguration::class, SmartHomeTaskScheduler::class ])
    class ScopedConfig
}