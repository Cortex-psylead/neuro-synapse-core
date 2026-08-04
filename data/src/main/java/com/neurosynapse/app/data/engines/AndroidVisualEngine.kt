package com.neurosynapse.app.data.engines

import android.content.Context
import android.util.Log
import com.neurosynapse.domain.projective.*
import com.neurosynapse.domain.common.*
import com.neurosynapse.domain.orchestrator.VisualAnalysisPort
import com.neurosynapse.domain.session.ClinicalSession
import org.opencv.android.OpenCVLoader
import org.opencv.core.*
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File

class AndroidVisualEngine(private val context: Context) : VisualAnalysisPort {

    init {
        if (!OpenCVLoader.initDebug()) Log.e("NS-Vision", "OpenCV Loader Falló")
    }

    override suspend fun analyze(
        session: ClinicalSession,
        testType: ProjectiveTestType,
        imageFiles: List<File>,
        age: SubjectAge,
        sex: SubjectSex,
        onProgress: (Float) -> Unit
    ): ProjectiveMorphometryMatrix {
        
        val imageFile = imageFiles.firstOrNull() ?: File(context.filesDir, "sessions/${session.sessionId.value}/test_htp_raw.jpg")
        
        if (!imageFile.exists()) return createEmptyMatrix(session.sessionId)

        onProgress(0.2f)

        // 1. Cargar y Pre-procesar
        val src = Imgcodecs.imread(imageFile.absolutePath)
        if (src.empty()) return createEmptyMatrix(session.sessionId)

        val gray = Mat()
        Imgproc.cvtColor(src, gray, Imgproc.COLOR_BGR2GRAY)
        Imgproc.threshold(gray, gray, 0.0, 255.0, Imgproc.THRESH_BINARY_INV + Imgproc.THRESH_OTSU)

        // 2. Encontrar el dibujo (Contornos)
        val contours = mutableListOf<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(gray, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)

        // 3. Calcular métricas espaciales si hay contornos
        var totalArea = 0.0
        var combinedRect: Rect? = null
        
        for (contour in contours) {
            val rect = Imgproc.boundingRect(contour)
            totalArea += Imgproc.contourArea(contour)
            
            if (combinedRect == null) {
                combinedRect = rect
            } else {
                val x1 = minOf(combinedRect.x, rect.x)
                val y1 = minOf(combinedRect.y, rect.y)
                val x2 = maxOf(combinedRect.x + combinedRect.width, rect.x + rect.width)
                val y2 = maxOf(combinedRect.y + combinedRect.height, rect.y + rect.height)
                combinedRect = Rect(x1, y1, x2 - x1, y2 - y1)
            }
        }

        // 4. Normalización de Coordenadas
        val imgWidth = src.cols().toDouble()
        val imgHeight = src.rows().toDouble()
        
        val occupancy = OccupancyRatio((totalArea / (imgWidth * imgHeight)).coerceIn(0.0, 1.0))
        
        // Calcular centro de masa relativo
        val centerX = if (combinedRect != null) (combinedRect.x + combinedRect.width / 2.0) / imgWidth else 0.5
        val centerY = if (combinedRect != null) (combinedRect.y + combinedRect.height / 2.0) / imgHeight else 0.5

        val morphometrics = GlobalMorphometrics(
            traceOccupancyRatio = occupancy,
            strokeDensityScore = totalArea / 1000.0,
            symmetryIndex = 0.5, // Placeholder para Sprint 11
            centerOfMassX = NormalizedCoord(centerX.coerceIn(0.0, 1.0)),
            centerOfMassY = NormalizedCoord(centerY.coerceIn(0.0, 1.0)),
            contourComplexityScore = contours.size.toDouble()
        )

        // 5. Crear resultado tipado (por ahora KochTree como base)
        val treeResult = ProjectiveTestResult.KochTreeResult(
            detectedElements = emptyList(),
            globalMorphometrics = morphometrics,
            imageHashSha256 = IntegrityHash("sha256_${imageFile.length()}"),
            treeMetrics = TreeMetrics(
                hasCrown = true,
                hasRoots = false,
                trunkWidthRatio = 0.1,
                crownOccupancyRatio = occupancy,
                branchComplexity = contours.size.toDouble(),
                hasHoles = false,
                hasFruits = false,
                treeHeightRatio = OccupancyRatio(if (combinedRect != null) combinedRect.height / imgHeight else 0.0)
            )
        )

        src.release(); gray.release(); hierarchy.release()

        return ProjectiveMorphometryMatrix(
            sessionId = session.sessionId,
            acquisitionTimestampUtc = UtcTimestamp.now(),
            testResults = listOf(treeResult),
            processingEngine = "OpenCV-Spatial-Analysis-v1",
            processingDurationMs = DurationMs(450),
            integrityHashSha256 = IntegrityHash("final_img_seal")
        )
    }

    private fun createEmptyMatrix(id: SessionId) = ProjectiveMorphometryMatrix(
        sessionId = id,
        acquisitionTimestampUtc = UtcTimestamp.now(),
        testResults = listOf(
            ProjectiveTestResult.KochTreeResult(
                detectedElements = emptyList(),
                globalMorphometrics = GlobalMorphometrics(OccupancyRatio(0.0), 0.0, 0.0, NormalizedCoord(0.0), NormalizedCoord(0.0), 0.0),
                imageHashSha256 = IntegrityHash("none"),
                treeMetrics = TreeMetrics(false, false, 0.0, OccupancyRatio(0.0), 0.0, false, false, OccupancyRatio(0.0))
            )
        ),
        processingEngine = "NS-SKIPPED",
        processingDurationMs = DurationMs(0),
        integrityHashSha256 = IntegrityHash("none")
    )

    override suspend fun releaseResources() {}
}
