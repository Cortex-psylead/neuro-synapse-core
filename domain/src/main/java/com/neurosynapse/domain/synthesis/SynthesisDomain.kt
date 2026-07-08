package com.neurosynapse.domain.synthesis

import com.neurosynapse.domain.common.*

data class SoapSubjective(
    val patientNarrativeSummary: String,
    val reportedSymptoms: List<String>,
    val emotionalTone: String,
    val keyThemes: List<String>
)

data class SoapObjective(
    val acousticSummary: AcousticSummaryForReport,
    val projectiveSummary: ProjectiveSummaryForReport,
    val acousticMatrixHash: IntegrityHash,
    val projectiveMatrixHash: IntegrityHash
)

data class AcousticSummaryForReport(
    val clinicalFlag: ClinicalFlag,
    val compositeStressIndex: NormalizedIndex,
    val keyFindings: List<String>
)

data class ProjectiveSummaryForReport(
    val testsAdministered: List<String>,
    val keyFindings: List<String>,
    val lowConfidenceWarnings: List<String>
)

data class SoapAssessment(
    val clinicalHypotheses: List<ClinicalHypothesis>,
    val differentialConsiderations: List<String>,
    val ciE11Codes: List<String>,
    val dsm5Codes: List<String>,
    val professionalReviewMandatory: String =
        "BORRADOR GENERADO POR IA. Requiere revisión, validación y firma del psicólogo " +
        "tratante antes de incorporarse a la historia clínica oficial. " +
        "Los códigos diagnósticos son sugerencias de trabajo, no diagnósticos confirmados."
)

data class ClinicalHypothesis(
    val description: String,
    val supportingEvidence: List<String>,
    val confidenceLevel: HypothesisConfidence,
    val requiresAdditionalAssessment: Boolean
)

enum class HypothesisConfidence { LOW, MODERATE, HIGH }

data class SoapPlan(
    val suggestedInterventions: List<String>,
    val recommendedFollowUpTests: List<String>,
    val referralSuggestions: List<String>,
    val sessionFrequencyRecommendation: String?
)

data class SlmGenerationMetadata(
    val modelId: String,
    val modelVersion: String,
    val promptTemplateVersion: String,
    val inferenceTimeMs: DurationMs,
    val tokensInput: Int,
    val tokensGenerated: Int,
    val temperature: Double
)

data class ClinicalDraftReport(
    val sessionId: SessionId,
    val schemaVersion: SchemaVersion = SchemaVersion.CLINICAL_DRAFT_V1,
    val generatedAtUtc: UtcTimestamp,
    val status: ReportStatus = ReportStatus.DRAFT_PENDING_REVIEW,
    val subjective: SoapSubjective,
    val objective: SoapObjective,
    val assessment: SoapAssessment,
    val plan: SoapPlan,
    val slmMetadata: SlmGenerationMetadata,
    val reportHashSha256: IntegrityHash
) {
    init {
        require(status == ReportStatus.DRAFT_PENDING_REVIEW) {
            "ClinicalDraftReport sólo puede crearse en estado DRAFT_PENDING_REVIEW."
        }
    }
}

enum class ReportStatus {
    DRAFT_PENDING_REVIEW,
    REVIEWED_BY_PROFESSIONAL,
    AMENDED,
    ARCHIVED
}
