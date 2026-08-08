package com.neurosynapse.app.memory

import android.app.ActivityManager
import android.content.Context
import android.util.Log

object MemoryGuardian {
    private const val TAG = "MemoryGuardian"
    private const val MIN_FREE_RAM_MB = 2500
    private const val MIN_FREE_RAM_HEURISTIC_MB = 800

    data class MemoryStatus(
        val totalRamMb: Long,
        val availableRamMb: Long,
        val isLlmViable: Boolean,
        val isHeuristicViable: Boolean,
        val recommendation: String
    )

    fun checkMemory(context: Context): MemoryStatus {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)
        val availableMb = memInfo.availMem / (1024 * 1024)
        val totalMb = memInfo.totalMem / (1024 * 1024)
        val isLlmViable = availableMb >= MIN_FREE_RAM_MB && !memInfo.lowMemory
        val isHeuristicViable = availableMb >= MIN_FREE_RAM_HEURISTIC_MB
        val recommendation = when {
            isLlmViable -> "LLM_LOCAL"
            isHeuristicViable -> "HEURISTIC_FALLBACK"
            else -> "CRITICAL_MEMORY"
        }
        Log.d(TAG, "RAM Total: ${totalMb}MB | Libre: ${availableMb}MB | Rec: $recommendation")
        return MemoryStatus(totalMb, availableMb, isLlmViable, isHeuristicViable, recommendation)
    }

    fun logNativeMemory() {
        val alloc = android.os.Debug.getNativeHeapAllocatedSize() / (1024 * 1024)
        val size = android.os.Debug.getNativeHeapSize() / (1024 * 1024)
        Log.d(TAG, "Native Heap: ${alloc}MB / ${size}MB")
    }
}
