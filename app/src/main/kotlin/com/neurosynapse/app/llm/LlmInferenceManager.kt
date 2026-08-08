package com.neurosynapse.app.llm

import android.content.Context
import android.util.Log
import org.codeshipping.llamakotlin.LlamaModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.reduce
import kotlinx.coroutines.withContext
import java.io.File

class LlmInferenceManager private constructor(context: Context) {
    private val appContext = context.applicationContext
    private var llamaModel: LlamaModel? = null

    companion object {
        private const val TAG = "LLM"
        @Volatile private var instance: LlmInferenceManager? = null
        
        fun getInstance(context: Context): LlmInferenceManager {
            return instance ?: synchronized(this) {
                instance ?: LlmInferenceManager(context).also { instance = it }
            }
        }
        
        fun destroyInstance() {
            instance?.close()
            instance = null
        }
    }

    suspend fun initialize(modelPath: String) {
        if (llamaModel != null) return
        
        try {
            Log.d(TAG, "Cargando modelo desde: $modelPath")
            Log.d(TAG, "Archivo existe: ${File(modelPath).exists()}")
            Log.d(TAG, "TamaÃ±o: ${File(modelPath).length()} bytes")

            // Usamos LlamaModel de org.codeshipping (Maven Central)
            llamaModel = LlamaModel.load(modelPath) {
                contextSize = 2048
                threads = Runtime.getRuntime().availableProcessors() / 2
                temperature = 0.3f
            }
            Log.d(TAG, "llama.cpp (GGUF) inicializado exitosamente via CodeShipping.")
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "FALLO CRÃTICO: RAM insuficiente para el modelo GGUF.")
            throw Exception("Memoria insuficiente para cargar modelo")
        } catch (e: Exception) {
            Log.e(TAG, "Error inicializando modelo: ${e.message}", e)
            throw Exception("Error inicializando modelo: ${e.message}")
        }
    }

    suspend fun analyzeDrawing(featuresJson: String): Result<String> = 
        withContext(Dispatchers.IO) {
            try {
                if (llamaModel == null) {
                    val defaultPath = appContext.filesDir.absolutePath + "/llama-3.2-1b.gguf"
                    initialize(defaultPath)
                }
                
                val fullPrompt = """
                    <|system|>
                    Eres un asistente de psicologia clinica objetivo. Analiza este dibujo de figura humana (test proyectivo DAP). NO diagnosticar formalmente.
                    <|user|>
                    Datos extraidos: $featuresJson.
                    Responde UNICAMENTE en formato JSON con: descripcion_general, cabeza, cuerpo, extremidades, rostro, omisiones_detectadas, calidad_linea, observaciones_tecnicas.
                    <|assistant|>
                """.trimIndent()
                
                // Generamos la respuesta recolectando el Flow
                // Nos aseguramos de concatenar correctamente los fragmentos de texto (tokens)
                val response = llamaModel?.generateStream(fullPrompt)?.reduce { acc, value -> acc + value }
                
                Log.d(TAG, "Respuesta LLM (Raw): $response")
                // Liberar el modelo de RAM inmediatamente despuÃ©s de generar respuesta para liberar recursos
                close()
                Result.success(response ?: "Sin respuesta del nÃºcleo local")
            } catch (e: OutOfMemoryError) {
                Log.e(TAG, "OOM durante la inferencia local")
                Result.failure(Exception("Memoria agotada durante anÃ¡lisis local"))
            } catch (e: Exception) {
                Log.e(TAG, "Fallo en generaciÃ³n SLM: ${e.message}")
                Result.failure(e)
            }
        }

    fun close() {
        try { 
            llamaModel?.close() 
            Log.d(TAG, "Modelo GGUF liberado de la RAM.")
        } catch (e: Exception) {
            Log.e(TAG, "Error liberando recursos nativos", e)
        }
        llamaModel = null
    }
}
