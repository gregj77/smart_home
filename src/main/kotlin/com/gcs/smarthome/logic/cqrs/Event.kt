package com.gcs.smarthome.logic.cqrs

import java.util.concurrent.CompletableFuture

abstract class Event<TPayload, TResult>(private val payload: TPayload) {
    val task = CompletableFuture<TResult>()

    private fun complete(result: TResult) {
        task.completeAsync { result }
    }

    private fun fail(error: Throwable) {
        task.completeExceptionally(error)
    }

    fun handle( handler: (TPayload) -> TResult) {
        try {
            complete(handler(payload))
        } catch (err: Exception) {
            fail(err)
        }
    }
}

