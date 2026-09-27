package com.tunombre.tvbridge.mobile

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.util.Calendar

/**
 * Programa la comprobacion diaria de estrenos (ver PremiereNotifier), sin
 * WorkManager (excluido a proposito de esta app, ver TrialReminderScheduler)
 * — un AlarmManager inexacto es suficiente para un aviso que no necesita
 * hora exacta. Llamar en cada apertura de la app (idempotente: reemplaza la
 * alarma existente) y tras un reinicio del dispositivo (ver
 * BootCompletedReceiver), ya que las alarmas no sobreviven un reinicio.
 */
object PremiereScheduler {
    private const val REQUEST_CODE = 3001
    private const val CHECK_HOUR_OF_DAY = 10

    fun schedule(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.setInexactRepeating(
            AlarmManager.RTC_WAKEUP,
            nextTriggerTime(),
            AlarmManager.INTERVAL_DAY,
            pendingIntent(context)
        )
    }

    private fun nextTriggerTime(): Long {
        val now = Calendar.getInstance()
        val trigger = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, CHECK_HOUR_OF_DAY)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (trigger.before(now)) trigger.add(Calendar.DAY_OF_MONTH, 1)
        return trigger.timeInMillis
    }

    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_CODE,
        Intent(context, PremiereCheckReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
}
