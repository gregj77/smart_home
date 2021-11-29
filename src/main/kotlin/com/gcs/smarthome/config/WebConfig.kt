package com.gcs.smarthome.config

import com.google.common.util.concurrent.AtomicDouble
import io.micrometer.core.instrument.MeterRegistry
import mu.KotlinLogging
import org.springframework.context.annotation.Bean
import org.springframework.http.HttpHeaders.AUTHORIZATION
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.security.authorization.AuthorizationDecision
import org.springframework.security.authorization.ReactiveAuthorizationManager
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity
import org.springframework.security.config.web.server.ServerHttpSecurity
import org.springframework.security.web.server.SecurityWebFilterChain
import org.springframework.security.web.server.authorization.AuthorizationContext
import reactor.core.publisher.Mono
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.random.Random

@EnableWebFluxSecurity
class WebConfig(meterRegistry: MeterRegistry) {

    private val tokenValue = AtomicDouble(nextTokenValue().toDouble())
    private val logger = KotlinLogging.logger {  }

    init {
        meterRegistry.gauge("access_token", tokenValue) { tokenValue.get() }
    }

    @Bean
    fun springSecurityFilterChain(http: ServerHttpSecurity):  SecurityWebFilterChain {
        http
            .csrf().disable()
            .cors().disable()
            .authorizeExchange()
            .pathMatchers("/api/v1/**").access(accessChecker())
            .pathMatchers("/**").permitAll()

        return http.build()
    }

    private fun accessChecker(): ReactiveAuthorizationManager<AuthorizationContext> {
        return ReactiveAuthorizationManager<AuthorizationContext> { _, ctx ->
            val currentToken = tokenValue.get().toLong().toString()
            val providedToken = ctx.exchange.request.headers.getFirst(AUTHORIZATION)
            val result = currentToken == providedToken
            logger.info { "provided: $providedToken, expected: $currentToken, decision: $result" }
            Mono.just(AuthorizationDecision(result))
        }
    }

    @Scheduled(cron = "30 45 2 * * ?")
    fun updateAccessToken() {
        tokenValue.set(nextTokenValue().toDouble())
    }

    private fun nextTokenValue() = Random(LocalDateTime.now().toEpochSecond(ZoneOffset.UTC))
        .nextLong(Integer.MAX_VALUE.toLong(), Long.MAX_VALUE)
}