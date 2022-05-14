package com.gcs.smarthome.logic

import com.gcs.smarthome.config.SchedulerConfiguration
import com.gcs.smarthome.data.model.DeviceType
import com.gcs.smarthome.data.model.ReferenceType
import com.gcs.smarthome.logic.message.BusinessDayOpenEvent
import com.gcs.smarthome.logic.message.ElectricReadingEvent
import com.google.common.util.concurrent.AtomicDouble
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.junit.jupiter.SpringExtension
import reactor.test.scheduler.VirtualTimeScheduler
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

@ExtendWith(SpringExtension::class)
@ContextConfiguration(initializers = [ConfigDataApplicationContextInitializer::class], classes = [EnergyStoreReportingServiceTest.ScopedConfig::class])
internal class EnergyStoreReportingServiceTest {

    @Autowired
    private lateinit var victim: EnergyStoreReportingService

    @Autowired
    private lateinit var localDateTimeProvider: () -> LocalDateTime

    @Autowired
    private lateinit var meterService: MeterService

    @Test
    fun `reporting service will calculate days diff between now and last reading`() {
        victim.onBusinessDayStart(BusinessDayOpenEvent(1, localDateTimeProvider().toLocalDate()))
        victim.onNewReferenceElectricityReading(NewReferenceReading(localDateTimeProvider().minusDays(1L), ReferenceType.POWER_READING, BigDecimal.ZERO, 0, BigDecimal.ZERO, 0, BigDecimal.ZERO))

        val meter = meterService.meterById(meterService.listMeterIdsBy(MeterService.filterByNameContaining(EnergyStoreReportingService.DAYS_SINCE_LAST_READING)).single()) as AtomicDouble

        assertThat(meter.get()).isEqualTo(1.0)

        victim.onBusinessDayStart(BusinessDayOpenEvent(2, localDateTimeProvider().toLocalDate().plusDays(1L)))
        assertThat(meter.get()).isEqualTo(2.0)
    }

    @Test
    fun `reporting service will calculate all metrics as soon as data is available`() {

        val storageReading = meterService.meterById(meterService.listMeterIdsBy(MeterService.filterByNameContaining(EnergyStoreReportingService.POWER_STORAGE_READING)).single()) as AtomicDouble
        val totalImport = meterService.meterById(meterService.listMeterIdsBy(MeterService.filterByNameContaining(EnergyStoreReportingService.TOTAL_ELECTRICITY_IMPORTED)).single()) as AtomicDouble
        val totalExport = meterService.meterById(meterService.listMeterIdsBy(MeterService.filterByNameContaining(EnergyStoreReportingService.TOTAL_ELECTRICITY_EXPORTED)).single()) as AtomicDouble
        val totalProduction = meterService.meterById(meterService.listMeterIdsBy(MeterService.filterByNameContaining(EnergyStoreReportingService.TOTAL_ELECTRICITY_PRODUCED)).single()) as AtomicDouble
        val exportSinceReading = meterService.meterById(meterService.listMeterIdsBy(MeterService.filterByNameContaining(EnergyStoreReportingService.ELECTRICITY_EXPORTED_SINCE_LAST_READING)).single()) as AtomicDouble
        val importSinceReading = meterService.meterById(meterService.listMeterIdsBy(MeterService.filterByNameContaining(EnergyStoreReportingService.ELECTRICITY_IMPORTED_SINCE_LAST_READING)).single()) as AtomicDouble

        assertThat(storageReading.get()).isEqualTo(0.0)
        assertThat(totalImport.get()).isEqualTo(0.0)
        assertThat(totalExport.get()).isEqualTo(0.0)
        assertThat(totalProduction.get()).isEqualTo(0.0)
        assertThat(exportSinceReading.get()).isEqualTo(0.0)
        assertThat(importSinceReading.get()).isEqualTo(0.0)

        victim.onNewMeterReading(ElectricReadingEvent(DeviceType.POWER_METER_IMPORT, "", 100.0, 0.0, localDateTimeProvider()))
        victim.onNewMeterReading(ElectricReadingEvent(DeviceType.POWER_METER_EXPORT, "", 50.0, 0.0, localDateTimeProvider()))
        victim.onNewMeterReading(ElectricReadingEvent(DeviceType.POWER_METER_PRODUCTION, "", 25.0, 0.0, localDateTimeProvider()))

        assertThat(storageReading.get()).isEqualTo(0.0)
        assertThat(totalImport.get()).isEqualTo(100.0)
        assertThat(totalExport.get()).isEqualTo(50.0)
        assertThat(totalProduction.get()).isEqualTo(25.0)
        assertThat(exportSinceReading.get()).isEqualTo(50.0)
        assertThat(importSinceReading.get()).isEqualTo(100.0)

        victim.onNewReferenceElectricityReading(NewReferenceReading(localDateTimeProvider(), ReferenceType.POWER_READING, BigDecimal.valueOf(10.0), 1, BigDecimal.valueOf(1.0), 2, BigDecimal.valueOf(2.0)))

        assertThat(storageReading.get()).isEqualTo(10.0)
        assertThat(totalImport.get()).isEqualTo(100.0)
        assertThat(totalExport.get()).isEqualTo(50.0)
        assertThat(totalProduction.get()).isEqualTo(25.0)
        assertThat(exportSinceReading.get()).isEqualTo(48.0)
        assertThat(importSinceReading.get()).isEqualTo(99.0)

    }

    @TestConfiguration
    @Import(value = [
        MeterService::class,
        SimpleMeterRegistry::class,
        SchedulerConfiguration::class,
        EnergyStoreReportingService::class
    ])
    class ScopedConfig {
        private var scheduler: VirtualTimeScheduler = VirtualTimeScheduler.create()

        @Bean
        fun scheduler(tz: ZoneId) : VirtualTimeScheduler {
            scheduler.advanceTimeTo(LocalDateTime.of(2022, 1, 1, 13, 0,0).toInstant(ZoneOffset.UTC))
            return scheduler
        }
    }
}