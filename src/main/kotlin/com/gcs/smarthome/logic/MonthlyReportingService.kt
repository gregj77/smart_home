package com.gcs.smarthome.logic

import com.gcs.smarthome.data.model.DeviceReadingMonthlyReport
import com.gcs.smarthome.data.model.DeviceType
import com.gcs.smarthome.data.repository.BusinessDayRepository
import com.google.common.util.concurrent.AtomicDouble
import io.micrometer.core.instrument.ImmutableTag
import io.micrometer.core.instrument.Tags
import mu.KotlinLogging
import org.springframework.stereotype.Service
import reactor.core.Disposables
import reactor.core.publisher.Sinks
import reactor.kotlin.core.publisher.toFlux
import java.time.LocalDate
import javax.annotation.PostConstruct
import javax.annotation.PreDestroy

@Service
class MonthlyReportingService(
    private val businessDayRepository: BusinessDayRepository,
    private val config: ElectricPowerMonitoringConfig.ConfigurationResult,
    private val meterService: MeterService,
    private val taskScheduler: SmartHomeTaskScheduler,
    ) {

    private val logger = KotlinLogging.logger { }

    private val monthlyReadings = Sinks.many()
        .multicast()
        .onBackpressureBuffer<MonthlyReading>()

    private val subscriptions = Disposables.composite()

    @PostConstruct
    fun onInitialize() {
        logger.info { "initializing monthly reporting service" }

        subscriptions.add(monthlyReadings
            .asFlux()
            .groupBy { reading -> Pair(reading.monthAndYear, reading.deviceType) }
            .flatMap { readingStream ->
                val (monthAndYear, deviceType) = readingStream.key()

                logger.info { "initializing monthly report for $monthAndYear for ${config.metricsMapping[deviceType]}" }
                val gaugeName = "monthly_${config.metricsMapping[deviceType]}_total"
                val tags = Tags.of(
                    ImmutableTag(MeterService.tagTypeName, MeterService.tagTypeMonthlyValue),
                    ImmutableTag("date", monthAndYear),
                    ImmutableTag("year", monthAndYear.dropLast(3))
                )

                val (id, storage, _) = meterService.createOrGetGauge(gaugeName, tags ) { AtomicDouble(0.0) }

                val reduceFunction: (AtomicDouble, Double) -> AtomicDouble = { acc, reading ->
                    logger.info { "monthly report for $monthAndYear of $deviceType => $reading (change since last: ${reading - acc.get()})" }
                    acc.set(reading)
                    acc
                }

                readingStream
                    .map(MonthlyReading::value)
                    .reduce(storage, reduceFunction)
                    .doOnCancel {
                        logger.info { "meter for $deviceType[$monthAndYear] is cancelled" }
                        meterService.removeMeters(listOf(id))
                    }
            }
            .subscribe())

        businessDayRepository.loadDeviceMonthlyReport(deviceTypes, null)
            .map(MonthlyReading::fromDeviceReadingMonthlyReport)
            .forEach(monthlyReadings::tryEmitNext)

        subscriptions.add(taskScheduler
            .schedule("7 15,45 * ? * *", LocalDate::class, false)
            .map { it.year * 100 + it.month.value }
            .flatMap { businessDayRepository.loadDeviceMonthlyReport(deviceTypes, it).map(MonthlyReading::fromDeviceReadingMonthlyReport).toFlux() }
            .subscribe(monthlyReadings::tryEmitNext) { err -> logger.error { "failed to load monthly report - ${err.message} <${err.javaClass.name}>\n${err.stackTrace}" } })
    }

    @PreDestroy
    fun onDestroy() {
        subscriptions.dispose()
    }

    private data class MonthlyReading(val monthAndYear: String, val deviceType: DeviceType, val value: Double) {
        companion object {
            fun fromDeviceReadingMonthlyReport(report: DeviceReadingMonthlyReport): MonthlyReading =
                MonthlyReading(
                    "${report.year}-${report.month.format(2)}",
                    report.deviceType,
                    report.value.toDouble())

            fun Number.format(pad: Int): String = "%0${pad}d".format(this)
        }
    }

    companion object {
        private val deviceTypes = listOf(
            DeviceType.POWER_METER_PRODUCTION.ordinal,
            DeviceType.POWER_METER_EXPORT.ordinal,
            DeviceType.POWER_METER_IMPORT.ordinal)
    }
}