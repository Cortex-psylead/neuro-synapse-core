package com.neurosynapse.app.data.security

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.biometric.BiometricPrompt
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey

/**
 * ─────────────────────────────────────────────────────────────────────────────
 * NEURO-SYNAPSE :data — security/NeuroSynapseKeyManager.kt
 *
 * SPRINT 2 EVOLUTION: Enlace biométrico por hardware (StrongBox/TEE).
 *
 * Exige autenticación explícita del terapeuta (AUTH_BIOMETRIC_STRONG) para
 * poder liberar los vectores criptográficos que abren SQLCipher.
 * ─────────────────────────────────────────────────────────────────────────────
 */
class NeuroSynapseKeyManager(private val context: Context) {

    companion object {
        private const val KEY_PROVIDER = "AndroidKeyStore"
        private const val KEY_ALIAS = "NeuroSynapseRootMasterKey"
        private const val HMAC_ALGORITHM = KeyProperties.KEY_ALGORITHM_HMAC_SHA256
        private const val SQLCIPHER_KEY_LENGTH_BYTES = 32

        // Contexto estático de aislamiento de dominio para derivación determinista (ADR-001)
        private val DERIVATION_CONTEXT = "NeuroSynapse_Storage_Salt_2026_v1".toByteArray(Charsets.UTF_8)
    }

    private val keyStore: KeyStore = KeyStore.getInstance(KEY_PROVIDER).apply { load(null) }

    /**
     * Fase 1: Inicializa el motor criptográfico Mac y lo envuelve en un CryptoObject
     * listo para ser inyectado en el BiometricPrompt del sistema operativo.
     */
    fun getBiometricCryptoObject(): BiometricPrompt.CryptoObject {
        try {
            val rootKey = getOrCreateRootKey()
            val mac = Mac.getInstance(HMAC_ALGORITHM)
            mac.init(rootKey)
            return BiometricPrompt.CryptoObject(mac)
        } catch (e: Exception) {
            throw KeyDerivationException("Error al preparar el objeto criptográfico biométrico", e)
        }
    }

    /**
     * Fase 2: Recibe el Mac validado por el hardware biométrico tras el éxito del escaneo
     * y extrae de forma segura los 32 bytes requeridos por SQLCipher.
     */
    fun derivePassphraseWithUnlockedMac(unlockedMac: Mac): ByteArray {
        try {
            val hmacOutput = unlockedMac.doFinal(DERIVATION_CONTEXT)
            check(hmacOutput.size == SQLCIPHER_KEY_LENGTH_BYTES) {
                "HMAC-SHA256 erróneo. Longitud requerida: $SQLCIPHER_KEY_LENGTH_BYTES, Obtenida: ${hmacOutput.size}"
            }
            return hmacOutput
        } catch (e: Exception) {
            throw KeyDerivationException("El hardware denegó la operación: autenticación inválida o corrupta", e)
        }
    }

    /**
     * Generación o recuperación de la clave maestra con políticas estrictas de biometría.
     */
    private fun getOrCreateRootKey(): SecretKey {
        if (keyStore.containsAlias(KEY_ALIAS)) {
            val entry = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
            if (entry != null) return entry.secretKey
            keyStore.deleteEntry(KEY_ALIAS)
        }

        val securityLevel = when {
            isStrongBoxAvailable() -> HardwareSecurityLevel.STRONGBOX
            isTeeAvailable() -> HardwareSecurityLevel.TEE
            else -> HardwareSecurityLevel.SOFTWARE_ONLY
        }

        val builder = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
        ).apply {
            setKeySize(256)
            
            // CRÍTICO SPRINT 2: Forzar bloqueo por hardware biométrico
            setUserAuthenticationRequired(true)
            
            // API 30+ permite especificar biometría fuerte exclusiva (sin PIN/Patrón de bypass fácil)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
            } else {
                @Suppress("DEPRECATION")
                setUserAuthenticationValidityDurationSeconds(-1)
            }

            if (securityLevel == HardwareSecurityLevel.STRONGBOX) {
                setIsStrongBoxBacked(true)
            }
        }

        val keyGenerator = KeyGenerator.getInstance(HMAC_ALGORITHM, KEY_PROVIDER)
        keyGenerator.init(builder.build())
        return keyGenerator.generateKey()
    }

    private fun isStrongBoxAvailable(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)

    private fun isTeeAvailable(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
}

enum class HardwareSecurityLevel { STRONGBOX, TEE, SOFTWARE_ONLY }
class KeyDerivationException(message: String, cause: Throwable? = null) : Exception(message, cause)
