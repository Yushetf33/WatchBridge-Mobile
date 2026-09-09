package com.tunombre.tvbridge.mobile

import android.content.Context

/**
 * STUB DE COMPILACIÓN — NO es un port real de TrialReminderScheduler.kt de
 * la app de TV.
 *
 * La versión de TV programa avisos de fin de prueba gratuita como trabajos
 * puntuales de androidx.work (WorkManager) — dependencia explícitamente
 * excluida de este proyecto (ver plan, sección 1: "skip WorkManager/
 * leanback/tvprovider... (all TV-only concerns)"). También depende de
 * TrialReminderWorker (no copiado), que a su vez muestra
 * TrialEndingSoonActivity/TrialExpiredActivity — pantallas de UI que no
 * existen todavía en este proyecto (la capa de UI es una fase posterior,
 * fuera del alcance de esta tarea).
 *
 * LicenseManager.kt (copiado tal cual desde la app de TV, con el único
 * diff de deviceType) llama a [scheduleFor]/[cancelAll] desde
 * persistResult() — se dejan aquí como no-op puramente para que ese
 * archivo compile sin modificar su lógica. Pendiente de decisión real en
 * la fase de Compose: los avisos de fin de trial en móvil probablemente
 * deban reimplementarse con AlarmManager + NotificationManager (sin
 * WorkManager), o simplemente omitirse si el flujo de VerifyScreen ya
 * cubre ese caso con la propia UI (ver LicenseManager.dueTrialReminderStage,
 * que sigue funcionando igual, solo sin el aviso en segundo plano).
 */
object TrialReminderScheduler {
    fun scheduleFor(context: Context, trialEndsAt: Long) {
        // No-op a propósito — ver comentario de la clase.
    }

    fun cancelAll(context: Context) {
        // No-op a propósito — ver comentario de la clase.
    }
}
