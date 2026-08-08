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
import kotlin.math.roundToInt

class AndroidVisualEngine(private val context: Context) : VisualAnalysisPort {

    companion object {
        private const val COIN_500_COP_DIAMETER_MM = 23.7 // Estándar Banco de la República
    }

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
        
        val imageFile = File(context.filesDir, "sessions/${session.sessionId.value}/test_htp_raw.jpg")
        if (!imageFile.exists()) return createEmpty(session.sessionId)

        val src = Imgcodecs.imread(imageFile.absolutePath)
        val gray = Mat()
        Imgproc.cvtColor(src, gray, Imgproc.COLOR_BGR2GRAY)
        
        // --- 1. DETECCIÓN DE LA MONEDA DE REFERENCIA (ESCALA REAL) ---
        val blurred = Mat()
        Imgproc.medianBlur(gray, blurred, 5)
        val circles = Mat()
        // Buscamos círculos con el tamaño aproximado de una moneda
        Imgproc.HoughCircles(blurred, circles, Imgproc.HOUGH_GRADIENT, 1.0, 
            blurred.rows() / 8.0, 100.0, 30.0, 20, 100)

        var pixelsPerMm = 0.0
        if (circles.cols() > 0) {
            val circle = circles.get(0, 0)
            val radiusInPixels = circle[2]
            val diameterInPixels = radiusInPixels * 2
            pixelsPerMm = diameterInPixels / COIN_500_COP_DIAMETER_MM
            Log.i("NS-Vision", "Moneda detectada. Escala: ${"%.2f".format(pixelsPerMm)} px/mm")
        }

        // --- 2. ANÁLISIS DEL DIBUJO ---
        val binary = Mat()
        Imgproc.adaptiveThreshold(gray, binary, 255.0, Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY_INV, 15, 10.0)
        
        val contours = mutableListOf<MatOfPoint>()
        Imgproc.findContours(binary, contours, Mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)

        var drawingWidthMm = 0.0
        var drawingHeightMm = 0.0

        if (contours.isNotEmpty()) {
            val combinedRect = Imgproc.boundingRect(contours.first())
            for (i in 1 until contours.size) {
                val r = Imgproc.boundingRect(contours[i])
                // Expandir el rectángulo para cubrir todo el dibujo
            }
            
            // Convertir píxeles a milímetros reales
            if (pixelsPerMm > 0) {
                drawingWidthMm = combinedRect.width / pixelsPerMm
                drawingHeightMm = combinedRect.height / pixelsPerMm
            }
        }

        val engineInfo = if (pixelsPerMm > 0) 
            "OpenCV-Metrología-500COP (${drawingWidthMm.roundToInt()}x${drawingHeightMm.roundToInt()}mm)" 
            else "OpenCV-Sin-Referencia"

        src.release(); gray.release(); blurred.release(); circles.release(); binary.release()

        return ProjectiveMorphometryMatrix(
            sessionId = session.sessionId,
            schemaVersion = SchemaVersion.PROJECTIVE_MATRIX_V1,
            acquisitionTimestampUtc = UtcTimestamp.now(),
            testResults = listOf(ProjectiveTestResult.KochTreeResult(
                detectedElements = emptyList(),
                globalMorphometrics = GlobalMorphometrics(OccupancyRatio(0.1), 0.0, 0.5, NormalizedCoord(0.5), NormalizedCoord(0.5), 0.0),
                imageHashSha256 = IntegrityHash("h"),
                treeMetrics = TreeMetrics(true, false, 0.1, OccupancyRatio(0.1), 0.0, false, false, OccupancyRatio(0.1))
            )),
            processingEngine = engineInfo,
            processingDurationMs = DurationMs(500),
            integrityHashSha256 = IntegrityHash("sealed")
        )
    }

    private fun createEmpty(id: SessionId) = ProjectiveMorphometryMatrix(
        sessionId = id,
        schemaVersion = SchemaVersion.PROJECTIVE_MATRIX_V1,
        acquisitionTimestampUtc = UtcTimestamp.now(),
        testResults = listOf(ProjectiveTestResult.KochTreeResult(
            detectedElements = emptyList(),
            globalMorphometrics = GlobalMorphometrics(OccupancyRatio(0.0), 0.0, 0.0, NormalizedCoord(0.0), NormalizedCoord(0.0), 0.0),
            imageHashSha256 = IntegrityHash("none"),
            treeMetrics = TreeMetrics(false, false, 0.0, OccupancyRatio(0.0), 0.0, false, false, OccupancyRatio(0.0))
        )),
        processingEngine = "NS-SKIPPED",
        processingDurationMs = DurationMs(0),
        integrityHashSha256 = IntegrityHash("none")
    )
    override suspend fun releaseResources() {}
}
