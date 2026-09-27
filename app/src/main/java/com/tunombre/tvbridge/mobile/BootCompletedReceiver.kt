package com.tunombre.tvbridge.mobile

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Las alarmas de AlarmManager no sobreviven un reinicio del dispositivo —
 * esto vuelve a programar la comprobacion diaria de estrenos (ver
 * PremiereScheduler) sin esperar a que el usuario abra la app. */
class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            PremiereScheduler.schedule(context)
        }
    }
}
