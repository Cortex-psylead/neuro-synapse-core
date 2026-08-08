package com.neurosynapse.app.analysis

import android.util.Log

object HeuristicAnalyzer {
    private const val TAG = "HeuristicAnalyzer"

    data class HeuristicFeatures(
        val density: Double,
        val contourCount: Int,
        val aspectRatio: Double,
        val symmetry: Double,
        val bodyPartsPresence: Map<String, Boolean>
    )

    fun analyze(features: HeuristicFeatures): String {
        Log.d(TAG, "Ejecutando análisis heurístico de respaldo...")
        
        val summary = StringBuilder()
        summary.append("ANÁLISIS HEURÍSTICO (Modo Respaldo)\n")
        summary.append("------------------------------------\n")
        
        summary.append("Densidad Gráfica: ${"%.2f".format(features.density * 100)}%\n")
        summary.append("Complejidad (Contornos): ${features.contourCount}\n")
        summary.append("Relación de Aspecto: ${"%.2f".format(features.aspectRatio)}\n")
        summary.append("Índice de Simetría: ${"%.2f".format(features.symmetry)}\n\n")
        
        summary.append("OBSERVACIONES TÉCNICAS:\n")
        if (features.density < 0.02) {
            summary.append("- Trazos tenues o escasos: Sugiere inhibición o cautela.\n")
        } else if (features.density > 0.15) {
            summary.append("- Alta densidad gráfica: Posible expansión o ansiedad.\n")
        }
        
        if (features.aspectRatio > 1.5) {
            summary.append("- Figura elongada verticalmente.\n")
        } else if (features.aspectRatio < 0.5) {
            summary.append("- Figura achatada o expandida horizontalmente.\n")
        }
        
        summary.append("\nESTADO DE MEMORIA: El motor de IA completo no pudo cargarse. Se recomienda cerrar aplicaciones y reintentar para obtener el reporte detallado.")
        
        return summary.toString()
    }
}
