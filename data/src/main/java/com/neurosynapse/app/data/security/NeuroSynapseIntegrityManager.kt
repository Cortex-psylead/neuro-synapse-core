package com.neurosynapse.app.data.security

import android.content.Context
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.IntegrityTokenRequest
import com.google.android.play.core.integrity.IntegrityTokenResponse
import kotlinx.coroutines.tasks.await
import java.security.MessageDigest
import java.util.Base64

/**
 * ─────────────────────────────────────────────────────────────────────────────
 * NEURO-SYNAPSE :data — security/NeuroSynapseIntegrityManager.kt
 *
 * SPRINT 2: Interceptor de confianza del entorno de ejecución (Play Integrity).
 *
 * Mitiga ataques de inyección de código en memoria, ingeniería inversa y
 * dispositivos comprometidos (Root/Custom ROMs no firmadas) antes de liberar
 * la pasarela criptográfica.
 * ─────────────────────────────────────────────────────────────────────────────
 */
class NeuroSynapseIntegrityManager(private val context: Context) {

    private val integrityManager = IntegrityManagerFactory.create(context)

    companion object {
        // Proyecto Cloud vinculado a Neuro-Synapse Core para la verificación de atestación
        private const val CLOUD_PROJECT_NUMBER = 1000267758L 
    }

    /**
     * Solicita un veredicto de integridad a Google Play Services encadenando
     * un nonce determinista basado en el identificador de la sesión actual.
     * Esto evita ataques de repetición (Replay Attacks).
     *
     * @param sessionId Identificador de la sesión clínica activa usado como ancla.
     * @return El token de integridad en formato JWT firmado por Google.
     */
    suspend fun requestEnvironmentAttestation(sessionId: String): String {
        try {
            val nonce = generateSecureNonce(sessionId)
            
            val tokenRequest = IntegrityTokenRequest.builder()
                .setCloudProjectNumber(CLOUD_PROJECT_NUMBER)
                .setNonce(nonce)
                .build()

            val response: IntegrityTokenResponse = integrityManager.requestIntegrityToken(tokenRequest).await()
            return response.token()
        } catch (e: Exception) {
            throw IntegrityAttestationException("Fallo crítico en el handshake de Play Integrity API", e)
        }
    }

    /**
     * Genera un nonce criptográfico SHA-256 formateado en Base64 URL-Safe sin relleno.
     * Cumple con la especificación estricta de Google para la atestación de hardware.
     */
    private fun generateSecureNonce(sessionId: String): String {
        return try {
            val rawContext = "$sessionId:${System.currentTimeMillis()}"
            val digest = MessageDigest.getInstance("SHA-256")
            val hashBytes = digest.digest(rawContext.toByteArray(Charsets.UTF_8))
            
            // Requisito de Play Integrity: Base64 URL-Safe sin padding (=)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                Base64.getUrlEncoder().withoutPadding().encodeToString(hashBytes)
            } else {
                android.util.Base64.encodeToString(hashBytes, android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP)
            }
        } catch (e: Exception) {
            throw IntegrityAttestationException("Error interno al computar el vector de aleatoriedad (Nonce)", e)
        }
    }
}

class IntegrityAttestationException(message: String, cause: Throwable? = null) : Exception(message, cause)
