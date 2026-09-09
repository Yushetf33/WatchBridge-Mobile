package com.tunombre.tvbridge.mobile

import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Puerto de UpdateChecker de la app de TV, apuntando al repo público de
 * descargas del móvil en vez del de TV. A diferencia de la TV (que se
 * enciende/apaga muchas veces sin abrir realmente la app y necesita
 * WorkManager + el disparador de ACTION_SCREEN_ON), aquí no hace falta
 * chequeo periódico en segundo plano: basta con comprobar cada vez que el
 * usuario abre la app (ver TvBridgeApp), con el mismo límite de frecuencia
 * para no machacar la API de GitHub si la abre varias veces seguidas.
 */
object UpdateChecker {

    private const val TAG = "UpdateChecker"
    private const val RELEASES_LATEST_URL =
        "https://api.github.com/repos/Yushetf33/TvRecommendationBridge-mobile-public/releases/latest"
    private const val APK_ASSET_NAME = "app-release.apk"
    private const val DOWNLOAD_FILE_NAME = "tvbridge-mobile-update.apk"
    private const val PREFS_NAME = "tvbridge_update"
    private const val KEY_DOWNLOAD_ID = "download_id"
    private const val KEY_DOWNLOAD_VERSION = "download_version"
    private const val NOTIFICATION_CHANNEL_ID = "updates"
    private const val NOTIFICATION_ID = 1001
    private const val KEY_LAST_CHECK_AT = "last_check_at"

    private val MIN_CHECK_INTERVAL_MS = TimeUnit.HOURS.toMillis(4)

    private const val DOWNLOAD_POLL_TIMEOUT_MS = 2 * 60 * 1000L
    private const val DOWNLOAD_POLL_INTERVAL_MS = 500L

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    /** Resultado de una comprobación manual (ver [checkNow]) — a diferencia
     * de [checkAndDownloadIfNewer] (en segundo plano, sin nada que mostrar),
     * esta sí necesita devolver algo que enseñarle al usuario. */
    sealed class CheckOutcome {
        object UpToDate : CheckOutcome()
        data class DownloadStarted(val version: String) : CheckOutcome()
        object NetworkError : CheckOutcome()
    }

    /** Comprueba la última release y, si es más nueva que la instalada,
     * lanza la descarga en segundo plano. Bloqueante — llamar desde un hilo
     * de fondo. No hace nada si ya se comprobó hace menos de
     * [MIN_CHECK_INTERVAL_MS] — pensado para llamarse en cada apertura de
     * la app (ver TvBridgeApp) sin machacar la API de GitHub. */
    fun checkAndDownloadIfNewer(context: Context) {
        val prefs = prefs(context)
        val lastCheckAt = prefs.getLong(KEY_LAST_CHECK_AT, 0L)
        if (System.currentTimeMillis() - lastCheckAt < MIN_CHECK_INTERVAL_MS) {
            Log.d(TAG, "Chequeo omitido: ya se comprobó hace menos de ${MIN_CHECK_INTERVAL_MS / 60000} min")
            return
        }
        checkNow(context)
    }

    /** Igual que [checkAndDownloadIfNewer], pero ignora el límite de
     * frecuencia (para un botón de "buscar actualización" explícito) y
     * devuelve qué ha pasado, para poder avisar al usuario. Bloqueante —
     * llamar desde un hilo de fondo. */
    fun checkNow(context: Context): CheckOutcome {
        prefs(context).edit().putLong(KEY_LAST_CHECK_AT, System.currentTimeMillis()).apply()

        val release = fetchLatestRelease() ?: return CheckOutcome.NetworkError
        val latestVersion = release.tagName.removePrefix("v")
        if (!isNewer(latestVersion, BuildConfig.VERSION_NAME)) {
            Log.d(TAG, "Ya en la última versión ($latestVersion)")
            return CheckOutcome.UpToDate
        }
        if (release.apkUrl == null) {
            Log.w(TAG, "Release $latestVersion no tiene asset $APK_ASSET_NAME")
            return CheckOutcome.NetworkError
        }
        startDownload(context, release.apkUrl, latestVersion)
        return CheckOutcome.DownloadStarted(latestVersion)
    }

