package com.neurosynapse.domain.session

import com.neurosynapse.domain.synthesis.ClinicalDraftReport
import com.neurosynapse.domain.synthesis.SoapAssessment

/**
 * Barrera de Seguridad Clínica (ADR-008).
 * Detecta y bloquea lenguaje diagnóstico prohibido por regulación INVIMA/FDA.
 * Ajustado a la estructura jerárquica de ClinicalDraftReport.
 */
class ClinicalSafetyGuard {

    private val forbiddenPatterns = listOf(
        Regex("(?i)diagnóstico de"),
        Regex("(?i)padece de"),
        Regex("(?i)sufre de"),
        Regex("(?i)confirmado"),
        Regex("(?i)cumple criterios para"),
        Regex("[Ff][0-9]{2}(\\.[0-9])?"),
        Regex("[0-9]{3}\\.[0-9]{2}")
    )

    /**
     * Analiza el reporte y sanitiza específicamente la sección de Assessment (Evaluación),
     * que es donde la IA suele emitir juicios clínicos prohibidos.
     */
    fun sanitizeReport(report: ClinicalDraftReport): ClinicalDraftReport {
        val subjective = report.subjective
        val assessment = report.assessment
        
        val sanitizedSubjective = subjective.copy(
            patientNarrativeSummary = filterText(subjective.patientNarrativeSummary),
            reportedSymptoms = subjective.reportedSymptoms.map { filterText(it) },
            keyThemes = subjective.keyThemes.map { filterText(it) }
        )

        val sanitizedHypotheses = assessment.clinicalHypotheses.map { hypothesis ->
            hypothesis.copy(
                description = filterText(hypothesis.description),
                supportingEvidence = hypothesis.supportingEvidence.map { filterText(it) }
            )
        }

        val sanitizedDifferential = assessment.differentialConsiderations.map { filterText(it) }

        // Retornar copia del reporte con las secciones Subjective y Assessment sanitizadas
        return report.copy(
            subjective = sanitizedSubjective,
            assessment = assessment.copy(
                clinicalHypotheses = sanitizedHypotheses,
                differentialConsiderations = sanitizedDifferential
            )
        )
    }

    private fun filterText(input: String): String {
        var filtered = input
        forbiddenPatterns.forEach { pattern ->
            filtered = filtered.replace(pattern, "[BLOQUEADO POR PROTOCOLO DE SEGURIDAD]")
        }
        return filtered
    }

    /**
     * Verifica si el reporte contiene violaciones antes de mostrarlo.
     */
    fun isReportSafe(report: ClinicalDraftReport): Boolean {
        val textsToCheck = mutableListOf<String>()
        
        textsToCheck.add(report.subjective.patientNarrativeSummary)
        textsToCheck.addAll(report.subjective.reportedSymptoms)
        textsToCheck.addAll(report.subjective.keyThemes)

        report.assessment.clinicalHypotheses.forEach { 
            textsToCheck.add(it.description)
            textsToCheck.addAll(it.supportingEvidence)
        }
        textsToCheck.addAll(report.assessment.differentialConsiderations)
        
        return textsToCheck.none { text ->
            forbiddenPatterns.any { it.containsMatchIn(text) }
        }
    }
}
