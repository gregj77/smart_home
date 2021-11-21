package com.gcs.smarthome.logic.cqrs

open class GenericCommand<TPayload, TResult>(payload: TPayload): Event<TPayload, TResult>(payload)