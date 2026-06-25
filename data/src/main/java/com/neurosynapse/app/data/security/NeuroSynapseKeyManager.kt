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

// ─────────────────────────────────────────────────────────────────────────────
// NEURO-SYNAPSE :data — security/NeuroSynapseKeyManager.kt
//
// Responsabilidad: derivar de forma determinista y segura los 32 bytes que
// SQLCipher necesita como passphrase en cada arranque de la aplicación.
//
// RESOLUCIÓN ISS-01 — masterKey.encoded retorna null en TEE/StrongBox:
//   SecretKey en AndroidKeyStore es un objeto opaco — la clave nunca abandona
//   el hardware seguro. Intentar .encoded retorna null o lanza excepción.
//   SOLUCIÓN: usar el Keystore como motor HMAC directamente.
//   El HMAC se inicializa con la SecretKey (sin extraerla) y procesa un
//   contexto estático conocido. El resultado (32 bytes) es determinista,
//   reproducible en cada arranque y nunca expone la clave raíz.
//
// RESOLUCIÓN ISS-02 — HMAC key + GCM block mode:
//   Las claves HMAC-SHA256 usan PURPOSE_SIGN | PURPOSE_VERIFY exclusivamente.
//   setBlockModes() y setEncryptionPaddings() no son aplicables y pueden
//   lanzar InvalidAlgorithmParameterException en algunos fabricantes.
//   El KeyGenParameterSpec de la clave HMAC queda sin block modes ni paddings.
//
// DISEÑO DE SEGURIDAD:
//   - StrongBox si está disponible (hardware dedicado, certificación FIPS 140-2)
//   - TEE como fallback (confiable en todos los dispositivos ARM64 modernos)
//   - La clave raíz NUNCA sale del Keystore en ninguna circunstancia
//   - La passphrase derivada existe sólo en RAM durante la apertura de la BD
//   - El salt es un separador de contexto, no una fuente de entropía
//     (la entropía real la provee el Keystore hardware)
// ─────────────────────────────────────────────────────────────────────────────

class NeuroSynapseKeyManager(private val context: Context) {

    // ── Constantes ────────────────────────────────────────────────────────────

    companion object {
        /**
         * Alias de la clave raíz HMAC en AndroidKeyStore.
         * Versionado explícitamente: si en el futuro se rota la clave,
         * el alias nuevo ("neuro_synapse_root_key_v2") garantiza coexistencia
         * durante la migración sin invalidar datos existentes.
         */
        private const val KEY_ALIAS = "neuro_synapse_root_key_v1"

        /**
         * Alias de la clave biométrica — separada de la clave raíz.
         * Esta clave exige setUserAuthenticationRequired(true):
         * sólo se desbloquea después de un BiometricPrompt exitoso.
         * La clave raíz (KEY_ALIAS) no exige auth para poder abrir
         * la BD en el arranque sin interacción del usuario.
         */
        private const val KEY_ALIAS_BIOMETRIC = "neuro_synapse_biometric_key_v1"

        /**
         * Proveedor de AndroidKeyStore — siempre esta cadena exacta.
         */
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"

        /**
         * Salt de contexto para la derivación HMAC.
         *
         * IMPORTANTE (ADR-006B, nota de revisión arquitectónica):
         * Este salt es un SEPARADOR DE CONTEXTO, NO una fuente de entropía.
         * Su propósito es hacer que la salida HMAC sea específica a este uso
         * (apertura de BD de Neuro-Synapse) y no reutilizable para otros
         * propósitos criptográficos con la misma clave raíz.
         * La entropía real del sistema proviene exclusivamente de la clave
         * generada por el hardware del Keystore.
         *
         * No debe cambiarse una vez desplegado en producción: cambiar el salt
         * produce una passphrase diferente, haciendo la BD existente inaccesible.
         */
        private val DERIVATION_CONTEXT: ByteArray =
            "NeuroSynapse_SQLCipher_DB_v1_Colombia_2026".toByteArray(Charsets.UTF_8)

        /**
         * Longitud exacta requerida por SQLCipher para la passphrase raw.
         * SQLCipher la usa directamente como clave AES-256 de página.
         */
        private const val SQLCIPHER_KEY_LENGTH_BYTES = 32
    }

    // ── API pública ───────────────────────────────────────────────────────────

