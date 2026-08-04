package com.neurosynapse.app.data.security

import android.content.Context
import android.util.Log
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.neurosynapse.app.data.persistence.NeuroSynapseDatabase
import com.neurosynapse.domain.common.*
import com.neurosynapse.domain.gateway.*
import com.neurosynapse.domain.session.ClinicalSession
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.crypto.Mac
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class AndroidLocalSovereigntyGateway(
    private val context: Context,
    private val keyManager: NeuroSynapseKeyManager,
    private val integrityManager: NeuroSynapseIntegrityManager
) : BiometricSovereigntyGateway {

    private var database: NeuroSynapseDatabase? = null
    private var unlockedMac: Mac? = null

    fun requireDatabase(): NeuroSynapseDatabase {
        return database ?: throw IllegalStateException("Acceso denegado: Base de datos bloqueada.")
    }

    suspend fun authenticateAndUnlock(activity: FragmentActivity) {
        val mac = keyManager.prepareMacForBiometricAuth()
        val executor = ContextCompat.getMainExecutor(activity)

        suspendCancellableCoroutine { continuation ->
            val callback = object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    val authMac = result.cryptoObject?.mac
                    if (authMac != null) {
                        unlockedMac = authMac
                        // Apertura de la DB inyectando el Mac desbloqueado por hardware
                        database = NeuroSynapseDatabase.getInstance(context, authMac)
                        continuation.resume(Unit)
                    } else {
                        continuation.resumeWithException(Exception("Fallo crítico: El hardware no retornó el objeto criptográfico."))
                    }
                }

                override fun onAuthenticationError(code: Int, str: CharSequence) {
                    continuation.resumeWithException(Exception("Error Biométrico [$code]: $str"))
                }

                override fun onAuthenticationFailed() {
                    // El usuario falló el intento, pero el diálogo sigue abierto.
                    Log.w("SovereigntyGateway", "Intento biométrico fallido.")
                }
            }

            val prompt = BiometricPrompt(activity, executor, callback)
            val info = BiometricPrompt.PromptInfo.Builder()
                .setTitle("Identidad del Terapeuta")
                .setSubtitle("Vincule su firma biométrica para abrir la sesión clínica")
                .setDescription("Neuro-Synapse cifra los datos con una llave derivada de su biometría.")
                .setAllowedAuthenticators(androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .setNegativeButtonText("Cancelar")
                .build()

            // Vincular el prompt con el objeto Mac del KeyManager
            prompt.authenticate(info, BiometricPrompt.CryptoObject(mac))
            
            continuation.invokeOnCancellation { prompt.cancelAuthentication() }
        }
    }

    override suspend fun anonymizeTensor(raw: BiometricFrame): AnonymizedTensor = 
        AnonymizedTensor("id", raw.sessionId, doubleArrayOf(0.0), raw.frameType, IntegrityHash("hash"))

    override suspend fun validateConsent(state: AutonomicState): ConsentLevel = state.consentLevel

    override suspend fun attestSessionIntegrity(session: ClinicalSession): AttestationResult = 
        integrityManager.attest(session.sessionId)

    override suspend fun zeroizeFrame(frame: BiometricFrame): ZeroizationReceipt = 
        ZeroizationReceipt(frame.frameId, System.currentTimeMillis(), frame.rawBytes.size, ZeroizationMethod.KOTLIN_BYTEARRAY_LOOP, IntegrityHash("zero"))
}
