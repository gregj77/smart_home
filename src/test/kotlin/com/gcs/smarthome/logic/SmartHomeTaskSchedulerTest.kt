package com.gcs.smarthome.logic

import com.gcs.smarthome.config.SchedulerConfiguration
import com.gcs.smarthome.testutils.VirtualTaskScheduler
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

import org.junit.jupiter.api.Assertions.*
import org.springframework.scheduling.TaskScheduler
import reactor.core.scheduler.Scheduler
import reactor.test.scheduler.VirtualTimeScheduler
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.Temporal
import java.time.temporal.TemporalUnit
import java.util.*

internal class SmartHomeTaskSchedulerTest {

    val testTime = OffsetDateTime.parse("2022-01-01T12:00:00Z", DateTimeFormatter.ISO_DATE_TIME).toInstant()

    var victim: SmartHomeTaskScheduler? = null
    var scheduler: VirtualTimeScheduler? = null
    var zoneId: ZoneId? = null

    @BeforeEach
    fun setUp() {
        scheduler = VirtualTimeScheduler.create()
        val taskScheduler = VirtualTaskScheduler(scheduler!!)
        val config = SchedulerConfiguration()
        zoneId = config.zoneId(TimeZone.getDefault())
        victim = SmartHomeTaskScheduler(
            taskScheduler,
            config.localDateProvider(scheduler as Scheduler, zoneId!!),
            config.localTimeProvider(scheduler as Scheduler, zoneId!!),
            config.localDateTimeProvider(scheduler as Scheduler, zoneId!!),
            config.timeZone())
        scheduler!!.advanceTimeTo(testTime)
    }

    @Test
    fun `various time methods on scheduler work`() {
        scheduler!!.advanceTimeBy(Duration.ofSeconds(3665))

        assertThat(victim!!.time).isEqualTo(LocalTime.of(14, 1, 5))
        assertThat(victim!!.date).isEqualTo(LocalDate.of(2022, 1, 1))
        assertThat(victim!!.dateTime).isEqualTo(LocalDateTime.of(2022, 1, 1, 14, 1, 5))
        assertThat(victim!!.timeZone.id).isEqualTo("Europe/Warsaw")
    }

    @Test
    fun `scheduling notifications for first second after midnight works or current time for first notification`() {
        val recordedTimes = mutableListOf<LocalDateTime>()

        victim!!.schedule("1 0 0 * * *", LocalDateTime::class, true).subscribe {
            recordedTimes.add(it)
        }
        scheduler!!.advanceTimeBy(Duration.ofDays(7))

        val expected = listOf<LocalDateTime>(
            testTime.atZone(zoneId!!).toLocalDateTime(),
            LocalDateTime.of(LocalDate.ofInstant(testTime.plus(1, ChronoUnit.DAYS), zoneId!!), LocalTime.of(0, 0, 1)),
            LocalDateTime.of(LocalDate.ofInstant(testTime.plus(2, ChronoUnit.DAYS), zoneId!!), LocalTime.of(0, 0, 1)),
            LocalDateTime.of(LocalDate.ofInstant(testTime.plus(3, ChronoUnit.DAYS), zoneId!!), LocalTime.of(0, 0, 1)),
            LocalDateTime.of(LocalDate.ofInstant(testTime.plus(4, ChronoUnit.DAYS), zoneId!!), LocalTime.of(0, 0, 1)),
            LocalDateTime.of(LocalDate.ofInstant(testTime.plus(5, ChronoUnit.DAYS), zoneId!!), LocalTime.of(0, 0, 1)),
            LocalDateTime.of(LocalDate.ofInstant(testTime.plus(6, ChronoUnit.DAYS), zoneId!!), LocalTime.of(0, 0, 1)),
            LocalDateTime.of(LocalDate.ofInstant(testTime.plus(7, ChronoUnit.DAYS), zoneId!!), LocalTime.of(0, 0, 1)),
        )

        assertThat(recordedTimes)
            .hasSize(8)
            .containsExactlyElementsOf(expected)
    }

    @Test
    fun `scheduling notifications for last second before midnight works`() {
        val recordedTimes = mutableListOf<LocalDateTime>()

        victim!!.schedule("59 59 23 * * *", LocalDateTime::class, false).subscribe {
            recordedTimes.add(it)
        }
        scheduler!!.advanceTimeBy(Duration.ofDays(7))

        val expected = listOf<LocalDateTime>(
            LocalDateTime.of(LocalDate.ofInstant(testTime.plus(0, ChronoUnit.DAYS), zoneId!!), LocalTime.of(23, 59, 59)),
            LocalDateTime.of(LocalDate.ofInstant(testTime.plus(1, ChronoUnit.DAYS), zoneId!!), LocalTime.of(23, 59, 59)),
            LocalDateTime.of(LocalDate.ofInstant(testTime.plus(2, ChronoUnit.DAYS), zoneId!!), LocalTime.of(23, 59, 59)),
            LocalDateTime.of(LocalDate.ofInstant(testTime.plus(3, ChronoUnit.DAYS), zoneId!!), LocalTime.of(23, 59, 59)),
            LocalDateTime.of(LocalDate.ofInstant(testTime.plus(4, ChronoUnit.DAYS), zoneId!!), LocalTime.of(23, 59, 59)),
            LocalDateTime.of(LocalDate.ofInstant(testTime.plus(5, ChronoUnit.DAYS), zoneId!!), LocalTime.of(23, 59, 59)),
            LocalDateTime.of(LocalDate.ofInstant(testTime.plus(6, ChronoUnit.DAYS), zoneId!!), LocalTime.of(23, 59, 59)),
        )

        assertThat(recordedTimes)
            .hasSize(7)
            .containsExactlyElementsOf(expected)
    }

    @Test
    fun `scheduling with LocalDate as expected result works`() {
        val firstResult = victim!!.schedule("* * * * * *", LocalDate::class, true).blockFirst()!!

        assertThat(firstResult)
            .isInstanceOf(LocalDate::class.java)
            .isEqualTo(victim!!.date)
    }

    @Test
    fun `scheduling with LocalTime as expected result works`() {
        val firstResult = victim!!.schedule("* * * * * *", LocalTime::class, true).blockFirst()!!

        assertThat(firstResult)
            .isInstanceOf(LocalTime::class.java)
            .isEqualTo(victim!!.time)
    }

    @Test
    fun `scheduling with LocalDateTime as expected result works`() {
        val firstResult = victim!!.schedule("* * * * * *", LocalDateTime::class, true).blockFirst()!!

        assertThat(firstResult)
            .isInstanceOf(LocalDateTime::class.java)
            .isEqualTo(victim!!.dateTime)
    }

    @Test
    fun `attempt to schedule unsupported temporal time will throw`() {
        assertThrows(IllegalArgumentException::class.java) {
            victim!!.schedule("* * * * * *", Temporal::class, true).blockFirst()!!
        }
    }
}