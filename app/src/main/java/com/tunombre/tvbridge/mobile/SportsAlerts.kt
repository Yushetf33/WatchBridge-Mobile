package com.tunombre.tvbridge.mobile

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Avisos de un evento deportivo que aun no ha empezado ("Mantener pulsado" en la tarjeta): una notificacion cuando
 * empieza; al tocarla se abre la app en la pestaña IPTV. Se programan con WorkManager (sobreviven a cerrar la app).
 */
object SportsAlerts {
    private const val PREFS = "sports_alerts"
    private const val CHANNEL_ID = "iptv_alerts"
    const val EXTRA_OPEN_IPTV = "open_iptv"

    private fun idFor(channelKey: String, startMs: Long) = "$channelKey#$startMs"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isScheduled(context: Context, channelKey: String, startMs: Long): Boolean =
        idFor(channelKey, startMs) in (prefs(context).getStringSet("ids", emptySet()) ?: emptySet())

    /** Programa (o quita, si ya estaba) el aviso del evento en ese canal. Devuelve true si queda programado. */
    fun toggle(context: Context, channelKey: String, channelName: String, title: String, startMs: Long): Boolean {
        val id = idFor(channelKey, startMs)
        val ids = (prefs(context).getStringSet("ids", emptySet()) ?: emptySet()).toMutableSet()
        val work = WorkManager.getInstance(context)
        if (id in ids) {
            ids.remove(id)
            prefs(context).edit().putStringSet("ids", ids).apply()
            work.cancelUniqueWork("sports_alert_$id")
            return false
        }
        val delay = startMs - System.currentTimeMillis()
        if (delay <= 0) return false
        ids.add(id)
        prefs(context).edit().putStringSet("ids", ids).apply()
        val data = Data.Builder().putString("id", id).putString("channel", channelName).putString("title", title).build()
        work.enqueueUniqueWork(
            "sports_alert_$id", ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<SportsAlertWorker>().setInitialDelay(delay, TimeUnit.MILLISECONDS).setInputData(data).build()
        )
        return true
    }

    internal fun forget(context: Context, id: String) {
        val ids = (prefs(context).getStringSet("ids", emptySet()) ?: emptySet()).toMutableSet()
        if (ids.remove(id)) prefs(context).edit().putStringSet("ids", ids).apply()
    }

    internal fun notify(context: Context, id: String, channelName: String, title: String) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, context.getString(R.string.iptv_alert_channel_name), NotificationManager.IMPORTANCE_HIGH))
        }
        val open = PendingIntent.getActivity(
            context, id.hashCode(),
            Intent(context, MainActivity::class.java).putExtra(EXTRA_OPEN_IPTV, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.iptv_alert_notif_title, title))
            .setContentText(context.getString(R.string.iptv_alert_notif_text, channelName))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        manager.notify(id.hashCode(), notification)
    }
}

class SportsAlertWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getString("id") ?: return Result.success()
        SportsAlerts.notify(applicationContext, id, inputData.getString("channel").orEmpty(), inputData.getString("title").orEmpty())
        SportsAlerts.forget(applicationContext, id)
        return Result.success()
    }
}
