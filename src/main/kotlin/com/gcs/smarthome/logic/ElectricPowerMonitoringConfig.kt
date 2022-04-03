package com.gcs.smarthome.logic

import com.gcs.gRPCModbusAdapter.service.*
import com.gcs.smarthome.config.MonitoringConfiguration
import com.gcs.smarthome.data.model.DeviceType
import mu.KotlinLogging
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import reactor.core.publisher.Flux
import java.lang.reflect.Constructor
import java.time.ZoneId

@Configuration
class ElectricPowerMonitoringConfig(private val monitoringConfiguration: MonitoringConfiguration) {

    private val logger = KotlinLogging.logger {  }

    @Bean
    protected fun processConfiguration(zoneId: ZoneId): ConfigurationResult {
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

                        val ctor = @Suppress("UNCHECKED_CAST")(function.readingType.constructors[0] as Constructor<ElectricReading>)
                        val aliasForMetricsRegistry = function.alias.lowercase().replace(Regex("\\s+"), "_")
                        mapping[Pair(device.deviceName, function.functionName)] = { response ->
                            ElectricReading.fromResponse(aliasForMetricsRegistry, response, ctor, zoneId)
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
    protected fun createMeterDataStream(cfg: ConfigurationResult, queryBuilder: (Query) -> Flux<Response>): Flux<ElectricReading> {
        if (!cfg.isValid) {
            logger.warn { "ElectricMonitoring configuration is invalid - either grpc.client.modbusServiceAdapter or monitoring.electricityDevices is missing!" }
            return Flux.never()
        }
        return queryBuilder(cfg.query)
            .mapNotNull { reading ->
                val key = Pair(reading.deviceName, reading.functionName)
                cfg.mapping[key]?.let { mappingFunc -> mappingFunc(reading) }
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