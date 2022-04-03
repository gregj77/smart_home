package com.gcs.smarthome.testutils

import org.springframework.scheduling.TaskScheduler
import org.springframework.scheduling.Trigger
import org.springframework.scheduling.TriggerContext
import org.springframework.scheduling.annotation.AsyncResult
import reactor.test.scheduler.VirtualTimeScheduler
import java.time.Clock
import java.time.Instant
import java.util.*
import java.util.concurrent.Delayed
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class VirtualTaskScheduler(private val scheduler: VirtualTimeScheduler) : TaskScheduler {
    override fun schedule(task: Runnable, trigger: Trigger): ScheduledFuture<*>? {

        val ctx = TestTriggerContext(scheduler)
        val nextExecutionTime = trigger.nextExecutionTime(ctx)!!
        val nextTick = nextExecutionTime.toInstant().toEpochMilli() - scheduler.now(TimeUnit.MILLISECONDS)
        var reschedule :Runnable? = null
        reschedule = Runnable {
            ctx.update(lastActualExecutionTime = Date.from(Instant.ofEpochMilli(scheduler.now(TimeUnit.MILLISECONDS)+1)))
            ctx.update(lastScheduledExecutionTime = Date.from(Instant.ofEpochMilli(scheduler.now(TimeUnit.MILLISECONDS)+1)))
            val tick = trigger.nextExecutionTime(ctx)
            task.run()
            ctx.update(lastCompletionTime = Date.from(Instant.ofEpochMilli(scheduler.now(TimeUnit.MILLISECONDS))))
            val nextTimeToRun = tick!!.toInstant().toEpochMilli() - scheduler.now(TimeUnit.MILLISECONDS)
            scheduler.schedule(reschedule!!, nextTimeToRun, TimeUnit.MILLISECONDS )
        }
        val subscription = scheduler.schedule(
            {
                reschedule.run()
            }, nextTick, TimeUnit.MILLISECONDS
        )
        ctx.update(lastScheduledExecutionTime = Date.from(Instant.ofEpochMilli(scheduler.now(TimeUnit.MILLISECONDS)+1)))

        return SchedulingResult(null, nextTick)
    }

    override fun schedule(task: Runnable, startTime: Date): ScheduledFuture<*> {
        TODO("Not yet implemented")
    }

    override fun scheduleAtFixedRate(task: Runnable, startTime: Date, period: Long): ScheduledFuture<*> {
        TODO("Not yet implemented")
    }

    override fun scheduleAtFixedRate(task: Runnable, period: Long): ScheduledFuture<*> {
        TODO("Not yet implemented")
    }

    override fun scheduleWithFixedDelay(task: Runnable, startTime: Date, delay: Long): ScheduledFuture<*> {
        TODO("Not yet implemented")
    }

    override fun scheduleWithFixedDelay(task: Runnable, delay: Long): ScheduledFuture<*> {
        TODO("Not yet implemented")
    }

    override fun getClock(): Clock {
        return Clock.fixed(Instant.ofEpochMilli(scheduler.now(TimeUnit.MILLISECONDS)), TimeZone.getDefault().toZoneId())
    }
}

class TestTriggerContext(private val scheduler: VirtualTimeScheduler) : TriggerContext {


    private var lastScheduledExecutionTime: Date? = null
    private var lastActualExecutionTime: Date? = null
    private var lastCompletionTime: Date? = null

    override fun lastScheduledExecutionTime(): Date? = lastScheduledExecutionTime

    override fun lastActualExecutionTime(): Date? = lastActualExecutionTime

    override fun lastCompletionTime(): Date? = lastCompletionTime

    override fun getClock(): Clock {
        return Clock.fixed(Instant.ofEpochMilli(scheduler.now(TimeUnit.MILLISECONDS)), TimeZone.getDefault().toZoneId())
    }

    fun update(
        lastScheduledExecutionTime: Date? = null,
        lastActualExecutionTime: Date? = null,
        lastCompletionTime: Date? = null) {

        lastScheduledExecutionTime?.let {
            this.lastScheduledExecutionTime = lastScheduledExecutionTime
        }
        lastActualExecutionTime?.let {
            this.lastActualExecutionTime = lastActualExecutionTime
        }
        lastCompletionTime?.let {
            this.lastCompletionTime = lastCompletionTime
        }
    }
}

class SchedulingResult<T>(result: T, private val delay: Long) : AsyncResult<T>(result), ScheduledFuture<T>  {
    override fun compareTo(other: Delayed): Int {
        return delay.compareTo(other.getDelay(TimeUnit.MILLISECONDS))
    }

    override fun getDelay(unit: TimeUnit): Long {
        return delay
    }

}