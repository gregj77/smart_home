package com.gcs.smarthome.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import reactor.core.scheduler.Scheduler
import reactor.core.scheduler.Schedulers
import java.time.*
import java.util.*
import java.util.concurrent.TimeUnit


@Configuration
class SchedulerConfiguration {

    init {
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Warsaw"))
    }

    @Bean
    @Profile("!TEST")
    fun scheduler(): Scheduler {
        return Schedulers.boundedElastic()
    }

    @Bean
    fun timeZone(): TimeZone = TimeZone.getDefault()

    @Bean
    fun zoneId(tz: TimeZone): ZoneId = ZoneId.of(tz.id)

    @Bean
    fun localDateProvider(scheduler: Scheduler, zoneId: ZoneId): () -> LocalDate {
        return { LocalDate.from(Instant.ofEpochMilli(scheduler.now(TimeUnit.MILLISECONDS)).atZone(zoneId)) }
    }

    @Bean
    fun localTimeProvider(scheduler: Scheduler, zoneId: ZoneId): () -> LocalTime {
        return { LocalTime.from(Instant.ofEpochMilli(scheduler.now(TimeUnit.MILLISECONDS)).atZone(zoneId)) }
    }

    @Bean
    fun localDateTimeProvider(scheduler: Scheduler, zoneId: ZoneId): () -> LocalDateTime {
        return { LocalDateTime.from(Instant.ofEpochMilli(scheduler.now(TimeUnit.MILLISECONDS)).atZone(zoneId)) }
    }
}
