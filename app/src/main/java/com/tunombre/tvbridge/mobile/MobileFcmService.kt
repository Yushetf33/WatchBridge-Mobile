package com.tunombre.tvbridge.mobile

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/** Recibe el aviso de "el programa está a punto de empezar" que manda la TV
 * al terminar un canal con alerta programada (ver
 * TvRecommendationBridge/IptvAlertWorker.doWork → DeviceApi.sendAlertPush en
 * la app de TV, y "send_alert_push" en el backend) — mensaje solo de datos
 * (sin "notification"), para que esto se llame SIEMPRE, con la app en
 * primer plano, segundo plano o cerrada del todo, y sea esta clase quien
 * decida cómo mostrarlo (con el botón "Ver", ver IptvAlertNotifier), no el
 * sistema por su cuenta. */
class MobileFcmService : FirebaseMessagingService() {

    /** Se llama con el primer token del dispositivo y cada vez que Firebase
     * lo rota — MainActivity también pide el token actual al arrancar por
     * si Firebase ya lo había generado antes de que este servicio empezara
     * a escuchar (mismo patrón que TvBridgeFcmService en la app de TV). */
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        LicenseManager.getSavedEmail(this)?.let { email ->
            Thread { DeviceApi.registerFcmToken(this, email, token) }.start()
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        if (message.data["type"] != "iptv_alert") return
        val streamUrl = message.data["streamUrl"] ?: return
        val channelName = message.data["channelName"] ?: return
        val tvDeviceId = message.data["tvDeviceId"] ?: return
        val programTitle = message.data["programTitle"]
        IptvAlertNotifier.show(applicationContext, streamUrl, channelName, programTitle, tvDeviceId)
    }
}
