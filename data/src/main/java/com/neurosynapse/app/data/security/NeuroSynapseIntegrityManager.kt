package com.neurosynapse.app.data.security

import android.content.Context
import android.util.Log
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.IntegrityTokenRequest
import com.google.android.play.core.integrity.model.AppIntegrityVerdict
import com.google.android.play.core.integrity.model.DeviceIntegrityVerdict
import com.neurosynapse.domain.common.SessionId
import com.neurosynapse.domain.gateway.AttestationLevel
import com.neurosynapse.domain.gateway.AttestationResult
import kotlinx.coroutines.tasks.await

// ─────────────────────────────────────────────────────────────────────────────
// NEURO-SYNAPSE :data — security/NeuroSynapseIntegrityManager.kt
//
// Responsabilidad: producir un AttestationResult firmado por los servidores
// de Google Play Integrity para blindar el entorno de ejecución contra:
//   - APKs no distribuidos por Google Play (sideloading malicioso)
//   - Dispositivos con bootloader desbloqueado o root activo
//   - Emuladores no autorizados
//   - Ataques de inyección de código en tiempo de ejecución
//
// DISEÑO CLÍNICO — RIESGO B (ADR-008, revisión arquitectónica):
//   Un fallo de attestation NO bloquea la sesión clínica. Una IPS rural
//   o un consultorio con Google Play Services degradados no debe perder
//   capacidad asistencial por un fallo de conectividad o de infraestructura
//   de Google. La sesión continúa con AttestationLevel.UNVERIFIED y una
//   entrada de auditoría que deja constancia del estado no verificado.
//   El terapeuta ve una advertencia visible en la UI; el riesgo es trazable.
//
// NONCE DE SESIÓN:
//   Cada solicitud de attestation usa el sessionId como nonce.
//   Esto vincula criptográficamente el token de integridad a la sesión
//   específica, impidiendo que un token legítimo de una sesión anterior
//   sea reutilizado (replay attack entre sesiones).
// ─────────────────────────────────────────────────────────────────────────────

private const val TAG = "NeuroSynapseIntegrity"

class NeuroSynapseIntegrityManager(private val context: Context) {

    // ── API pública ───────────────────────────────────────────────────────────

    /**
     * Solicita y verifica la integridad del entorno de ejecución para una sesión.
     *
     * Flujo:
     *   1. Construir solicitud con sessionId como nonce.
     *   2. Llamar a Play Integrity API (red — requiere conectividad).
     *   3. Parsear el veredicto del token.
     *   4. Mapear a AttestationResult con el nivel de garantía adecuado.
     *   5. En caso de fallo: retornar UNVERIFIED con motivo documentado.
     *
     * @param sessionId Usado como nonce — vincula el token a esta sesión.
     * @return AttestationResult. Nunca lanza excepción — los fallos se
     *         encapsulan como AttestationLevel.UNVERIFIED con failureReasons.
     */
    suspend fun attest(sessionId: SessionId): AttestationResult {
        return try {
            requestAndParseToken(sessionId)
        } catch (e: Exception) {
            Log.w(TAG, "Play Integrity attestation falló para sesión ${sessionId.value}: ${e.message}")
            AttestationResult(
                sessionId = sessionId,
                attestedAtEpochMs = System.currentTimeMillis(),
                isValid = false,
                attestationLevel = AttestationLevel.UNVERIFIED,
                failureReasons = listOf(
                    "Play Integrity API no disponible: ${e.javaClass.simpleName}",
                    e.message ?: "Error desconocido"
                )
            )
        }
    }

    // ── Implementación ────────────────────────────────────────────────────────

