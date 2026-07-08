package com.neurosynapse.domain.projective

import com.neurosynapse.domain.common.*

@JvmInline
value class NormalizedCoord(val value: Double) {
    init {
        require(value in 0.0..1.0) {
            "NormalizedCoord debe estar en [0.0, 1.0]. Recibido: $value"
        }
    }
}

@JvmInline
value class DetectionConfidence(val value: Float) {
    init {
        require(value in 0.0f..1.0f) {
            "DetectionConfidence debe estar en [0.0, 1.0]. Recibido: $value"
        }
    }
    val isReliable: Boolean get() = value >= 0.5f
    val isHighConfidence: Boolean get() = value >= 0.85f
}

@JvmInline
value class OccupancyRatio(val value: Double) {
    init {
        require(value in 0.0..1.0) {
            "OccupancyRatio debe estar en [0.0, 1.0]. Recibido: $value"
        }
    }
}

data class NormalizedBoundingBox(
    val centerX: NormalizedCoord,
    val centerY: NormalizedCoord,
    val width: Double,
    val height: Double
) {
    init {
        require(width > 0.0 && width <= 1.0) { "BBox width inválida: $width" }
        require(height > 0.0 && height <= 1.0) { "BBox height inválida: $height" }
    }
    val relativeArea: Double get() = width * height
    val verticalZone: VerticalZone get() = when {
        centerY.value < 0.33 -> VerticalZone.UPPER
        centerY.value < 0.66 -> VerticalZone.MIDDLE
        else -> VerticalZone.LOWER
    }
    val horizontalZone: HorizontalZone get() = when {
        centerX.value < 0.33 -> HorizontalZone.LEFT
        centerX.value < 0.66 -> HorizontalZone.CENTER
        else -> HorizontalZone.RIGHT
    }
}

enum class VerticalZone { UPPER, MIDDLE, LOWER }
enum class HorizontalZone { LEFT, CENTER, RIGHT }

data class DetectedProjectiveElement(
    val elementClass: String,
    val boundingBox: NormalizedBoundingBox,
    val confidence: DetectionConfidence,
    val clinicalRelevanceTag: String?
)

data class GlobalMorphometrics(
    val traceOccupancyRatio: OccupancyRatio,
    val strokeDensityScore: Double,
    val symmetryIndex: Double,
    val centerOfMassX: NormalizedCoord,
    val centerOfMassY: NormalizedCoord,
    val contourComplexityScore: Double
) {
    init {
        require(strokeDensityScore >= 0.0) { "strokeDensityScore no puede ser negativo" }
        require(symmetryIndex in 0.0..1.0) { "symmetryIndex debe estar en [0.0, 1.0]" }
        require(contourComplexityScore >= 0.0) { "contourComplexityScore no puede ser negativo" }
    }
}

data class HouseMetrics(
    val hasChimney: Boolean,
    val hasDoor: Boolean,
    val hasWindows: Boolean,
    val windowCount: Int,
    val roofOccupancyRatio: OccupancyRatio,
    val doorCentrality: Double,
    val chimneySmoke: Boolean,
    val hasPath: Boolean,
    val pathConnectsToDoor: Boolean
)

data class TreeMetrics(
    val hasCrown: Boolean,
    val hasRoots: Boolean,
    val trunkWidthRatio: Double,
    val crownOccupancyRatio: OccupancyRatio,
    val branchComplexity: Double,
    val hasHoles: Boolean,
    val hasFruits: Boolean,
    val treeHeightRatio: OccupancyRatio
)

data class HumanFigureMetrics(
    val hasHead: Boolean,
    val hasFacialFeatures: Boolean,
    val hasArms: Boolean,
    val hasHands: Boolean,
    val hasLegs: Boolean,
    val hasFeet: Boolean,
    val figureHeightRatio: OccupancyRatio,
    val armPosition: ArmPosition,
    val facialExpression: FacialExpression,
    val figureOrientation: FigureOrientation,
    val erasureCount: Int
)

