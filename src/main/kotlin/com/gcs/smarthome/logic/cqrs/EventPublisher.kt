package com.gcs.smarthome.logic.cqrs

import mu.KotlinLogging
import org.springframework.context.ApplicationEventPublisher
import reactor.core.publisher.Mono
import reactor.util.function.Tuple2
import reactor.util.retry.RetrySpec
import java.time.Duration
import java.util.concurrent.CompletableFuture

class EventPublisher(private val publisher: ApplicationEventPublisher, private val eventTimeout: Duration) {

    private val logger = KotlinLogging.logger {  }

    fun <T : Any> broadcastEvent(event: T) {
        publisher.publishEvent(event)
    }

    fun <Payload, Result, T : GenericQuery<Payload, Result>, CTX, AggregatedResult> query(
        args: T,
        ctx: CTX,
        resultAggregator: (CTX, Result) -> AggregatedResult): Mono<AggregatedResult> {

        publisher.publishEvent(args)
        return internalHandleResult(args.task).map { resultAggregator(ctx, it) }
    }

    fun <Payload, Result, T : GenericQuery<Payload, Result>> query(args: T) =
        query(args, Unit) { _, result -> result }

    fun <Payload, Result, T : GenericCommand<Payload, Result>, CTX, AggregatedResult> command(
        args: T,
        ctx: CTX,
        resultAggregator: (CTX, Result) -> AggregatedResult): Mono<AggregatedResult> {

        publisher.publishEvent(args)
        return internalHandleResult(args.task).map { resultAggregator(ctx, it) }
    }

    fun <Payload, Result, T : GenericCommand<Payload, Result>> command(args: T) =
        command(args, Unit) { _, result -> result }

    private fun <Result> internalHandleResult(asyncStage: CompletableFuture<Result>) = Mono
        .fromFuture(asyncStage)
        .timeout(eventTimeout)

    fun <T1, T2> queryMany(args1: GenericQuery<*,T1>, args2: GenericQuery<*,T2>) : Mono<Tuple2<T1, T2>> {
        return this.query(args1)
            .zipWith(this.query(args2))
            .doOnError { this.logger.warn { "error: ${it.message} <${it.javaClass.name}>" } }
            .retryWhen(RetrySpec.fixedDelay(10L, Duration.ofSeconds(5L)))
    }
}

