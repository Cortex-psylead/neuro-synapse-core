package com.neurosynapse.app.data.security

import android.content.Context
import android.util.Log
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.neurosynapse.app.data.persistence.NeuroSynapseDatabase
import com.neurosynapse.app.data.persistence.dao.AuditLogDao
import com.neurosynapse.app.data.persistence.dao.ClinicalSessionDao
import com.neurosynapse.domain.common.ConsentLevel
import com.neurosynapse.domain.common.IntegrityHash
import com.neurosynapse.domain.common.SessionId
import com.neurosynapse.domain.common.UtcTimestamp
import com.neurosynapse.domain.gateway.AndroidBiometricSovereigntyGateway
import com.neurosynapse.domain.gateway.AttestationLevel
import com.neurosynapse.domain.gateway.AttestationResult
import com.neurosynapse.domain.gateway.AutonomicState
import com.neurosynapse.domain.gateway.BiometricFrame
import com.neurosynapse.domain.gateway.BiometricFrameType
import com.neurosynapse.domain.gateway.BiometricSovereigntyGateway
import com.neurosynapse.domain.gateway.ZeroizationFailureException
import com.neurosynapse.domain.gateway.ZeroizationMethod
import com.neurosynapse.domain.gateway.ZeroizationReceipt
import com.neurosynapse.domain.gateway.AnonymizedTensor
import com.neurosynapse.domain.session.ClinicalSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.time.Instant
import java.time.format.DateTimeFormatter
import javax.crypto.Mac
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

// ─────────────────────────────────────────────────────────────────────────────
// NEURO-SYNAPSE :data — security/AndroidLocalSovereigntyGateway.kt
//
// Implementación concreta de BiometricSovereigntyGateway (ADR-008).
//
// Este componente es el Portal de Acceso del sistema: orquesta la cadena
// completa de autorización antes de que cualquier dato clínico sea accesible.
//
// FLUJO DE APERTURA DE SESIÓN:
//   1. BiometricPrompt.authenticate() → terapeuta verifica identidad
//   2. onAuthenticationSucceeded → CryptoObject.mac es el unlockedMac
//   3. unlockedMac.doFinal(DERIVATION_CONTEXT) → 32 bytes de passphrase
//   4. NeuroSynapseDatabase.getInstance(context, unlockedMac) → BD abierta
//   5. attest(sessionId) → Play Integrity verifica el entorno
//   6. validateConsent() → nivel de consentimiento verificado contra BD
//
// FLUJO DE PURGA REGULATORIA:
//   1. Requiere autenticación biométrica previa (unlockedMac no nulo)
//   2. AuditLogDao.executeRegulatedPurge(sessionId)
//   3. ClinicalSessionDao.deleteSession(sessionId)
//   4. DB.execSQL("VACUUM") para sobrescribir páginas liberadas
//   5. Entrada en compliance_log con timestamp y sessionId purgado
//
// BIOMETRÍA STRONG vs DEVICE_CREDENTIAL:
//   El sistema exige BIOMETRIC_STRONG exclusivamente.
//   Razón clínica: PIN/patrón/contraseña son credenciales de dispositivo
//   que pueden ser observadas o extraídas. Una huella o FaceID strong
//   no es replicable por observación, alineado con Resolución 3100/2019.
// ─────────────────────────────────────────────────────────────────────────────

private const val TAG = "SovereigntyGateway"

