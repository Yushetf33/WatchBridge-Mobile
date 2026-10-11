package com.tunombre.tvbridge.mobile

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast

/** Botón "Ver"/tocar el aviso de "el programa está a punto de empezar" (ver
 * IptvAlertNotifier) — pide al backend que se lo mande de vuelta a la TV que
 * originó el aviso (targetDeviceId=tvDeviceId), la misma vía que ya usa
 * "Enviar a la TV" desde DetailScreen. goAsync() porque la llamada de red
 * (DeviceApi.sendRemoteOpenIptv) es bloqueante y un BroadcastReceiver normal
 * no puede quedarse esperando — sin esto el proceso podría matarse a medio
 * camino si la app estaba cerrada cuando se tocó la notificación. */
class IptvAlertVerReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val streamUrl = intent.getStringExtra(EXTRA_STREAM_URL) ?: return
        val channelName = intent.getStringExtra(EXTRA_CHANNEL_NAME) ?: return
        val programTitle = intent.getStringExtra(EXTRA_PROGRAM_TITLE)
        val tvDeviceId = intent.getStringExtra(EXTRA_TV_DEVICE_ID) ?: return
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0)

        val email = LicenseManager.getSavedEmail(context)
        if (email == null) {
            Toast.makeText(context, R.string.iptv_alert_no_session, Toast.LENGTH_SHORT).show()
            return
        }

        val appContext = context.applicationContext
        val pendingResult = goAsync()
        Thread {
            try {
                val sent = DeviceApi.sendRemoteOpenIptv(appContext, email, tvDeviceId, streamUrl, channelName, programTitle)
                val messageRes = if (sent) R.string.iptv_alert_sent_toast else R.string.iptv_alert_send_failed_toast
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    Toast.makeText(appContext, messageRes, Toast.LENGTH_SHORT).show()
                }
            } finally {
                (appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(notificationId)
                pendingResult.finish()
            }
        }.start()
    }

    companion object {
        const val EXTRA_STREAM_URL = "stream_url"
        const val EXTRA_CHANNEL_NAME = "channel_name"
        const val EXTRA_PROGRAM_TITLE = "program_title"
        const val EXTRA_TV_DEVICE_ID = "tv_device_id"
        const val EXTRA_NOTIFICATION_ID = "notification_id"
    }
}
