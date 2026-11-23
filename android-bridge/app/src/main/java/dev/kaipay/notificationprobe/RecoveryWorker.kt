package dev.kaipay.notificationprobe

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters

/** Inexact periodic reconciliation repairs a persist-before-enqueue interruption. Android may defer it. */
class RecoveryWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
    override fun doWork(): Result {
        val delivery = LiveDelivery.get(applicationContext)
        if (!delivery.awaitReady()) return Result.retry()
        delivery.reconcile()
        return Result.success()
    }
}