    /**
     * Deriva los 32 bytes de passphrase para SQLCipher.
     *
     * Flujo completo:
     *   1. Obtener o crear la clave raíz HMAC-SHA256 en AndroidKeyStore.
     *   2. Inicializar Mac con la SecretKey (sin extraerla — ISS-01).
     *   3. Ejecutar HMAC sobre DERIVATION_CONTEXT.
     *   4. Truncar a 32 bytes (HMAC-SHA256 produce 32 bytes exactos).
     *   5. Retornar para uso inmediato en SQLCipher.
     *
     * POSTCONDICIÓN: El array retornado debe ser zeorizado por el llamador
     * inmediatamente después de pasarlo a SQLCipher.openOrCreateDatabase().
     * Ver NeuroSynapseDatabase.kt para la implementación del zeroing.
     *
     * @return ByteArray de 32 bytes. El llamador es responsable de zeorizarlo.
     * @throws KeyDerivationException si el Keystore no está disponible o la
     *         clave fue invalidada (backup restore en dispositivo diferente).
     */
    fun deriveSQLCipherPassphrase(): ByteArray {
        return try {
            val rootKey = getOrCreateRootKey()
            derivePassphraseFromKey(rootKey)
        } catch (e: Exception) {
            throw KeyDerivationException(
                "No se pudo derivar la passphrase SQLCipher desde AndroidKeyStore. " +
                "Posibles causas: dispositivo sin TEE, key invalidada por factory reset, " +
                "o restauración de backup en hardware diferente.",
                e
            )
        }
    }

    /**
     * Verifica si la clave raíz ya existe en el Keystore.
     * Útil para detectar primera instalación vs. reinstalación.
     */
    fun rootKeyExists(): Boolean {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        return keyStore.containsAlias(KEY_ALIAS)
    }

    /**
     * Reporta el nivel de seguridad hardware del dispositivo.
     * Usado por AndroidLocalSovereigntyGateway para el AttestationResult.
     */
    fun getHardwareSecurityLevel(): HardwareSecurityLevel {
        return when {
            isStrongBoxAvailable() -> HardwareSecurityLevel.STRONGBOX
            isTeeAvailable()       -> HardwareSecurityLevel.TEE
            else                   -> HardwareSecurityLevel.SOFTWARE_ONLY
        }
    }

    // ── Gestión de la clave raíz ──────────────────────────────────────────────