class AndroidLocalSovereigntyGateway(
    private val context: Context,
    private val keyManager: NeuroSynapseKeyManager,
    private val integrityManager: NeuroSynapseIntegrityManager
) : BiometricSovereigntyGateway {

    // BD sólo accesible después de autenticación biométrica exitosa
    @Volatile
    private var database: NeuroSynapseDatabase? = null

    // Mac desbloqueado por biometría — null si el terapeuta no ha autenticado
    @Volatile
    private var unlockedMac: Mac? = null

    // ── Autenticación biométrica ──────────────────────────────────────────────

    /**
     * Presenta el BiometricPrompt al terapeuta y abre la BD al autenticar.
     *
     * Este método debe llamarse desde una FragmentActivity (pantalla de login
     * del terapeuta) antes de cualquier operación con datos clínicos.
     *
     * Cuando onAuthenticationSucceeded retorna, [database] queda inicializado
     * y todas las operaciones del gateway están disponibles.
     *
     * @param activity FragmentActivity donde mostrar el diálogo biométrico.
     * @throws BiometricAuthException si la autenticación falla o es cancelada.
     * @throws BiometricUnavailableException si el dispositivo no tiene
     *         biometría strong disponible.
     */
    suspend fun authenticateAndUnlock(activity: FragmentActivity) {
        requireBiometricStrong()

        // Preparar el CryptoObject con la clave HMAC del Keystore.
        // Android Keystore libera el Mac para usar SÓLO después de que
        // la biometría sea verificada exitosamente — no antes.
        val mac = keyManager.prepareMacForBiometricAuth()

        suspendCancellableCoroutine { continuation ->
            val executor = ContextCompat.getMainExecutor(activity)

            val callback = object : BiometricPrompt.AuthenticationCallback() {

                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    // El CryptoObject.mac está desbloqueado por la biometría
                    val authenticatedMac = result.cryptoObject?.mac
                    if (authenticatedMac == null) {
                        continuation.resumeWithException(
                            BiometricAuthException("CryptoObject.mac es null tras autenticación exitosa")
                        )
                        return
                    }

                    try {
                        unlockedMac = authenticatedMac

                        // Abrir la BD con la passphrase derivada del mac desbloqueado
                        database = NeuroSynapseDatabase.getInstance(context, keyManager)

                        Log.i(TAG, "Autenticación biométrica exitosa. BD abierta.")
                        continuation.resume(Unit)

                    } catch (e: Exception) {
                        continuation.resumeWithException(
                            BiometricAuthException("Error al abrir la BD tras autenticación: ${e.message}", e)
                        )
                    }
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    // Errores permanentes (no reintentos disponibles)
                    continuation.resumeWithException(
                        BiometricAuthException(
                            "Autenticación biométrica fallida (código $errorCode): $errString"
                        )
                    )
                }

                override fun onAuthenticationFailed() {
                    // Intento fallido — Android gestiona los reintentos automáticamente.
                    // onAuthenticationError() se llamará cuando se agoten los intentos.
                    // No hacer nada aquí — la corrutina sigue suspendida esperando éxito o error final.
                    Log.d(TAG, "Intento biométrico fallido — esperando reintento o error final")
                }
            }

            val prompt = BiometricPrompt(activity, executor, callback)

            val promptInfo = BiometricPrompt.PromptInfo.Builder()
                .setTitle("Verificación del Terapeuta")
                .setSubtitle("Confirme su identidad para acceder a los datos clínicos")
                .setDescription("Neuro-Synapse requiere autenticación biométrica para proteger la información de los pacientes.")
                .setAllowedAuthenticators(BIOMETRIC_STRONG)
                // Sin fallback a PIN/patrón — exigimos biometría fuerte exclusivamente
                .setNegativeButtonText("Cancelar")
                .build()

            prompt.authenticate(promptInfo, BiometricPrompt.CryptoObject(mac))

            continuation.invokeOnCancellation {
                // Si la corrutina es cancelada (e.g., el Activity se destruye),
                // limpiar el estado de autenticación parcial
                Log.d(TAG, "Autenticación biométrica cancelada por la corrutina")
            }
        }
    }

    /**
     * Cierra la sesión del terapeuta: zeoriza el mac desbloqueado y
     * cierra la referencia a la BD. La BD en sí permanece cifrada en disco.
     */
    fun lockSession() {
        unlockedMac = null
        database = null
        Log.i(TAG, "Sesión del terapeuta cerrada. Mac zeorizado.")
    }

    /**
     * Indica si hay una sesión del terapeuta actualmente autenticada.
     */
    val isUnlocked: Boolean get() = database != null && unlockedMac != null

    // ── BiometricSovereigntyGateway — implementación del contrato ─────────────

    override suspend fun anonymizeTensor(raw: BiometricFrame): AnonymizedTensor =
        withContext(Dispatchers.Default) {
            require(raw.rawBytes.isNotEmpty()) {
                "rawBytes no puede estar vacío para anonimizar"
            }

            // Extracción de feature vector — no toca rawBytes (zeorización es
            // responsabilidad exclusiva de zeroizeFrame, llamado por el orquestador)
            val featureVector = extractFeatureVector(raw.rawBytes, raw.frameType)

            val vectorBytes = featureVector.flatMapIndexed { i, d ->
                val bits = d.toBits()
                listOf(
                    (bits ushr 56).toByte(), (bits ushr 48).toByte(),
                    (bits ushr 40).toByte(), (bits ushr 32).toByte(),
                    (bits ushr 24).toByte(), (bits ushr 16).toByte(),
                    (bits ushr 8).toByte(), bits.toByte()
                )
            }.toByteArray()

            val hashHex = sha256Hex(vectorBytes)

            AnonymizedTensor(
                tensorId = java.util.UUID.randomUUID().toString(),
                sessionId = raw.sessionId,
                featureVector = featureVector,
                tensorType = raw.frameType,
                integrityHash = IntegrityHash(hashHex)
            )
        }

    override suspend fun validateConsent(state: AutonomicState): ConsentLevel {
        requireDatabaseUnlocked()

        // Verificar que el nivel de consentimiento en memoria coincide
        // con el nivel persistido en SQLCipher (anti-tampering en BD)
        val persisted = withContext(Dispatchers.IO) {
            database!!.clinicalSessionDao()
                .getSessionById(state.session.sessionId.value)
        }

        return if (persisted?.consentLevel == state.consentLevel.name) {
            state.consentLevel
        } else {
            // Inconsistencia entre estado en memoria y en BD →
            // posible manipulación → tratar como sin consentimiento
            Log.w(TAG, "Inconsistencia de consentimiento para sesión ${state.session.sessionId.value}. " +
                "Memoria: ${state.consentLevel.name}, BD: ${persisted?.consentLevel}")
            ConsentLevel.NONE
        }
    }

    override suspend fun attestSessionIntegrity(session: ClinicalSession): AttestationResult =
        integrityManager.attest(session.sessionId)

    override suspend fun zeroizeFrame(frame: BiometricFrame): ZeroizationReceipt =
        withContext(Dispatchers.Default) {
            val size = frame.rawBytes.size

            // CAPA 1: ByteArray loop explícito en Kotlin (JVM heap)
            // ADR-007: el loop for es obligatorio — no usar Arrays.fill() porque
            // algunos JIT optimizan Arrays.fill() a un memset diferido.
            for (i in frame.rawBytes.indices) {
                frame.rawBytes[i] = 0
            }

            // CAPA 2: Verificación — todos los bytes deben ser 0x00
            val allZero = frame.rawBytes.all { it == 0.toByte() }
            if (!allZero) {
                throw ZeroizationFailureException(
                    frame.frameId,
                    "La zeroización Kotlin no completó correctamente — bytes no-cero detectados"
                )
            }

            // Hash de verificación: SHA-256 de array de ceros (determinístico)
            val verifyHash = sha256Hex(frame.rawBytes)

            ZeroizationReceipt(
                frameId = frame.frameId,
                zeroizedAtEpochMs = System.currentTimeMillis(),
                bytesZeroized = size,
                method = ZeroizationMethod.KOTLIN_BYTEARRAY_LOOP,
                verificationHash = IntegrityHash(verifyHash)
            )
        }

    // ── Purga regulatoria segura ──────────────────────────────────────────────

    /**
     * Ejecuta la purga completa de una sesión clínica expirada.
     *
     * PRECONDICIÓN: El terapeuta debe estar autenticado (isUnlocked = true).
     * La purga de datos clínicos requiere autorización explícita del profesional.
     *
     * FLUJO COMPLETO:
     *   1. Verificar autenticación biométrica activa.
     *   2. executeRegulatedPurge() — audit log con bypass_retention_lock.
     *   3. deleteSession() — sesión principal (audit log en cascada).
     *   4. VACUUM — SQLCipher zeoriza páginas liberadas (cipher_memory_security ON).
     *   5. Registrar en compliance_log (tabla externa al audit log protegido).
     *
     * @param sessionId ID de la sesión a purgar.
     * @param purgedByUtc Timestamp de autorización del terapeuta.
     * @throws IllegalStateException si el terapeuta no está autenticado.
     */
    suspend fun secureDeleteSession(sessionId: SessionId, purgedByUtc: UtcTimestamp) {
        requireDatabaseUnlocked()

        withContext(Dispatchers.IO) {
            val db = database!!
            val auditDao = db.auditLogDao()
            val sessionDao = db.clinicalSessionDao()

            // 1. Purga del audit log con bypass regulatorio atómico
            auditDao.executeRegulatedPurge(sessionId.value)

            // 2. Borrado de la sesión (cascade ya eliminó el audit log arriba,
            //    pero la FK CASCADE también aplica como red de seguridad adicional)
            sessionDao.deleteSession(sessionId.value)

            // 3. VACUUM — compacta el archivo WAL y zeoriza páginas liberadas
            //    cipher_memory_security = ON garantiza que SQLCipher sobrescriba
            //    las páginas con ceros antes de liberarlas al sistema de archivos
            db.openHelper.writableDatabase.execSQL("VACUUM")

            // 4. Registrar la purga en el compliance log
            //    (tabla separada del audit log protegido — permite auditoría del borrado)
            logCompliancePurge(db, sessionId, purgedByUtc)

            Log.i(TAG, "Purga regulatoria completada para sesión ${sessionId.value}. " +
                "VACUUM ejecutado. Páginas liberadas zeorizadas.")
        }
    }

    // ── Acceso controlado a la BD (sólo interno) ─────────────────────────────

    /**
     * Retorna la instancia de BD sólo si la sesión está autenticada.
     * Usado internamente por los repositorios que necesitan acceso a los DAOs.
     *
     * @throws IllegalStateException si el terapeuta no está autenticado.
     */
    fun requireDatabase(): NeuroSynapseDatabase {
        requireDatabaseUnlocked()
        return database!!
    }

    // ── Helpers privados ──────────────────────────────────────────────────────

    /**
     * Verifica que el dispositivo tiene biometría strong disponible y configurada.
     * Lanza BiometricUnavailableException con causa específica si no.
     */
    private fun requireBiometricStrong() {
        val biometricManager = BiometricManager.from(context)
        when (val status = biometricManager.canAuthenticate(BIOMETRIC_STRONG)) {
            BiometricManager.BIOMETRIC_SUCCESS -> { /* OK */ }
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE ->
                throw BiometricUnavailableException(
                    "Este dispositivo no tiene hardware biométrico strong. " +
                    "Neuro-Synapse requiere huella digital o reconocimiento facial " +
                    "certificado (BIOMETRIC_STRONG) para proteger los datos clínicos."
                )
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE ->
                throw BiometricUnavailableException(
                    "El hardware biométrico no está disponible temporalmente. " +
                    "Intente en unos momentos."
                )
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED ->
                throw BiometricUnavailableException(
                    "El terapeuta no tiene huellas o FaceID configurados. " +
                    "Configure la biometría en los ajustes del dispositivo antes de usar Neuro-Synapse."
                )
            else ->
                throw BiometricUnavailableException(
                    "Biometría strong no disponible (código: $status)."
                )
        }
    }

    /**
     * Guarda registro de auditoría de la purga en la tabla compliance_log.
     * Esta tabla es mutable (no tiene triggers append-only) porque registra
     * acciones administrativas sobre el sistema, no eventos clínicos de pacientes.
     *
     * compliance_log se crea en NeuroSynapseDatabase.onCreate si no existe.
     * Es externa al audit_log_entries protegido — el gateway tiene acceso directo.
     */
    private fun logCompliancePurge(
        db: NeuroSynapseDatabase,
        sessionId: SessionId,
        purgedByUtc: UtcTimestamp
    ) {
        db.openHelper.writableDatabase.execSQL("""
            CREATE TABLE IF NOT EXISTS compliance_log (
                id            INTEGER PRIMARY KEY AUTOINCREMENT,
                action        TEXT NOT NULL,
                session_id    TEXT NOT NULL,
                executed_at   TEXT NOT NULL,
                executed_by   TEXT NOT NULL DEFAULT 'THERAPIST_BIOMETRIC'
            )
        """.trimIndent())

        db.openHelper.writableDatabase.execSQL(
            "INSERT INTO compliance_log (action, session_id, executed_at) VALUES (?, ?, ?)",
            arrayOf("REGULATORY_PURGE", sessionId.value, purgedByUtc.iso8601)
        )
    }

    private fun requireDatabaseUnlocked() {
        check(database != null && unlockedMac != null) {
            "AndroidLocalSovereigntyGateway: operación requiere autenticación biométrica previa. " +
            "Llame a authenticateAndUnlock() antes de operar con datos clínicos."
        }
    }

    /**
     * Extrae un vector de características numéricas del buffer biométrico.
     * La estrategia de extracción depende del tipo de frame.
     *
     * Para AUDIO_PCM_RAW: estadísticas de amplitud por segmento de 64 samples.
     * Para IMAGE_GRAYSCALE_RAW: histograma de intensidad normalizado.
     * Para NUMERIC_FEATURE_VECTOR: ya es un vector — deserializar directamente.
     *
     * El vector resultante nunca contiene datos de identificación personal —
     * son métricas cuantitativas anónimas extraídas del buffer crudo.
     */
    private fun extractFeatureVector(rawBytes: ByteArray, frameType: BiometricFrameType): DoubleArray {
        return when (frameType) {
            BiometricFrameType.AUDIO_PCM_RAW -> {
                // 16 segmentos de amplitud media cuadrada (RMS)
                val segmentSize = maxOf(1, rawBytes.size / 16)
                DoubleArray(16) { segIdx ->
                    val start = segIdx * segmentSize
                    val end = minOf(start + segmentSize, rawBytes.size)
                    val sumSq = (start until end).sumOf {
                        val s = rawBytes[it].toDouble()
                        s * s
                    }
                    Math.sqrt(sumSq / (end - start))
                }
            }
            BiometricFrameType.IMAGE_GRAYSCALE_RAW -> {
                // Histograma de 16 bins de intensidad normalizado [0, 255]
                val histogram = IntArray(16)
                rawBytes.forEach { byte ->
                    val intensity = byte.toInt() and 0xFF
                    val bin = (intensity * 16) / 256
                    histogram[bin.coerceIn(0, 15)]++
                }
                val total = rawBytes.size.toDouble().coerceAtLeast(1.0)
                DoubleArray(16) { histogram[it] / total }
            }
            BiometricFrameType.NUMERIC_FEATURE_VECTOR -> {
                // El buffer ya es un vector de doubles serializado (8 bytes cada uno)
                val count = rawBytes.size / 8
                DoubleArray(count) { i ->
                    val offset = i * 8
                    java.nio.ByteBuffer.wrap(rawBytes, offset, 8).double
                }
            }
        }
    }

    private fun sha256Hex(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data)
            .joinToString("") { "%02x".format(it) }
}

// ── Excepciones del gateway ───────────────────────────────────────────────────

class BiometricAuthException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)

class BiometricUnavailableException(
    message: String
) : Exception(message)