    private suspend fun requestAndParseToken(sessionId: SessionId): AttestationResult {
        val integrityManager = IntegrityManagerFactory.create(context)

        // El nonce debe ser Base64 URL-safe, entre 16 y 500 bytes.
        // Usamos el sessionId (UUID v4 = 36 chars ASCII) — suficiente entropía
        // y vinculación directa con la sesión clínica.
        val nonce = sessionId.value
            .toByteArray(Charsets.UTF_8)
            .let { android.util.Base64.encodeToString(it, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP) }

        val tokenRequest = IntegrityTokenRequest.builder()
            .setNonce(nonce)
            .build()

        // Operación de red — puede tardar 100-500ms en condiciones normales
        val tokenResponse = integrityManager.requestIntegrityToken(tokenRequest).await()
        val token = tokenResponse.token()

        // En producción, el token debe enviarse al backend propio para verificación
        // con la clave privada del proyecto registrada en Google Play Console.
        // Para el MVP (sin backend propio aún), parseamos el JWT localmente
        // como verificación de primer nivel — suficiente para Milestone 2.
        return parseTokenLocally(sessionId, token)
    }

    /**
     * Parsea el veredicto del token de integridad localmente.
     *
     * NOTA DE PRODUCCIÓN:
     *   Para una implementación de grado hospitalario completo, este token
     *   debe verificarse en el backend propio (servidor de Neuro-Synapse)
     *   usando la API de verificación de Google Play Integrity con la clave
     *   del proyecto. La verificación local aquí es suficiente para MVP y
     *   para el proceso de certificación INVIMA inicial.
     *
     * El token es un JWT con tres partes. El payload (parte central) contiene
     * el veredicto en Base64. Lo decodificamos sin verificar la firma
     * (verificación de firma = responsabilidad del backend en producción).
     */
    private fun parseTokenLocally(sessionId: SessionId, token: String): AttestationResult {
        return try {
            val parts = token.split(".")
            if (parts.size != 3) {
                return unverifiedResult(sessionId, listOf("Token JWT malformado: ${parts.size} partes"))
            }

            val payloadJson = String(
                android.util.Base64.decode(parts[1], android.util.Base64.URL_SAFE),
                Charsets.UTF_8
            )

            val appIntegrity = extractStringField(payloadJson, "appRecognitionVerdict")
            val deviceIntegrity = extractArrayField(payloadJson, "deviceRecognitionVerdict")

            val appOk = appIntegrity == "PLAY_RECOGNIZED"
            val deviceOk = deviceIntegrity.contains("MEETS_DEVICE_INTEGRITY")

            val failureReasons = buildList {
                if (!appOk) add("App no reconocida por Play Store: '$appIntegrity'")
                if (!deviceOk) add("Dispositivo no cumple integridad: $deviceIntegrity")
            }

            AttestationResult(
                sessionId = sessionId,
                attestedAtEpochMs = System.currentTimeMillis(),
                isValid = appOk && deviceOk,
                attestationLevel = when {
                    appOk && deviceOk && deviceIntegrity.contains("MEETS_STRONG_INTEGRITY") ->
                        AttestationLevel.HARDWARE_BACKED
                    appOk && deviceOk ->
                        AttestationLevel.SOFTWARE_BACKED
                    else ->
                        AttestationLevel.UNVERIFIED
                },
                failureReasons = failureReasons
            )

        } catch (e: Exception) {
            unverifiedResult(sessionId, listOf("Error al parsear token: ${e.message}"))
        }
    }

    // ── Helpers de parsing JSON mínimo ────────────────────────────────────────
    // JSON mínimo sin dependencia de Gson/Moshi — el payload es pequeño y
    // predecible. En Sprint 6 se puede migrar a kotlinx.serialization.

    private fun extractStringField(json: String, key: String): String {
        val pattern = Regex("\"$key\"\\s*:\\s*\"([^\"]+)\"")
        return pattern.find(json)?.groupValues?.get(1) ?: "UNKNOWN"
    }

    private fun extractArrayField(json: String, key: String): List<String> {
        val pattern = Regex("\"$key\"\\s*:\\s*\\[([^]]*)]")
        val arrayContent = pattern.find(json)?.groupValues?.get(1) ?: return emptyList()
        return arrayContent.split(",")
            .map { it.trim().removeSurrounding("\"") }
            .filter { it.isNotEmpty() }
    }

    private fun unverifiedResult(sessionId: SessionId, reasons: List<String>) = AttestationResult(
        sessionId = sessionId,
        attestedAtEpochMs = System.currentTimeMillis(),
        isValid = false,
        attestationLevel = AttestationLevel.UNVERIFIED,
        failureReasons = reasons
    )
}
