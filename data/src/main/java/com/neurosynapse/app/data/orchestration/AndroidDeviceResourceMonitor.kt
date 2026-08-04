package com.neurosynapse.app.data.orchestration

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import com.neurosynapse.domain.orchestrator.*
import java.util.Timer
import kotlin.concurrent.fixedRateTimer

class AndroidDeviceResourceMonitor(private val context: Context) : DeviceResourceMonitor {
    
    private var monitorTimer: Timer? = null

    override suspend fun getCurrentSnapshot(): DeviceResourceSnapshot {
        val memoryInfo = getMemoryInfo()
        val batteryLevel = getBatteryLevel()

        return DeviceResourceSnapshot(
            availableRamMb = (memoryInfo.availMem / (1024 * 1024)).toInt(),
            usedRamMb = ((memoryInfo.totalMem - memoryInfo.availMem) / (1024 * 1024)).toInt(),
            totalRamMb = (memoryInfo.totalMem / (1024 * 1024)).toInt(),
            cpuLoadPercent = 0f, // CPU load requires native/proc reading, keeping as 0 for now
            batteryLevelPercent = batteryLevel,
            thermalState = ThermalState.NOMINAL,
            snapshotEpochMs = System.currentTimeMillis()
        )
    }

    private fun getMemoryInfo(): ActivityManager.MemoryInfo {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        return memoryInfo
    }

    private fun getBatteryLevel(): Int {
        val intentFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryStatus = context.registerReceiver(null, intentFilter)
        val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        return if (level != -1 && scale != -1) (level * 100 / scale.toFloat()).toInt() else 0
    }

    override suspend fun requestGarbageCollection() {
        System.gc()
        System.runFinalization()
    }

    override fun startContinuousMonitoring(intervalMs: Long, onSnapshot: (DeviceResourceSnapshot) -> Unit) {
        monitorTimer = fixedRateTimer(period = intervalMs) {
            val memoryInfo = getMemoryInfo()
            val batteryLevel = getBatteryLevel()
            onSnapshot(DeviceResourceSnapshot(
                (memoryInfo.availMem / (1024 * 1024)).toInt(),
                ((memoryInfo.totalMem - memoryInfo.availMem) / (1024 * 1024)).toInt(),
                (memoryInfo.totalMem / (1024 * 1024)).toInt(),
                0f,
                batteryLevel,
                ThermalState.NOMINAL,
                System.currentTimeMillis()
            ))
        }
    }

    override fun stopContinuousMonitoring() {
        monitorTimer?.cancel()
        monitorTimer = null
    }
}
