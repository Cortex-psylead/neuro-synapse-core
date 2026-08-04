package com.neurosynapse.app

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import kotlin.concurrent.thread

class SecureAudioRecorder(private val context: Context) {

    private var isRecording = false
    private var audioRecord: AudioRecord? = null

    @SuppressLint("MissingPermission")
    fun startRecording(sessionId: String, fileName: String): File {
        val sessionDir = File(context.filesDir, "sessions/$sessionId").apply { mkdirs() }
        val audioFile = File(sessionDir, fileName) // Usamos el nombre que nos pasen

        val sampleRate = 16000
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            channelConfig,
            audioFormat,
            bufferSize
        )

        isRecording = true
        audioRecord?.startRecording()

        thread {
            val data = ByteArray(bufferSize)
            FileOutputStream(audioFile).use { fos ->
                while (isRecording) {
                    val read = audioRecord?.read(data, 0, bufferSize) ?: 0
                    if (read > 0) fos.write(data, 0, read)
                }
            }
            Log.i("SecureAudioRecorder", "Guardado: ${audioFile.absolutePath}")
        }

        return audioFile
    }

    fun stopRecording() {
        isRecording = false
        try {
            audioRecord?.apply {
                stop()
                release()
            }
        } catch (e: Exception) {
            Log.e("SecureAudioRecorder", "Error al detener", e)
        }
        audioRecord = null
    }
}
