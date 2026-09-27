package com.tunombre.tvbridge.mobile

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import org.json.JSONArray
import org.json.JSONObject

/**
 * Aviso local de "hoy/mañana se estrena X" para lo que el usuario sigue —
 * misma fuente de datos que el Calendario (ver CalendarViewModel.load):
 * series del Historial y de Mi lista, y peliculas de Mi lista, via
 * TmdbClient.fetchSchedule. Comprobado una vez al dia por
 * PremiereCheckReceiver (ver PremiereScheduler), sin backend propio.
 */
object PremiereNotifier {
    private const val PREFS_NAME = "tvbridge_prefs"
    private const val KEY_HIDDEN = "calendar_hidden"
    private const val KEY_NOTIFIED = "premiere_notified"
    private const val NOTIFICATION_CHANNEL_ID = "premieres"

    private val isoFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    /** Bloqueante — llamar desde un hilo de fondo (ver PremiereCheckReceiver).
     * No hace ninguna llamada de red si el usuario tiene las notificaciones
     * desactivadas para la app — esa es la unica forma de desactivar esto,
     * igual que UpdateChecker no tiene interruptor propio en Ajustes. */
    fun checkAndNotify(context: Context) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        val tracked = LinkedHashMap<String, Triple<Int, String, Pair<String, String?>>>()
        for (w in Watchlist.getLocal(context)) tracked.putIfAbsent("${w.mediaPath}:${w.tmdbId}", Triple(w.tmdbId, w.mediaPath, w.title to w.posterPath))
        for (h in RecommendationHistory.getAll(context)) {
            if (h.mediaPath == "tv") tracked.putIfAbsent("tv:${h.tmdbId}", Triple(h.tmdbId, "tv", h.title to h.posterPath))
        }
        if (tracked.isEmpty()) return

        val pool = Executors.newFixedThreadPool(6)
        val events = try {
            tracked.values.take(60).map { (id, path, info) ->
                pool.submit(Callable { TmdbClient.fetchSchedule(id, path, info.first, info.second) })
            }.flatMap { runCatching { it.get() }.getOrDefault(emptyList()) }
        } finally {
            pool.shutdown()
        }

        val hiddenKeys = readHidden(prefs).keys
        val today = isoFormat.format(Date())
        val tomorrow = isoFormat.format(Calendar.getInstance().apply { add(Calendar.DAY_OF_MONTH, 1) }.time)
        val upcoming = events.filter { "${it.mediaPath}:${it.tmdbId}" !in hiddenKeys && (it.date == today || it.date == tomorrow) }
        if (upcoming.isEmpty()) return

        val alreadyNotified = readNotified(prefs)
        val toNotify = upcoming.filter { "${it.mediaPath}:${it.tmdbId}:${it.date}" !in alreadyNotified }
        if (toNotify.isEmpty()) return

        ensureNotificationChannel(context)
        toNotify.forEach { event -> showNotification(context, event, isToday = event.date == today) }

        saveNotified(prefs, alreadyNotified + toNotify.map { "${it.mediaPath}:${it.tmdbId}:${it.date}" }, today)
    }

    private fun showNotification(context: Context, event: TmdbClient.ScheduleEvent, isToday: Boolean) {
        val pendingIntent = PendingIntent.getActivity(
            context,
            "${event.mediaPath}:${event.tmdbId}:${event.date}".hashCode(),
            Intent(context, MainActivity::class.java).apply {
                putExtra(ShareTargetActivity.EXTRA_MEDIA_PATH, event.mediaPath)
                putExtra(ShareTargetActivity.EXTRA_TMDB_ID, event.tmdbId)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = context.getString(
            if (isToday) R.string.premiere_notification_title_today else R.string.premiere_notification_title_tomorrow
        )
        val text = if (event.subtitle != null) {
            context.getString(R.string.premiere_notification_text_episode, event.title, event.subtitle)
        } else {
            context.getString(R.string.premiere_notification_text, event.title)
        }

        val notification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify("${event.mediaPath}:${event.tmdbId}:${event.date}".hashCode(), notification)
    }

    private fun ensureNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (notificationManager.getNotificationChannel(NOTIFICATION_CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            context.getString(R.string.premiere_notification_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT
        )
        notificationManager.createNotificationChannel(channel)
    }

    private fun readHidden(prefs: android.content.SharedPreferences): Map<String, String> = try {
        val json = JSONObject(prefs.getString(KEY_HIDDEN, null) ?: "{}")
        json.keys().asSequence().associateWith { json.optString(it) }
    } catch (e: Exception) {
        emptyMap()
    }

    private fun readNotified(prefs: android.content.SharedPreferences): Set<String> = try {
        val json = JSONArray(prefs.getString(KEY_NOTIFIED, null) ?: "[]")
        (0 until json.length()).map { json.getString(it) }.toSet()
    } catch (e: Exception) {
        emptySet()
    }

    /** Guarda solo lo de hoy en adelante — evita que la lista de "ya
     * avisado" crezca sin limite con estrenos pasados. */
    private fun saveNotified(prefs: android.content.SharedPreferences, all: Set<String>, today: String) {
        val pruned = all.filter { it.substringAfterLast(":") >= today }
        prefs.edit().putString(KEY_NOTIFIED, JSONArray(pruned).toString()).apply()
    }
}
