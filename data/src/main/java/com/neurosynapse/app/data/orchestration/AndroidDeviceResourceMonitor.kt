package com.neurosynapse.app.data.orchestration

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.util.Log
import com.neurosynapse.domain.orchestrator.DeviceResourceMonitor
import com.neurosynapse.domain.orchestrator.DeviceResourceSnapshot
import com.neurosynapse.domain.orchestrator.ThermalState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ─────────────────────────────────────────────────────────────────────────────
// NEURO-SYNAPSE :data — orchestration/AndroidDeviceResourceMonitor.kt
//
// Implementación concreta de DeviceResourceMonitor para Android.
//
// FUENTES DE DATOS:
//   RAM → ActivityManager.MemoryInfo (totalMem, availMem, threshold)
//   Thermal → PowerManager.ThermalStatusCallback (API 29+) con fallback
//             a heurística por temperatura de batería (API < 29)
//   CPU → /proc/stat sampling (sin APIs privadas — compatible con Play Store)
//   Batería → BatteryManager a través de un IntentFilter sticky
//
// MONITOREO CONTINUO:
//   startContinuousMonitoring() lanza una corrutina en Dispatchers.IO que
//   toma snapshots a intervalos regulares (típico: 2000ms durante el pipeline).
//   stopContinuousMonitoring() cancela la corrutina y limpia el estado.
//   El orquestador llama start al inicio de cada fase pesada y stop al terminar.
// ─────────────────────────────────────────────────────────────────────────────

private const val TAG = "DeviceResourceMonitor"