    /**
     * Obtiene la clave raíz del Keystore, o la crea si no existe.
     *
     * ISS-02 RESUELTO: La especificación de la clave usa EXCLUSIVAMENTE
     * PURPOSE_SIGN y PURPOSE_VERIFY. No hay block modes ni padding modes
     * porque HMAC no es un cifrado de bloque — no los necesita ni los admite.
     * Agregar setBlockModes() en una clave HMAC puede lanzar
     * InvalidAlgorithmParameterException en Samsung Exynos y MediaTek.
     */
    private fun getOrCreateRootKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }

        if (keyStore.containsAlias(KEY_ALIAS)) {
            return keyStore.getKey(KEY_ALIAS, null) as SecretKey
        }

        // La clave no existe — primer arranque o reinstalación limpia
        return createRootKey()
    }

    /**
     * Crea la clave raíz HMAC-SHA256 en AndroidKeyStore.
     *
     * StrongBox vs TEE:
     *   - StrongBox: chip de seguridad dedicado (certificación FIPS 140-2).
     *     Disponible en Pixel 3+, Samsung Galaxy S20+ con Titan M, etc.
     *     Operaciones más lentas (~5-10ms vs ~1ms en TEE) pero mayor garantía.
     *   - TEE (Trusted Execution Environment): aislamiento por software/firmware.
     *     Disponible en todos los dispositivos ARM64 modernos.
     *     Suficiente para los requisitos de la Resolución 3100/2019.
     *
     * Si StrongBox no está disponible, el fallback a TEE es automático y
     * transparente para el resto del sistema.
     */
    private fun createRootKey(): SecretKey {
        val useStrongBox = isStrongBoxAvailable()

        // ISS-02: KeyGenParameterSpec para HMAC — SIN block modes NI padding modes
        val keyGenSpec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            // HMAC usa PURPOSE_SIGN para generar el MAC y PURPOSE_VERIFY para verificarlo.
            // NO usar PURPOSE_ENCRYPT ni PURPOSE_DECRYPT — eso es para cifrado simétrico (AES).
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
        )
            // Algoritmo del Mac que usará esta clave
            .setDigests(KeyProperties.DIGEST_SHA256)
            // Hardware security: StrongBox si disponible, TEE si no
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && useStrongBox) {
                    setIsStrongBoxBacked(true)
                }
            }
            // La clave no expira (la BD de un paciente puede consultarse años después)
            // La rotación se gestiona mediante alias versionado (KEY_ALIAS_v2, etc.)
            .build()

        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_HMAC_SHA256,
            KEYSTORE_PROVIDER
        )
        keyGenerator.init(keyGenSpec)
        return keyGenerator.generateKey()
    }

    // ── Derivación HMAC ───────────────────────────────────────────────────────

    /**
     * ISS-01 RESUELTO: Derivación HMAC sin intentar extraer la clave.
     *
     * La SecretKey de AndroidKeyStore es un objeto opaco. .encoded retorna
     * null en TEE y StrongBox porque la clave nunca abandona el hardware.
     *
     * SOLUCIÓN CORRECTA: inicializar Mac directamente con la SecretKey.
     * El motor HMAC ejecuta el cálculo DENTRO del entorno seguro,
     * retornando sólo los bytes del MAC (no la clave).
     *
     * Propiedades del resultado:
     *   - Determinista: misma clave + mismo contexto = mismo resultado siempre.
     *   - Reproducible: funciona en cada arranque sin almacenar nada extra.
     *   - Portátil: funciona en Pixel, Samsung, Xiaomi, OnePlus sin cambios.
     *   - Seguro: la clave raíz nunca se materializa fuera del hardware.
     */
    private fun derivePassphraseFromKey(rootKey: SecretKey): ByteArray {
        val mac = Mac.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256)

        // init() acepta SecretKey directamente — el Keystore actúa como motor HMAC.
        // NO llamar a rootKey.encoded en ningún caso.
        mac.init(rootKey)

        // doFinal procesa DERIVATION_CONTEXT dentro del entorno seguro
        val hmacOutput = mac.doFinal(DERIVATION_CONTEXT)

        // HMAC-SHA256 produce exactamente 32 bytes — longitud perfecta para SQLCipher AES-256
        check(hmacOutput.size == SQLCIPHER_KEY_LENGTH_BYTES) {
            "HMAC-SHA256 debe producir exactamente 32 bytes. Obtenido: ${hmacOutput.size}"
        }

        return hmacOutput
    }

    // ── Mac para autenticación biométrica (Sprint 2) ──────────────────────────

    /**
     * Prepara un Mac listo para ser envuelto en BiometricPrompt.CryptoObject.
     *
     * La clave biométrica (KEY_ALIAS_BIOMETRIC) exige autenticación strong
     * antes de que el Mac pueda ejecutar doFinal(). El BiometricPrompt
     * completa el desbloqueo cuando onAuthenticationSucceeded() retorna.
     *
     * Separación de responsabilidades:
     *   KEY_ALIAS          → sin auth requerida → abre la BD en el arranque
     *   KEY_ALIAS_BIOMETRIC → requiere auth strong → autoriza operaciones clínicas
     *
     * Si el usuario añade una nueva huella, la clave biométrica se invalida
     * (setInvalidatedByBiometricEnrollment = true) como medida anti-hijacking.
     */
    fun prepareMacForBiometricAuth(): Mac {
        val biometricKey = getOrCreateBiometricKey()
        return Mac.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256).apply {
            init(biometricKey)
        }
    }

    private fun getOrCreateBiometricKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        if (keyStore.containsAlias(KEY_ALIAS_BIOMETRIC)) {
            return keyStore.getKey(KEY_ALIAS_BIOMETRIC, null) as SecretKey
        }
        val keyGenSpec = KeyGenParameterSpec.Builder(
            KEY_ALIAS_BIOMETRIC,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
        )
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setUserAuthenticationRequired(true)
            .setUserAuthenticationParameters(-1, KeyProperties.AUTH_BIOMETRIC_STRONG)
            .setInvalidatedByBiometricEnrollment(true)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && isStrongBoxAvailable()) {
                    setIsStrongBoxBacked(true)
                }
            }
            .build()
        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_HMAC_SHA256, KEYSTORE_PROVIDER
        )
        keyGenerator.init(keyGenSpec)
        return keyGenerator.generateKey()
    }

    // ── Detección de hardware ─────────────────────────────────────────────────

    private fun isStrongBoxAvailable(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)

    private fun isTeeAvailable(): Boolean =
        // TEE está disponible en todos los dispositivos ARM64 con Android 6+
        // que pasan la certificación CDD. Asumimos disponibilidad en API 26+.
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
}

// ── Tipos de soporte ──────────────────────────────────────────────────────────

enum class HardwareSecurityLevel {
    STRONGBOX,      // Chip de seguridad dedicado (FIPS 140-2 certificado)
    TEE,            // Trusted Execution Environment (ARM TrustZone)
    SOFTWARE_ONLY   // Sin hardware seguro — no recomendado para producción clínica
}

class KeyDerivationException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)
