package com.gcs.smarthome.logic

import mu.KotlinLogging
import org.springframework.scheduling.TaskScheduler
import org.springframework.scheduling.support.CronTrigger
import org.springframework.stereotype.Service
import reactor.core.publisher.Flux
import reactor.core.publisher.FluxSink
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.Temporal
import java.util.*
import kotlin.reflect.KClass

@Service
class SmartHomeTaskScheduler(private val scheduler: TaskScheduler) {

    private val logger = KotlinLogging.logger {  }

    val timeZone: TimeZone
        get() = TimeZone.getDefault()

    val dateTime: LocalDateTime
        get() = LocalDateTime.ofInstant(scheduler.clock.instant(), timeZone.toZoneId())

    val date: LocalDate
        get() = LocalDate.ofInstant(scheduler.clock.instant(), timeZone.toZoneId())

    val time: LocalTime
        get() = LocalTime.ofInstant(scheduler.clock.instant(), timeZone.toZoneId())

    fun <T> schedule(expression: String, clazz: KClass<T>, startImmediately: Boolean = false) : Flux<T> where T : Temporal {

        return Flux.create({ observer ->

            try {
                logger.info { "setting up cron notification for $expression..." }

                val mappingFunc : () -> T = when (clazz) {
                    LocalTime::class -> {  -> @Suppress("UNCHECKED_CAST")(time as T) }
                    LocalDate::class -> { -> @Suppress("UNCHECKED_CAST")(date as T) }
                    LocalDateTime::class -> {  -> @Suppress("UNCHECKED_CAST")(dateTime as T) }
                    else -> throw IllegalArgumentException("class type ${clazz.simpleName} is not supported!")
                }

                val onCronTask = Runnable {
                    observer.next(mappingFunc())
                }

                if (startImmediately) {
                    onCronTask.run()
                }

                val subscription = scheduler.schedule(onCronTask, CronTrigger(expression, TimeZone.getDefault()))!!

                observer.onDispose {
                    logger.info { "subscription of $expression cancelled..." }
                    subscription.cancel(true)
                }

            } catch (err: Exception) {
                logger.error { "failed to setup scheduler for $expression - ${err.message} <${err.javaClass.name}>}" }
                observer.error(err)
            }

        }, FluxSink.OverflowStrategy.LATEST)
    }

}