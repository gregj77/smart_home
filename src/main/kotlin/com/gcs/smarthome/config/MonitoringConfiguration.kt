package com.gcs.smarthome.config

import com.gcs.smarthome.logic.ElectricReading
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotEmpty
import com.gcs.gRPCModbusAdapter.service.DeviceFunction as FunctionNameEnum

@Validated
@ConfigurationProperties(prefix = "monitoring")
class MonitoringConfiguration(val electricityDevices: List<ElectricityDevice>?) {
}

@Validated
class ElectricityDevice(
    @NotEmpty
    val deviceName: String,

    @NotEmpty
    val functions: List<DeviceFunction>
)

@Validated
class DeviceFunction(
    val functionName: FunctionNameEnum,

    @Min(1)
    @Max(3600)
    val readInterval: Int,

    @NotEmpty
    val alias: String,

    val readingType: Class<out ElectricReading>
)