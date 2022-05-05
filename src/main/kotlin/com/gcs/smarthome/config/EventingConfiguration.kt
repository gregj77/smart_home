package com.gcs.smarthome.config

import com.gcs.smarthome.logic.cqrs.EventPublisher
import mu.KotlinLogging
import org.springframework.context.ApplicationContext
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.context.event.ApplicationEventMulticaster
import org.springframework.context.event.SimpleApplicationEventMulticaster
import org.springframework.core.task.SimpleAsyncTaskExecutor
import reactor.core.scheduler.Scheduler
import reactor.core.scheduler.Schedulers
import java.time.*
import java.util.TimeZone
import java.util.WeakHashMap
import java.util.concurrent.TimeUnit
import javax.annotation.PostConstruct

@Configuration
class EventingConfiguration {
    private val logger = KotlinLogging.logger {  }


    @Bean
    fun asyncApplicationEventMulticaster(): ApplicationEventMulticaster {
        val eventMulticaster = SimpleApplicationEventMulticaster()
        eventMulticaster.setTaskExecutor(SimpleAsyncTaskExecutor())
        eventMulticaster.setErrorHandler {
            logger.error { "got error ${it.message} <${it.javaClass.name}>\n${it.stackTrace}" }
        }
        return eventMulticaster
    }

    @Bean
    fun eventPublisher(publisher: ApplicationEventPublisher): EventPublisher {
        return EventPublisher(publisher, Duration.ofSeconds(60L))
    }

}
