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
class SmartHomeTaskScheduler(
    private val scheduler: TaskScheduler,
    val dateProvider: () -> LocalDate,
    val timeProvider: () -> LocalTime,
    val dateTimeProvider: () -> LocalDateTime,
    val timeZone: TimeZone) {

    private val logger = KotlinLogging.logger {  }

    val dateTime: LocalDateTime
        get() = dateTimeProvider()

    val date: LocalDate
        get() = dateProvider()

    val time: LocalTime
        get() = timeProvider()

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
                    try {
                        observer.next(mappingFunc())
                    } catch (err: Exception) {
                        observer.error(err)
                    }
                }

                if (startImmediately) {
                    onCronTask.run()
                }

                val subscription = scheduler.schedule(onCronTask, CronTrigger(expression, timeZone))!!

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