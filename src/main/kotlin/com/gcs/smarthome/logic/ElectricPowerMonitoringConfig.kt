package com.gcs.smarthome.logic

import com.gcs.gRPCModbusAdapter.service.*
import com.gcs.smarthome.config.MonitoringConfiguration
import com.gcs.smarthome.data.model.DeviceType
import io.grpc.stub.StreamObserver
import mu.KotlinLogging
import net.devh.boot.grpc.client.inject.GrpcClient
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import reactor.core.publisher.Flux
import java.lang.reflect.Constructor

@Configuration
class ElectricPowerMonitoringConfig(private val monitoringConfiguration: MonitoringConfiguration) {

    private val logger = KotlinLogging.logger {  }

    @GrpcClient("modbusServiceAdapter")
    private lateinit var service: ModbusDeviceServiceGrpc.ModbusDeviceServiceStub

    @Bean
    protected fun processConfiguration(): ConfigurationResult {
        val queryBuilder = Query.newBuilder()

        val mapping = mutableMapOf<Pair<String, DeviceFunction>, (Response) -> ElectricReading>()
        val deviceToMetricsMapping = mutableMapOf<DeviceType, String>()
        var isValid = false

        monitoringConfiguration
            .electricityDevices
            ?.forEach { device ->

                logger.info { "configuring device: ${device.deviceName}..." }
                val deviceRequestBuilder = DeviceRequest.newBuilder()

                deviceRequestBuilder.deviceName = device.deviceName

                device
                    .functions
                    .forEach { function ->
                        logger.info { "adding device ${device.deviceName} function ${function.functionName} [${function.readInterval}]..." }
                        val requestBuilder = Request.newBuilder()
                        requestBuilder.functionName = function.functionName
                        requestBuilder.readIntervalInSeconds = function.readInterval
                        deviceRequestBuilder.addReadRequests(requestBuilder.build())

                        val ctor = function.readingType.constructors[0] as Constructor<ElectricReading>
                        val aliasForMetricsRegistry = function.alias.lowercase().replace(Regex("\\s+"), "_")
                        mapping[Pair(device.deviceName, function.functionName)] = { response ->
                            ElectricReading.fromResponse(aliasForMetricsRegistry, response, ctor)
                        }

                        if (ElectricReading.classToDeviceMapping.containsKey(function.readingType.kotlin)) {
                            deviceToMetricsMapping[ElectricReading.classToDeviceMapping[function.readingType.kotlin]!!] = aliasForMetricsRegistry
                        }
                    }
                logger.info { "device ${device.deviceName} configured with ${device.functions.size} function(s)" }

                queryBuilder.addRequest(deviceRequestBuilder.build())
                isValid = true
            }
        logger.info { "power monitoring configured with ${monitoringConfiguration.electricityDevices?.size} devices..." }

        return ConfigurationResult(queryBuilder.build(), mapping, deviceToMetricsMapping, isValid)
    }

    @Bean
    protected fun createMeterDataStream(cfg: ConfigurationResult): Flux<ElectricReading> {
        if (!cfg.isValid) {
            logger.warn { "ElectricMonitoring configuration is invalid - either grpc.client.modbusServiceAdapter or monitoring.electricityDevices is missing!" }
            return Flux.never()
        }
        return Flux
            .create<ElectricReading> { sink ->
                val observer = object : StreamObserver<Response> {
                    override fun onNext(item: Response) {
                        val key = Pair(item.deviceName, item.functionName)
                        cfg.mapping[key]?.let {
                            sink.next(it(item))
                        }
                    }

                    override fun onError(error: Throwable) = sink.error(error)
                    override fun onCompleted() = sink.complete()
                }

                try {
                    logger.info { "subscribing data stream...." }
                    service.subscribeForDeviceData(cfg.query, observer)
                    logger.info { "subscribed!" }
                } catch (err: Exception) {
                    logger.warn { "failed to subscribe data stream: ${err.message} <${err.javaClass.name}>\n${err.stackTrace}" }
                    sink.error(err)
                }
            }
            .publish()
            .refCount()
    }

    data class ConfigurationResult(
        val query: Query,
        val mapping: Map<Pair<String, DeviceFunction>, (Response) -> ElectricReading>,
        val metricsMapping: Map<DeviceType, String>,
        val isValid: Boolean
    )
}