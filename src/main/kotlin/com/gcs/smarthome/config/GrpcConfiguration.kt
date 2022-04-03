package com.gcs.smarthome.config

import com.gcs.gRPCModbusAdapter.service.ModbusDeviceServiceGrpc.ModbusDeviceServiceStub
import com.gcs.gRPCModbusAdapter.service.Query
import com.gcs.gRPCModbusAdapter.service.Response
import io.grpc.stub.ClientCallStreamObserver
import io.grpc.stub.ClientResponseObserver
import mu.KotlinLogging
import net.devh.boot.grpc.client.inject.GrpcClient
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import reactor.core.publisher.Flux
import reactor.util.retry.RetrySpec
import java.time.Duration

@Configuration
class GrpcConfiguration {

    private val logger = KotlinLogging.logger {  }

    @GrpcClient("modbusServiceAdapter")
    private lateinit var service: ModbusDeviceServiceStub

    @Bean
    fun subscribeForDeviceDataBuilder(): (query: Query) -> Flux<Response> {
        return { query ->
            Flux.create<Response> { sink ->
                val observer = object : ClientResponseObserver<Query, Response> {
                    override fun onNext(item: Response) { sink.next(item) }
                    override fun onError(error: Throwable) = sink.error(error)
                    override fun onCompleted() = sink.complete()
                    override fun beforeStart(callObserver: ClientCallStreamObserver<Query>) {
                        sink.onDispose {
                            logger.info { "cancelling subscription..." }
                            callObserver.cancel("subscription canceled due to unsubscribe", null)
                        }
                    }
                }

                try {
                    logger.info { "subscribing data stream...." }
                    service.subscribeForDeviceData(query, observer)
                    logger.info { "subscribed!" }
                } catch (err: Exception) {
                    logger.warn { "failed to subscribe data stream: ${err.message} <${err.javaClass.name}>\n${err.stackTrace}" }
                    sink.error(err)
                }
            }
                .doOnError { logger.error { "failed to create stream - ${it.message} <${it.javaClass.name}>\n${it.stackTrace}" } }
                .retryWhen(RetrySpec.fixedDelay(Long.MAX_VALUE, Duration.ofSeconds(5L)))
        }
    }

}