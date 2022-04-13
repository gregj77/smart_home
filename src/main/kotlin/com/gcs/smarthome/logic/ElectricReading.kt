package com.gcs.smarthome.logic

import com.gcs.gRPCModbusAdapter.service.Response
import com.gcs.smarthome.data.model.DeviceType
import java.lang.reflect.Constructor
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicLong
import kotlin.reflect.KClass

interface InstantReading

internal interface PersistableReading {
    val deviceType: DeviceType
}

sealed class ElectricReading(val value: BigDecimal, val unit: String, val time: LocalDateTime, val alias: String) {

    val id = counter.incrementAndGet()

    class ExportedPower(value: BigDecimal, unit: String, time: LocalDateTime, alias: String)
        : ElectricReading(value, unit, time, alias), PersistableReading {
        override val deviceType = classToDeviceMapping[ExportedPower::class]!!
    }

    class ImportedPower(value: BigDecimal, unit: String, time: LocalDateTime, alias: String)
        : ElectricReading(value, unit, time, alias), PersistableReading {
        override val deviceType = classToDeviceMapping[ImportedPower::class]!!
    }

    class ProducedPower(value: BigDecimal, unit: String, time: LocalDateTime, alias: String)
        : ElectricReading(value, unit, time, alias), PersistableReading {
        override val deviceType = classToDeviceMapping[ProducedPower::class]!!
    }

    class InstantProductionPower(value: BigDecimal, unit: String, time: LocalDateTime, alias: String)
        : ElectricReading(value.coerceIn(BigDecimal.ZERO, BigDecimal.valueOf(Long.MAX_VALUE)), unit, time, alias),
        InstantReading

    class InstantTotalPower(value: BigDecimal, unit: String, time: LocalDateTime, alias: String)
        : ElectricReading(value, unit, time, alias), InstantReading

    class CurrentVoltage(value: BigDecimal, unit: String, time: LocalDateTime, alias: String)
        : ElectricReading(value, unit, time, alias), InstantReading

    companion object {
        private val counter = AtomicLong(0)

        val classToDeviceMapping = mapOf<KClass<out ElectricReading>, DeviceType>(
            ExportedPower::class to DeviceType.POWER_METER_EXPORT,
            ImportedPower::class to DeviceType.POWER_METER_IMPORT,
            ProducedPower::class to DeviceType.POWER_METER_PRODUCTION
        )

        fun fromResponse(alias: String, response: Response, target: Constructor<ElectricReading>) : ElectricReading {
            val readingValue = response.value.toFloat().toBigDecimal()
            val readingTime = Instant.ofEpochSecond(response.time.seconds, response.time.nanos.toLong())
                .atZone(ZoneId.systemDefault())
                .toLocalDateTime()

            return target.newInstance(readingValue, response.unit, readingTime, alias)
        }
    }

}