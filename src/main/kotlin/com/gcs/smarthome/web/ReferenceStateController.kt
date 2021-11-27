package com.gcs.smarthome.web

import com.gcs.smarthome.data.model.ReferenceType
import com.gcs.smarthome.logic.ReferenceStateHub
import com.gcs.smarthome.logic.cqrs.EventPublisher
import mu.KotlinLogging
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Mono
import java.math.BigDecimal
import java.time.LocalDate
import javax.validation.Valid
import javax.validation.ValidationException
import javax.validation.Validator
import javax.validation.constraints.DecimalMin

@RestController
@RequestMapping("/api/v1/referenceState")
class ReferenceStateController(private val validator: Validator, private val eventPublisher: EventPublisher) {

    private val logger= KotlinLogging.logger {  }


    @PostMapping(consumes = ["application/json"])
    fun newReferenceReading(@RequestBody reading: Mono<@Valid Reading>): Mono<ResponseEntity<Any>> {

        return reading
            .filter{ validate(it) }
            .flatMap {
                logger.debug { "received new reading request: $it" }
                eventPublisher.command(ReferenceStateHub.newReferenceReading(it) ) }
            .map {
                logger.debug { "reading saved with id $it" }
                ResponseEntity<Any>(it, HttpStatus.CREATED)
            }
            .onErrorResume {
                logger.warn { "error validating request: $it" }
                Mono.just(ResponseEntity<Any>(
                    when (it) {
                        is ValidationExceptionEx -> it.errors
                        else -> it.message
                    },
                    HttpStatus.BAD_REQUEST))
            }
    }

    private fun validate(reading: Reading): Boolean {
        val errors = validator.validate(reading)
        if (errors.isNotEmpty()) {
            val results = mutableListOf<String>();
            errors.forEach {
                val result = StringBuilder()
                result.append(it.propertyPath)
                result.append(":")
                result.append(it.message)
                results.add(result.toString())
            }
            throw ValidationExceptionEx(results)
        }
        return true
    }
}

@Validated
data class Reading(
    val type: ReferenceType,
    val readingDate: LocalDate,
    @field:DecimalMin(value = "0.0")
    val value: BigDecimal,
)

class ValidationExceptionEx(val errors: List<String>) : ValidationException("validation error!") {
    override fun toString(): String {
        return errors.joinToString { it + "\n" }
    }
}