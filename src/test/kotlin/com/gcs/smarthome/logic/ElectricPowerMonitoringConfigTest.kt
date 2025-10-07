package com.gcs.smarthome.logic

import com.gcs.gRPCModbusAdapter.service.DeviceFunction
import com.gcs.gRPCModbusAdapter.service.Query
import com.gcs.gRPCModbusAdapter.service.Response
import com.gcs.smarthome.config.MonitoringConfiguration
import com.google.protobuf.Timestamp
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.junit.jupiter.SpringExtension
import reactor.core.publisher.Flux
import java.time.Instant
import java.time.ZoneId

@ExtendWith(SpringExtension::class)
@ActiveProfiles("grpc")
@EnableConfigurationProperties(value = [MonitoringConfiguration::class])
@ContextConfiguration(initializers = [ConfigDataApplicationContextInitializer::class], classes = [ElectricPowerMonitoringConfig::class, ElectricPowerMonitoringConfigTest.ScopedConfig::class])
class ElectricPowerMonitoringConfigTest {

    @Autowired
    lateinit var configurationResult: ElectricPowerMonitoringConfig.ConfigurationResult

    @Autowired
    lateinit var electricReadingStream: Flux<ElectricReading>


    @Test
    fun `loaded configuration is valid`() {
        assertTrue(configurationResult.isValid)

        assertThat(configurationResult.mapping)
            .hasSize(11)
            .extracting {
                configurationResult.mapping[Pair("ProductionMeter", DeviceFunction.EXPORT_POWER)]
            }.let {
                assertThat(it).isNotNull
            }
            .extracting{
                configurationResult.mapping[Pair("ProductionMeter", DeviceFunction.CURRENT_POWER)]
            }.let {
                assertThat(it).isNotNull
            }
            .extracting{
                configurationResult.mapping[Pair("ProductionMeter", DeviceFunction.CURRENT_VOLTAGE_PHASE1)]
            }.let {
                assertThat(it).isNotNull
            }
            .extracting{
                configurationResult.mapping[Pair("ProductionMeter", DeviceFunction.CURRENT_VOLTAGE_PHASE2)]
            }.let {
                assertThat(it).isNotNull
            }
            .extracting{
                configurationResult.mapping[Pair("ProductionMeter", DeviceFunction.CURRENT_VOLTAGE_PHASE3)]
            }.let {
                assertThat(it).isNotNull
            }
            .extracting{
                configurationResult.mapping[Pair("MainMeter", DeviceFunction.CURRENT_POWER)]
            }.let {
                assertThat(it).isNotNull
            }
            .extracting{
                configurationResult.mapping[Pair("MainMeter", DeviceFunction.IMPORT_POWER)]
            }.let {
                assertThat(it).isNotNull
            }
            .extracting{
                configurationResult.mapping[Pair("MainMeter", DeviceFunction.EXPORT_POWER)]
            }.let {
                assertThat(it).isNotNull
            }
    }

    @Test
    fun `all responses are converterted to corresponding reading type`() {

        val received = electricReadingStream.collectList().block()!!

        assertThat(received.size).isEqualTo(11)

        val readingTypes : List<Class<*>> = received.map { it.javaClass }.toList()

        assertThat(readingTypes)
            .containsExactly(
                ElectricReading.CurrentVoltage::class.java,
                ElectricReading.CurrentVoltage::class.java,
                ElectricReading.CurrentVoltage::class.java,
                ElectricReading.ImportedPower::class.java,
                ElectricReading.InstantProductionPower::class.java,
                ElectricReading.InstantTotalPower::class.java,
                ElectricReading.ImportedPower::class.java,
                ElectricReading.ExportedPower::class.java,
                ElectricReading.CurrentAmperage::class.java,
                ElectricReading.CurrentAmperage::class.java,
                ElectricReading.CurrentAmperage::class.java,
            )
    }


    @Configuration
    class ScopedConfig {

        @Bean
        fun zoneId() : ZoneId {
            return ZoneId.of("CET")
        }