    /** Llamado por [UpdateDownloadReceiver] cuando DownloadManager termina
     * una descarga (comprueba que sea la nuestra antes de notificar). */
    fun onDownloadComplete(context: Context, completedDownloadId: Long) {
        val prefs = prefs(context)
        val ourDownloadId = prefs.getLong(KEY_DOWNLOAD_ID, -1L)
        if (completedDownloadId != ourDownloadId) return

        val version = prefs.getString(KEY_DOWNLOAD_VERSION, null) ?: return
        val file = downloadedFile(context)
        if (!file.exists()) return

        showInstallNotification(context, file, version)
    }

    /** Complemento de [checkNow] para el botón manual de "Buscar
     * actualización": espera a que la descarga iniciada por [checkNow]
     * termine y lanza el instalador directamente, ya que el usuario lo ha
     * pedido en primer plano y puede quedarse esperando (a diferencia de la
     * notificación de [onDownloadComplete], pensada para cuando el chequeo
     * automático descubre la actualización con la app en background). No
     * hace nada si [checkNow] no llegó a iniciar una descarga. Bloqueante —
     * llamar desde un hilo de fondo, después de [checkNow]. */
    fun awaitDownloadAndInstall(context: Context) {
        val downloadId = prefs(context).getLong(KEY_DOWNLOAD_ID, -1L)
        if (downloadId == -1L || !awaitDownloadSuccess(context, downloadId)) return
        val file = downloadedFile(context)
        if (!file.exists()) return
        context.startActivity(buildInstallIntent(context, file))
    }

    private fun awaitDownloadSuccess(context: Context, downloadId: Long): Boolean {
        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val deadline = System.currentTimeMillis() + DOWNLOAD_POLL_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            downloadManager.query(DownloadManager.Query().setFilterById(downloadId)).use { cursor ->
                if (cursor.moveToFirst()) {
                    when (cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))) {
                        DownloadManager.STATUS_SUCCESSFUL -> return true
                        DownloadManager.STATUS_FAILED -> return false
                    }
                }
            }
            Thread.sleep(DOWNLOAD_POLL_INTERVAL_MS)
        }
        return false
    }

    private data class LatestRelease(val tagName: String, val apkUrl: String?)

    private fun fetchLatestRelease(): LatestRelease? {
        val request = Request.Builder().url(RELEASES_LATEST_URL).build()
        return try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "GitHub respondió ${response.code} comprobando actualizaciones")
                    return null
                }
                val body = response.body?.string() ?: return null
                val json = JSONObject(body)
                val tagName = json.optString("tag_name", "")
                if (tagName.isBlank()) return null
                var apkUrl: String? = null
                json.optJSONArray("assets")?.let { assets ->
                    for (i in 0 until assets.length()) {
                        val asset = assets.getJSONObject(i)
                        if (asset.optString("name") == APK_ASSET_NAME) {
                            apkUrl = asset.optString("browser_download_url")
                        }
                    }
                }
                LatestRelease(tagName, apkUrl)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error comprobando actualizaciones", e)
            null
        }
    }

    private fun isNewer(remote: String, local: String): Boolean {
        val remoteParts = remote.split(".").map { it.toIntOrNull() ?: 0 }
        val localParts = local.split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(remoteParts.size, localParts.size)) {
            val r = remoteParts.getOrElse(i) { 0 }
            val l = localParts.getOrElse(i) { 0 }
            if (r != l) return r > l
        }
        return false
    }

    private fun downloadedFile(context: Context): File =
        File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), DOWNLOAD_FILE_NAME)

    private fun startDownload(context: Context, apkUrl: String, version: String) {
        downloadedFile(context).let { if (it.exists()) it.delete() }

        val request = DownloadManager.Request(Uri.parse(apkUrl))
            .setTitle(context.getString(R.string.update_notification_downloading_title))
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, DOWNLOAD_FILE_NAME)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_HIDDEN)

        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val downloadId = downloadManager.enqueue(request)
        prefs(context).edit()
            .putLong(KEY_DOWNLOAD_ID, downloadId)
            .putString(KEY_DOWNLOAD_VERSION, version)
            .apply()
        Log.d(TAG, "Descargando actualización $version (downloadId=$downloadId)")
    }

    private fun buildInstallIntent(context: Context, apkFile: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun showInstallNotification(context: Context, apkFile: File, version: String) {
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            buildInstallIntent(context, apkFile),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        ensureNotificationChannel(context)

        val notification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(context.getString(R.string.update_notification_ready_title))
            .setContentText(context.getString(R.string.update_notification_ready_text, version))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun ensureNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (notificationManager.getNotificationChannel(NOTIFICATION_CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            context.getString(R.string.update_notification_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT
        )
        notificationManager.createNotificationChannel(channel)
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
