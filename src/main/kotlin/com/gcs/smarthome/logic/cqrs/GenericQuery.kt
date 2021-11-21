package com.gcs.smarthome.logic.cqrs

open class GenericQuery<TPayload, TResult>(payload: TPayload) : Event<TPayload, TResult>(payload)