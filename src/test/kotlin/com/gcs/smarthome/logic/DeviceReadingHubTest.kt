package com.gcs.smarthome.logic

import com.gcs.smarthome.config.EventingConfiguration
import com.gcs.smarthome.data.model.DeviceReading
import com.gcs.smarthome.data.model.DeviceType
import com.gcs.smarthome.data.repository.DeviceReadingRepository
import com.gcs.smarthome.logic.cqrs.EventPublisher
import com.gcs.smarthome.logic.message.BusinessDayOpenEvent
import com.gcs.smarthome.testutils.JpaConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.junit.jupiter.SpringExtension
import java.math.BigDecimal
import java.time.LocalDateTime

@DataJpaTest
@ExtendWith(SpringExtension::class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration( classes = [ DeviceReadingHubTest.ScopedConfig::class ])
class DeviceReadingHubTest {
    private val testDay = 56.toShort()
    private val testTime = LocalDateTime.of(2022, 1, 15, 12, 0, 0)

    @Autowired
    lateinit var eventPublisher: EventPublisher

    @Autowired
    lateinit var repository : DeviceReadingRepository

    @BeforeEach
    fun setUp() {
    }

    @Test
    @Order(1)
    fun `attempt to save reading without initializing business day fails`() {
        val msg = DeviceReadingHub.commandStoreReading(
            DeviceType.POWER_METER_EXPORT,
            ElectricReading.ImportedPower(BigDecimal.valueOf(12345L), "kWh", testTime, "alias" ))

        assertThrows<IllegalStateException> { eventPublisher.command(msg).block() }
    }

    @Test
    @Order(2)
    fun `attempt to save reading after initializing business day works`() {

        eventPublisher.broadcastEvent(BusinessDayOpenEvent(testDay, testTime.toLocalDate()))

        val msg = DeviceReadingHub.commandStoreReading(
            DeviceType.POWER_METER_EXPORT,
            ElectricReading.ExportedPower(BigDecimal.valueOf(724L), "kWh", testTime, "alias" ))

        val readingId = eventPublisher.command(msg).block()!!

        val reading = repository.findById(readingId)

        assertThat(reading.isPresent).isTrue
        assertThat(reading.get()).let {
            it.extracting(DeviceReading::id).isEqualTo(readingId)
            it.extracting(DeviceReading::businessDayId).isEqualTo(testDay)
            it.extracting(DeviceReading::deviceType).isEqualTo(DeviceType.POWER_METER_EXPORT)
            it.extracting(DeviceReading::value).isEqualTo(BigDecimal.valueOf(724L))
            it.extracting(DeviceReading::createdOnTime).isEqualTo(testTime.toLocalTime())
        }
    }

    @Test
    @Order(3)
    fun `attempt to read latest reading returns 0 if no reading of given type is found`() {

        val msg = DeviceReadingHub.queryLatestDeviceReading(DeviceType.GARDEN_WATER_METER_IMPORT)

        val readingValue = eventPublisher.query(msg).block()!!

        assertThat(readingValue.toLong()).isEqualTo(0L)
    }


    @Test
    @Order(4)
    fun `attempt to read latest import reading returns 2252 for multiple entries in database`() {

        val msg = DeviceReadingHub.queryLatestDeviceReading(DeviceType.POWER_METER_IMPORT)

        val readingValue = eventPublisher.query(msg).block()!!

        assertThat(readingValue).isEqualTo(BigDecimal.valueOf(2252.51))
    }

    @Test
    fun `attempt to read daily delta for day with multiple entries returns valid delta`() {

        val msg = DeviceReadingHub.queryDailyDeltaDeviceReading(DeviceType.POWER_METER_IMPORT, 1)

        val readingValue = eventPublisher.query(msg).block()!!

        assertThat(readingValue).isEqualTo(BigDecimal.valueOf(0.27))
    }

    @Test
    fun `attempt to read daily delta for day with single entry returns 0 as delta`() {

        val msg = DeviceReadingHub.queryDailyDeltaDeviceReading(DeviceType.POWER_METER_EXPORT, 1)

        val readingValue = eventPublisher.query(msg).block()!!

        assertThat(readingValue.toLong()).isEqualTo(0L)
    }

    @Test
    fun `attempt to read daily delta for day without any entry returns 0 as delta`() {

        val msg = DeviceReadingHub.queryDailyDeltaDeviceReading(DeviceType.HOME_WATER_METER_IMPORT, 1)

        val readingValue = eventPublisher.query(msg).block()!!

        assertThat(readingValue.toLong()).isEqualTo(0L)
    }

    @Configuration
    @Import(value = [JpaConfig::class, EventingConfiguration::class, DeviceReadingHub::class])
    class ScopedConfig
}