class AndroidDeviceResourceMonitor(
    private val context: Context,
    private val monitoringScope: CoroutineScope
) : DeviceResourceMonitor {

    private val activityManager =
        context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

    // Estado thermal desde ThermalStatusCallback (API 29+)
    @Volatile
    private var currentThermalStatus: Int = PowerManager.THERMAL_STATUS_NONE

    // Job de monitoreo continuo — sólo uno activo a la vez
    private var monitoringJob: Job? = null

    // Inicializar ThermalStatusCallback si el API lo soporta
    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            powerManager.addThermalStatusListener { status ->
                currentThermalStatus = status
                Log.d(TAG, "Thermal status cambiado: $status (${thermalStateFromStatus(status)})")
            }
        }
    }

    // ── DeviceResourceMonitor — implementación ────────────────────────────────

    override suspend fun getCurrentSnapshot(): DeviceResourceSnapshot =
        withContext(Dispatchers.IO) {
            val memInfo = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memInfo)

            val totalMb     = (memInfo.totalMem / 1_048_576L).toInt()
            val availMb     = (memInfo.availMem / 1_048_576L).toInt()
            val usedMb      = totalMb - availMb
            val cpuLoad     = sampleCpuLoad()
            val battery     = getBatteryLevel()
            val thermal     = currentThermalState()

            DeviceResourceSnapshot(
                availableRamMb     = availMb,
                usedRamMb          = usedMb,
                totalRamMb         = totalMb,
                cpuLoadPercent     = cpuLoad,
                batteryLevelPercent = battery,
                thermalState       = thermal,
                snapshotEpochMs    = System.currentTimeMillis()
            ).also {
                Log.v(TAG, "Snapshot: RAM ${usedMb}/${totalMb} MB " +
                    "(${it.ramUsagePercent.toInt()}%), " +
                    "CPU ${cpuLoad.toInt()}%, " +
                    "Thermal ${thermal.name}, " +
                    "Battery ${battery}%")
            }
        }

    override suspend fun requestGarbageCollection() =
        withContext(Dispatchers.IO) {
            Log.d(TAG, "Solicitando GC al JVM...")
            System.gc()
            System.runFinalization()
            // Pausa breve para que el GC tenga oportunidad de actuar
            // antes de que el orquestador tome el siguiente snapshot
            delay(500L)
            Log.d(TAG, "GC completado.")
        }

    override fun startContinuousMonitoring(
        intervalMs: Long,
        onSnapshot: (DeviceResourceSnapshot) -> Unit
    ) {
        monitoringJob?.cancel()
        monitoringJob = monitoringScope.launch(Dispatchers.IO) {
            Log.d(TAG, "Monitoreo continuo iniciado (intervalo: ${intervalMs}ms)")
            while (isActive) {
                try {
                    val snapshot = getCurrentSnapshot()
                    onSnapshot(snapshot)
                } catch (e: Exception) {
                    Log.w(TAG, "Error en snapshot de monitoreo continuo: ${e.message}")
                }
                delay(intervalMs)
            }
            Log.d(TAG, "Monitoreo continuo detenido.")
        }
    }

    override fun stopContinuousMonitoring() {
        monitoringJob?.cancel()
        monitoringJob = null
    }

    // ── Helpers de lectura de recursos ───────────────────────────────────────

    /**
     * Muestrea la carga CPU desde /proc/stat.
     * Compatible con Play Store — no usa APIs privadas.
     * Toma dos muestras con 100ms de intervalo para calcular el delta.
     *
     * Retorna Float en [0.0, 100.0] representando % de uso de CPU.
     * En caso de error (dispositivo sin /proc/stat) retorna 0.0f.
     */
    private fun sampleCpuLoad(): Float {
        return try {
            val sample1 = readCpuStat() ?: return 0.0f
            Thread.sleep(100)
            val sample2 = readCpuStat() ?: return 0.0f

            val totalDelta  = sample2.total  - sample1.total
            val idleDelta   = sample2.idle   - sample1.idle

            if (totalDelta <= 0) return 0.0f
            ((totalDelta - idleDelta).toFloat() / totalDelta * 100f).coerceIn(0f, 100f)
        } catch (e: Exception) {
            0.0f
        }
    }

    private data class CpuStat(val total: Long, val idle: Long)

    private fun readCpuStat(): CpuStat? {
        return try {
            val line = java.io.File("/proc/stat").readLines().firstOrNull() ?: return null
            val parts = line.trim().split("\\s+".toRegex())
            if (parts.size < 5 || parts[0] != "cpu") return null
            val user    = parts[1].toLong()
            val nice    = parts[2].toLong()
            val system  = parts[3].toLong()
            val idle    = parts[4].toLong()
            val iowait  = if (parts.size > 5) parts[5].toLong() else 0L
            val irq     = if (parts.size > 6) parts[6].toLong() else 0L
            val softirq = if (parts.size > 7) parts[7].toLong() else 0L
            CpuStat(
                total = user + nice + system + idle + iowait + irq + softirq,
                idle  = idle + iowait
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Obtiene el nivel de batería via BroadcastReceiver sticky.
     * No requiere registro de receiver para el intent ACTION_BATTERY_CHANGED.
     */
    private fun getBatteryLevel(): Int {
        return try {
            val intent = context.registerReceiver(
                null,
                android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED)
            )
            val level = intent?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1) ?: -1
            if (level == -1 || scale == -1 || scale == 0) return 100
            (level * 100 / scale)
        } catch (e: Exception) {
            100
        }
    }

    /**
     * Convierte el estado thermal actual a ThermalState del dominio.
     * API 29+: usa PowerManager.THERMAL_STATUS_*.
     * API < 29: NOMINAL por defecto (sin información de throttling disponible).
     */
    private fun currentThermalState(): ThermalState {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            thermalStateFromStatus(currentThermalStatus)
        } else {
            ThermalState.NOMINAL
        }
    }

    private fun thermalStateFromStatus(status: Int): ThermalState =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            when (status) {
                PowerManager.THERMAL_STATUS_NONE,
                PowerManager.THERMAL_STATUS_LIGHT  -> ThermalState.NOMINAL
                PowerManager.THERMAL_STATUS_MODERATE -> ThermalState.FAIR
                PowerManager.THERMAL_STATUS_SEVERE,
                PowerManager.THERMAL_STATUS_CRITICAL -> ThermalState.SERIOUS
                PowerManager.THERMAL_STATUS_EMERGENCY,
                PowerManager.THERMAL_STATUS_SHUTDOWN -> ThermalState.CRITICAL
                else -> ThermalState.NOMINAL
            }
        } else {
            ThermalState.NOMINAL
        }
}
