package com.gcs.smarthome.logic.cqrs

import org.springframework.context.ApplicationEventPublisher
import reactor.core.publisher.Mono
import java.time.Duration
import java.util.concurrent.CompletableFuture

class EventPublisher(private val publisher: ApplicationEventPublisher, private val eventTimeout: Duration) {

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
        query(args, "") { _, result -> result }

    fun <Payload, Result, T : GenericCommand<Payload, Result>, CTX, AggregatedResult> command(
        args: T,
        ctx: CTX,
        resultAggregator: (CTX, Result) -> AggregatedResult): Mono<AggregatedResult> {

        publisher.publishEvent(args)
        return internalHandleResult(args.task).map { resultAggregator(ctx, it) }
    }

    fun <Payload, Result, T : GenericCommand<Payload, Result>> command(args: T) =
        command(args, "") { _, result -> result }

    private fun <Result> internalHandleResult(asyncStage: CompletableFuture<Result>) = Mono
        .fromFuture(asyncStage)
        .timeout(eventTimeout)
}