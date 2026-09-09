package com.tunombre.tvbridge.mobile

import android.content.Context
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Programa de referidos: código propio (mostrado en Ajustes > Suscripción,
 * junto a los QR de pago) y un campo para introducir el de un amigo. Ver
 * /api/referral-code, /api/referral-apply y /api/referral-activate en el
 * backend — los días de bonus solo se conceden cuando el referido usa la
 * app de verdad (ver [reportActivationIfNeeded], llamado desde
 * StremioLauncher.open), no por el simple hecho de introducir el código.
 */
object ReferralManager {
    private const val TAG = "ReferralManager"
    private const val PREFS_NAME = "tvbridge_prefs"
    private const val KEY_ACTIVATION_REPORTED = "referral_activation_reported"

    private val httpClient = sharedHttpClient
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    sealed class ApplyResult {
        object Success : ApplyResult()
        data class Failed(val reason: String) : ApplyResult()
        object NetworkError : ApplyResult()
    }

    /** [paidCount]/[required]: progreso hacia el Lifetime gratis (ver
     * LIFETIME_PURCHASES_REQUIRED en el backend) — cuántos referidos han
     * comprado ya de los que hacen falta. [lifetimeGranted] es true en
     * cuanto se alcanza, aunque [paidCount] ya no siga subiendo después. */
    data class ReferralStats(val code: String, val paidCount: Int, val required: Int, val lifetimeGranted: Boolean)

    /** Bloqueante — llamar desde un hilo de fondo. Null si falla o no hay
     * email verificado todavía. */
    fun fetchOwnCode(context: Context): ReferralStats? {
        val email = LicenseManager.getSavedEmail(context) ?: return null
        val body = JSONObject().apply {
            put("action", "code")
            put("email", email)
        }
        val json = postJson("/api/referral", body) ?: return null
        val code = json.optString("code").takeIf { it.isNotBlank() } ?: return null
        return ReferralStats(
            code = code,
            paidCount = json.optInt("paidCount", 0),
            required = json.optInt("required", 10),
            lifetimeGranted = json.optBoolean("lifetimeGranted", false)
        )
    }

    /** Bloqueante — llamar desde un hilo de fondo. */
    fun applyCode(context: Context, code: String): ApplyResult {
        val email = LicenseManager.getSavedEmail(context) ?: return ApplyResult.Failed("not_verified")
        val deviceId = LicenseManager.getDeviceId(context)
        val body = JSONObject().apply {
            put("action", "apply")
            put("email", email)
            put("deviceId", deviceId)
            put("code", code.trim())
        }
        val json = postJson("/api/referral", body) ?: return ApplyResult.NetworkError
        return if (json.optBoolean("ok", false)) {
            ApplyResult.Success
        } else {
            ApplyResult.Failed(json.optString("reason", "unknown"))
        }
    }

    /** Llamado desde StremioLauncher.open (único punto por el que pasan
     * todos los caminos de apertura: clic normal, Fire TV, voz, Watch Now)
     * — la primera vez que este dispositivo abre de verdad una
     * recomendación, avisa al backend por si fue referido por alguien,
     * para que esa persona reciba sus días de bonus. Mejor esfuerzo, no
     * bloqueante, y solo se intenta una vez por dispositivo en toda su
     * vida (si falla por falta de red, no se reintenta — mismo criterio
     * que Watchlist/RecommendationHistory). */
    fun reportActivationIfNeeded(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_ACTIVATION_REPORTED, false)) return
        prefs.edit().putBoolean(KEY_ACTIVATION_REPORTED, true).apply()

        val deviceId = LicenseManager.getDeviceId(context)
        Thread {
            val body = JSONObject().apply {
                put("action", "activate")
                put("deviceId", deviceId)
            }
            postJson("/api/referral", body)
        }.start()
    }

    private fun postJson(path: String, body: JSONObject): JSONObject? {
        val apiUrl = BuildConfig.LICENSE_API_URL
        if (apiUrl.isBlank()) return null
        val request = Request.Builder()
            .url("${apiUrl.trimEnd('/')}$path")
            .post(body.toString().toRequestBody(jsonMediaType))
            .build()
        return try {
            httpClient.newCall(request).execute().use { response ->
                val responseBody = response.body?.string() ?: return null
                JSONObject(responseBody)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error llamando a $path", e)
            null
        }
    }
}
