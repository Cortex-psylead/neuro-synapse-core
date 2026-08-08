package com.neurosynapse.app.memory

import android.util.Log

object NativeMemoryPurge {
    private const val TAG = "NativeMemoryPurge"

    fun purgeAll(mats: List<org.opencv.core.Mat>? = null,
                 bitmaps: List<android.graphics.Bitmap>? = null,
                 onComplete: () -> Unit) {
        Log.d(TAG, "Iniciando purga de memoria nativa...")
        mats?.forEach { if (!it.empty()) it.release() }
        bitmaps?.forEach { if (!it.isRecycled) it.recycle() }
        System.gc()
        Runtime.getRuntime().gc()
        System.runFinalization()
        MemoryGuardian.logNativeMemory()
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            onComplete()
        }, 2000)
    }

    fun emergencyPurge() {
        System.gc()
        Runtime.getRuntime().gc()
        System.runFinalization()
        Thread.sleep(500)
    }
}
