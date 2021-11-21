package com.gcs.smarthome.config

import com.gcs.smarthome.logic.ElectricReading
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.ConstructorBinding
import org.springframework.validation.annotation.Validated
import javax.validation.constraints.Max
import javax.validation.constraints.Min
import javax.validation.constraints.NotEmpty
import com.gcs.gRPCModbusAdapter.service.DeviceFunction as FunctionNameEnum

@Validated
@ConstructorBinding
@ConfigurationProperties(prefix = "monitoring")
class MonitoringConfiguration(val electricityDevices: List<ElectricityDevice>?) {
}

@Validated
@ConstructorBinding
class ElectricityDevice(
    @NotEmpty
    val deviceName: String,

    @NotEmpty
    val functions: List<DeviceFunction>
)

@Validated
@ConstructorBinding
class DeviceFunction(
    val functionName: FunctionNameEnum,

    @Min(1)
    @Max(3600)
    val readInterval: Int,

    @NotEmpty
    val alias: String,

    val readingType: Class<out ElectricReading>
)