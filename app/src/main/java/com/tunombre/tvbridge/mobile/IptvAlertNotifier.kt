package com.tunombre.tvbridge.mobile

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Build
import androidx.core.app.NotificationCompat

/** Construye y muestra el aviso de "el programa está a punto de empezar" que
 * llega por FCM (ver MobileFcmService) — con un botón "Ver" que, al
 * tocarlo, le pide al backend que se lo mande de vuelta a la TV que lo
 * originó (ver IptvAlertVerReceiver/DeviceApi.sendRemoteOpenIptv). Tocar el
 * cuerpo de la notificación hace lo mismo que tocar "Ver" — no hace falta
 * acertar el botón pequeño. */
object IptvAlertNotifier {
    private const val CHANNEL_ID = "iptv_alerts"

    fun show(context: Context, streamUrl: String, channelName: String, programTitle: String?, tvDeviceId: String) {
        ensureChannel(context)

        val id = "$streamUrl#$tvDeviceId".hashCode()
        val verIntent = Intent(context, IptvAlertVerReceiver::class.java).apply {
            putExtra(IptvAlertVerReceiver.EXTRA_STREAM_URL, streamUrl)
            putExtra(IptvAlertVerReceiver.EXTRA_CHANNEL_NAME, channelName)
            putExtra(IptvAlertVerReceiver.EXTRA_PROGRAM_TITLE, programTitle)
            putExtra(IptvAlertVerReceiver.EXTRA_TV_DEVICE_ID, tvDeviceId)
            putExtra(IptvAlertVerReceiver.EXTRA_NOTIFICATION_ID, id)
        }
        val verPendingIntent = PendingIntent.getBroadcast(
            context, id, verIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = if (programTitle != null) {
            context.getString(R.string.iptv_alert_notif_title, programTitle)
        } else {
            context.getString(R.string.iptv_alert_notif_title_generic)
        }

        // El icono PEQUEÑO (arriba a la izquierda, en la barra de estado) el
        // propio Android lo pinta siempre como una silueta plana monocromo
        // — ignora el color del recurso, de ahí el cuadrado genérico que
        // salía usando ic_launcher (a color, sin silueta clara) — ver
        // ic_notification (blanco sobre transparente, generado a mano a
        // partir del logo). El icono GRANDE (a la derecha, el que de
        // verdad se ve) sí respeta el color — ahí va el logo real.
        val largeIcon = BitmapFactory.decodeResource(context.resources, R.mipmap.ic_launcher)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setLargeIcon(largeIcon)
            .setContentTitle(title)
            .setContentText(context.getString(R.string.iptv_alert_notif_text, channelName))
            .setContentIntent(verPendingIntent)
            .addAction(0, context.getString(R.string.iptv_alert_watch_now), verPendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(id, notification)
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (notificationManager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.iptv_alert_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        )
        notificationManager.createNotificationChannel(channel)
    }
}
