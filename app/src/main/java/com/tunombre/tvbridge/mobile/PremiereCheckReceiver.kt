package com.tunombre.tvbridge.mobile

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Disparado a diario por la alarma de PremiereScheduler — hace la
 * comprobacion (con red) en un hilo aparte, sujeto al tiempo que Android da
 * a un BroadcastReceiver via goAsync(). */
class PremiereCheckReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        Thread {
            try {
                PremiereNotifier.checkAndNotify(context)
            } finally {
                pendingResult.finish()
            }
        }.start()
    }
}
