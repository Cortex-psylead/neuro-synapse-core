package com.neurosynapse.domain.session

import com.neurosynapse.domain.common.*
import kotlinx.serialization.Serializable

@Serializable
data class AuditLogEntry(
    val entryId: String,
    val sessionId: SessionId,
    val timestampUtc: UtcTimestamp,
    val action: String,
    val principalId: String,
    val integrityHash: IntegrityHash,
    val previousHash: IntegrityHash
) {
    companion object {
        val GENESIS_HASH = IntegrityHash("0".repeat(64))
    }
}

@Serializable
data class SubsystemHealth(
    val isOperative: Boolean,
    val availableRamMb: Int,
    val batteryLevel: Int,
    val temperatureCelsius: Float,
    val canonicalCode: String
)

sealed class ClinicalSessionState {
    object Idle : ClinicalSessionState()
    data class PhaseTransition(val targetPhase: PipelinePhase) : ClinicalSessionState()
    data class Warning(val warning: SessionWarning) : ClinicalSessionState()
    data class Error(val message: String, val code: WarningCode) : ClinicalSessionState()
}

@Serializable
data class SessionWarning(
    val code: WarningCode,
    val message: String,
    val timestamp: UtcTimestamp
)

enum class WarningCode {
    LOW_CONFIDENCE_SCORE,
    RESOURCE_THROTTLING,
    BIOMETRIC_DISCONTINUITY,
    INTEGRITY_CHECK_FAILED
}
