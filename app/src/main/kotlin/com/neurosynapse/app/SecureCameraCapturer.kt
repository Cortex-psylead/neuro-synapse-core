package com.neurosynapse.app

import android.content.Context
import android.util.Log
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.delay
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class SecureCameraCapturer(private val context: Context) {

    private var imageCapture: ImageCapture? = null
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    fun startPreview(lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner, cameraSelector, preview, imageCapture
                )
            } catch (e: Exception) {
                Log.e("SecureCamera", "Fallo al iniciar cámara", e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    suspend fun captureBurst(sessionId: String, count: Int = 3): List<File> {
        val files = mutableListOf<File>()
        repeat(count) { i ->
            val file = captureSingle(sessionId, "burst_$i.jpg")
            files.add(file)
            delay(150) // Pequeña demora entre ráfagas
        }
        return files
    }

    private suspend fun captureSingle(sessionId: String, fileName: String): File = suspendCoroutine { continuation ->
        val sessionDir = File(context.filesDir, "sessions/$sessionId").apply { mkdirs() }
        val photoFile = File(sessionDir, fileName)

        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        imageCapture?.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    continuation.resume(photoFile)
                }
                override fun onError(exc: ImageCaptureException) {
                    continuation.resumeWith(Result.failure(exc))
                }
            }
        )
    }

    suspend fun captureProjectiveTest(sessionId: String): File {
        return captureSingle(sessionId, "test_htp_raw.jpg")
    }

    fun shutdown() {
        cameraExecutor.shutdown()
    }
}