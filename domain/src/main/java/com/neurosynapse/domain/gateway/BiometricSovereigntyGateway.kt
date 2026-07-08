package com.neurosynapse.domain.gateway

import com.neurosynapse.domain.common.ConsentLevel
import com.neurosynapse.domain.common.IntegrityHash
import com.neurosynapse.domain.common.SessionId
import com.neurosynapse.domain.session.ClinicalSession

data class BiometricFrame(
    val frameId: String,
    val rawBytes: ByteArray,
    val frameType: BiometricFrameType,
    val capturedAtEpochMs: Long,
    val sessionId: SessionId
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BiometricFrame) return false
        return frameId == other.frameId && sessionId == other.sessionId
    }
    override fun hashCode(): Int = frameId.hashCode() * 31 + sessionId.hashCode()
}

enum class BiometricFrameType {
    AUDIO_PCM_RAW,
    IMAGE_GRAYSCALE_RAW,
    NUMERIC_FEATURE_VECTOR
}

data class AnonymizedTensor(
    val tensorId: String,
    val sessionId: SessionId,
    val featureVector: DoubleArray,
    val tensorType: BiometricFrameType,
    val integrityHash: IntegrityHash
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AnonymizedTensor) return false
        return tensorId == other.tensorId
    }
    override fun hashCode(): Int = tensorId.hashCode()
}

data class AutonomicState(
    val session: ClinicalSession,
    val consentLevel: ConsentLevel,
    val deviceIntegrityVerified: Boolean,
    val biometricAuthVerified: Boolean
)

data class ZeroizationReceipt(
    val frameId: String,
    val zeroizedAtEpochMs: Long,
    val bytesZeroized: Int,
    val method: ZeroizationMethod,
    val verificationHash: IntegrityHash
)

enum class ZeroizationMethod {
    CPP_MEMSET,
    KOTLIN_BYTEARRAY_LOOP,
    COMBINED
}

data class AttestationResult(
    val sessionId: SessionId,
    val attestedAtEpochMs: Long,
    val isValid: Boolean,
    val attestationLevel: AttestationLevel,
    val failureReasons: List<String> = emptyList()
)

enum class AttestationLevel {
    HARDWARE_BACKED,
    SOFTWARE_BACKED,
    UNVERIFIED
}

interface BiometricSovereigntyGateway {
    suspend fun anonymizeTensor(raw: BiometricFrame): AnonymizedTensor
    suspend fun validateConsent(state: AutonomicState): ConsentLevel
    suspend fun attestSessionIntegrity(session: ClinicalSession): AttestationResult
    suspend fun zeroizeFrame(frame: BiometricFrame): ZeroizationReceipt
}

class ConsentViolationException(
    val sessionId: SessionId,
    val requiredLevel: ConsentLevel,
    val actualLevel: ConsentLevel,
    message: String
) : Exception(message)

class SessionIntegrityException(
    val sessionId: SessionId,
    val attestationResult: AttestationResult,
    message: String
) : Exception(message)

class ZeroizationFailureException(
    val frameId: String,
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)