enum class ArmPosition { OPEN, CLOSE_TO_BODY, BEHIND_BODY, RAISED, UNDETECTABLE }
enum class FacialExpression { SMILING, NEUTRAL, FROWNING, ABSENT, UNDETECTABLE }
enum class FigureOrientation { FRONTAL, PROFILE_LEFT, PROFILE_RIGHT, BACK, UNDETECTABLE }

data class PersonInRainMetrics(
    val hasUmbrella: Boolean,
    val umbrellaCoversFullFigure: Boolean,
    val rainIntensityScore: Double,
    val figurePosture: FigurePosture,
    val hasPuddles: Boolean,
    val figureMetrics: HumanFigureMetrics
)

enum class FigurePosture { UPRIGHT, BENT, RUNNING, HUDDLED, UNDETECTABLE }

sealed class ProjectiveTestResult {
    abstract val testType: ProjectiveTestType
    abstract val detectedElements: List<DetectedProjectiveElement>
    abstract val globalMorphometrics: GlobalMorphometrics
    abstract val imageHashSha256: IntegrityHash

    data class HtpHouseResult(
        override val detectedElements: List<DetectedProjectiveElement>,
        override val globalMorphometrics: GlobalMorphometrics,
        override val imageHashSha256: IntegrityHash,
        val houseMetrics: HouseMetrics
    ) : ProjectiveTestResult() {
        override val testType = ProjectiveTestType.HTP_HOUSE
    }

    data class HtpTreeResult(
        override val detectedElements: List<DetectedProjectiveElement>,
        override val globalMorphometrics: GlobalMorphometrics,
        override val imageHashSha256: IntegrityHash,
        val treeMetrics: TreeMetrics
    ) : ProjectiveTestResult() {
        override val testType = ProjectiveTestType.HTP_TREE
    }

    data class HtpPersonResult(
        override val detectedElements: List<DetectedProjectiveElement>,
        override val globalMorphometrics: GlobalMorphometrics,
        override val imageHashSha256: IntegrityHash,
        val humanFigureMetrics: HumanFigureMetrics
    ) : ProjectiveTestResult() {
        override val testType = ProjectiveTestType.HTP_PERSON
    }

    data class MachoverResult(
        override val detectedElements: List<DetectedProjectiveElement>,
        override val globalMorphometrics: GlobalMorphometrics,
        override val imageHashSha256: IntegrityHash,
        val humanFigureMetrics: HumanFigureMetrics
    ) : ProjectiveTestResult() {
        override val testType = ProjectiveTestType.MACHOVER_HUMAN_FIGURE
    }

    data class KochTreeResult(
        override val detectedElements: List<DetectedProjectiveElement>,
        override val globalMorphometrics: GlobalMorphometrics,
        override val imageHashSha256: IntegrityHash,
        val treeMetrics: TreeMetrics
    ) : ProjectiveTestResult() {
        override val testType = ProjectiveTestType.KOCH_TREE
    }

    data class PersonInRainResult(
        override val detectedElements: List<DetectedProjectiveElement>,
        override val globalMorphometrics: GlobalMorphometrics,
        override val imageHashSha256: IntegrityHash,
        val personInRainMetrics: PersonInRainMetrics
    ) : ProjectiveTestResult() {
        override val testType = ProjectiveTestType.PERSON_IN_THE_RAIN
    }
}

data class ProjectiveMorphometryMatrix(
    val sessionId: SessionId,
    val schemaVersion: SchemaVersion = SchemaVersion.PROJECTIVE_MATRIX_V1,
    val acquisitionTimestampUtc: UtcTimestamp,
    val testResults: List<ProjectiveTestResult>,
    val processingEngine: String,
    val processingDurationMs: DurationMs,
    val integrityHashSha256: IntegrityHash
) {
    init {
        require(testResults.isNotEmpty()) {
            "ProjectiveMorphometryMatrix debe contener al menos un test"
        }
        val testTypes = testResults.map { it.testType }
        require(testTypes.size == testTypes.toSet().size) {
            "No puede haber tests proyectivos duplicados en la misma matriz"
        }
    }
}