        @Bean
        fun responseStreamFactory(): (Query) -> Flux<Response> {
            return { Flux
                .fromIterable(arrayListOf(
                    Response.newBuilder()
                        .setTime(Timestamp.newBuilder().setSeconds(Instant.now().epochSecond).build())
                        .setDataType("java.lang.Float")
                        .setDeviceName("ProductionMeter")
                        .setValue("220.1")
                        .setUnit("V")
                        .setFunctionName(DeviceFunction.CURRENT_VOLTAGE_PHASE1)
                        .build(),
                    Response.newBuilder()
                        .setTime(Timestamp.newBuilder().setSeconds(Instant.now().epochSecond).build())
                        .setDataType("java.lang.Float")
                        .setDeviceName("ProductionMeter")
                        .setValue("220.2")
                        .setUnit("V")
                        .setFunctionName(DeviceFunction.CURRENT_VOLTAGE_PHASE2)
                        .build(),
                    Response.newBuilder()
                        .setTime(Timestamp.newBuilder().setSeconds(Instant.now().epochSecond).build())
                        .setDataType("java.lang.Float")
                        .setDeviceName("ProductionMeter")
                        .setValue("220.3")
                        .setUnit("V")
                        .setFunctionName(DeviceFunction.CURRENT_VOLTAGE_PHASE3)
                        .build(),
                    Response.newBuilder()
                        .setTime(Timestamp.newBuilder().setSeconds(Instant.now().epochSecond).build())
                        .setDataType("java.lang.Float")
                        .setDeviceName("ProductionMeter")
                        .setValue("1000.0")
                        .setUnit("Wh")
                        .setFunctionName(DeviceFunction.EXPORT_POWER)
                        .build(),
                    Response.newBuilder()
                        .setTime(Timestamp.newBuilder().setSeconds(Instant.now().epochSecond).build())
                        .setDataType("java.lang.Float")
                        .setDeviceName("ProductionMeter")
                        .setValue("1000.0")
                        .setUnit("W")
                        .setFunctionName(DeviceFunction.CURRENT_POWER)
                        .build(),
                    Response.newBuilder()
                        .setTime(Timestamp.newBuilder().setSeconds(Instant.now().epochSecond).build())
                        .setDataType("java.lang.Float")
                        .setDeviceName("MainMeter")
                        .setValue("1000.0")
                        .setUnit("W")
                        .setFunctionName(DeviceFunction.CURRENT_POWER)
                        .build(),
                    Response.newBuilder()
                        .setTime(Timestamp.newBuilder().setSeconds(Instant.now().epochSecond).build())
                        .setDataType("java.lang.Float")
                        .setDeviceName("MainMeter")
                        .setValue("1000.0")
                        .setUnit("Wh")
                        .setFunctionName(DeviceFunction.IMPORT_POWER)
                        .build(),
                    Response.newBuilder()
                        .setTime(Timestamp.newBuilder().setSeconds(Instant.now().epochSecond).build())
                        .setDataType("java.lang.Float")
                        .setDeviceName("MainMeter")
                        .setValue("1000.0")
                        .setUnit("Wh")
                        .setFunctionName(DeviceFunction.EXPORT_POWER)
                        .build(),
                    Response.newBuilder()
                        .setTime(Timestamp.newBuilder().setSeconds(Instant.now().epochSecond).build())
                        .setDataType("java.lang.Float")
                        .setDeviceName("MainMeter")
                        .setValue("11.22")
                        .setUnit("A")
                        .setFunctionName(DeviceFunction.CURRENT_AMPERAGE_PHASE1)
                        .build(),
                    Response.newBuilder()
                        .setTime(Timestamp.newBuilder().setSeconds(Instant.now().epochSecond).build())
                        .setDataType("java.lang.Float")
                        .setDeviceName("MainMeter")
                        .setValue("22.33")
                        .setUnit("A")
                        .setFunctionName(DeviceFunction.CURRENT_AMPERAGE_PHASE2)
                        .build(),
                    Response.newBuilder()
                        .setTime(Timestamp.newBuilder().setSeconds(Instant.now().epochSecond).build())
                        .setDataType("java.lang.Float")
                        .setDeviceName("MainMeter")
                        .setValue("33.44")
                        .setUnit("A")
                        .setFunctionName(DeviceFunction.CURRENT_AMPERAGE_PHASE3)
                        .build(),
                ))
            }
        }

    }


}