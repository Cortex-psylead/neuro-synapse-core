package com.neurosynapse.app.data.security

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey

class NeuroSynapseKeyManager(private val context: Context) {

    companion object {
        private const val KEY_ALIAS_BIOMETRIC = "neuro_synapse_biometric_key_v1"
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val SQLCIPHER_KEY_LENGTH_BYTES = 32
        private val DERIVATION_CONTEXT = "NeuroSynapse_v1_Cali_2026".toByteArray(Charsets.UTF_8)
    }

    /**
     * Prepara el motor HMAC protegido por biometría. 
     * El Mac devuelto está "bloqueado" por el hardware hasta que el BiometricPrompt tenga éxito.
     */
    fun prepareMacForBiometricAuth(): Mac {
        val biometricKey = getOrCreateBiometricKey()
        return Mac.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256).apply {
            init(biometricKey)
        }
    }

    /**
     * Deriva la llave final para SQLCipher usando el hardware seguro.
     */
    fun derivePassphraseWithUnlockedMac(unlockedMac: Mac): ByteArray {
        val hmacOutput = unlockedMac.doFinal(DERIVATION_CONTEXT)
        return hmacOutput.take(SQLCIPHER_KEY_LENGTH_BYTES).toByteArray()
    }

    private fun getOrCreateBiometricKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        if (keyStore.containsAlias(KEY_ALIAS_BIOMETRIC)) {
            return keyStore.getKey(KEY_ALIAS_BIOMETRIC, null) as SecretKey
        }

        val keyGenSpec = KeyGenParameterSpec.Builder(
            KEY_ALIAS_BIOMETRIC,
            KeyProperties.PURPOSE_SIGN
        )
            .setDigests(KeyProperties.DIGEST_SHA256)
            // EXIGENCIA CLÍNICA: Solo biometría fuerte (Huella/Rostro certificado), no PIN
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true) 
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && isStrongBoxAvailable()) {
                    setIsStrongBoxBacked(true)
                }
            }
            .build()

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, KEYSTORE_PROVIDER).run {
            init(keyGenSpec)
            generateKey()
        }
    }

    private fun isStrongBoxAvailable(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
}
