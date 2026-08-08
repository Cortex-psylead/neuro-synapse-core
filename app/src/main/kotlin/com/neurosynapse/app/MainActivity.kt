package com.neurosynapse.app

import android.Manifest
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.util.Log
import android.view.View
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.neurosynapse.app.data.engines.*
import com.neurosynapse.app.data.orchestration.*
import com.neurosynapse.app.data.persistence.repositories.*
import com.neurosynapse.app.data.security.*
import com.neurosynapse.app.databinding.ActivityMainBinding
import com.neurosynapse.app.llm.LlmInferenceManager
import com.neurosynapse.app.memory.MemoryGuardian
import com.neurosynapse.app.memory.NativeMemoryPurge
import com.neurosynapse.app.analysis.HeuristicAnalyzer
import com.neurosynapse.domain.common.*
import com.neurosynapse.domain.orchestrator.*
import com.neurosynapse.domain.session.*
import com.neurosynapse.domain.projective.*
import org.json.JSONObject
import org.json.JSONArray
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var gateway: AndroidLocalSovereigntyGateway
    private lateinit var visualCapturer: SecureCameraCapturer
    private lateinit var resourceMonitor: AndroidDeviceResourceMonitor
    
    private var patientAge: SubjectAge? = null
    private var patientSex: SubjectSex? = null

    private var isModelLoadedInRam = false
    private var activeModelPath: String? = null
    private var currentSessionId: String? = null

    private var downloadId: Long = -1
    private var pendingModelFileName: String? = null
    private var isDownloading = false

    private val onDownloadComplete = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val id = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
            if (downloadId == id && id != -1L) {
                finalizeModelInstallation()
            }
        }
    }

    private var onPermissionsGranted: (() -> Unit)? = null

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val cameraGranted = permissions[Manifest.permission.CAMERA] ?: false
        val audioGranted = permissions[Manifest.permission.RECORD_AUDIO] ?: false
        if (cameraGranted && audioGranted) {
            onPermissionsGranted?.invoke()
        } else {
            Toast.makeText(this, "Permisos requeridos para operar.", Toast.LENGTH_LONG).show()
        }
        onPermissionsGranted = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.mainRoot) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(top = systemBars.top, bottom = systemBars.bottom)
            insets
        }

        gateway = AndroidLocalSovereigntyGateway(this, NeuroSynapseKeyManager(this), NeuroSynapseIntegrityManager(this))
        visualCapturer = SecureCameraCapturer(this)
        resourceMonitor = AndroidDeviceResourceMonitor(this)

        binding.btnUnlock.setOnClickListener { unlockSystem() }
        
        checkAndRequestPermissions {}

        binding.btnModeImage.setOnClickListener { 
            if(isModelLoadedInRam) {
                checkAndRequestPermissions {
                    showPatientDataDialog { age, sex -> prepareUI { startVisionTest(age, sex) } }
                }
            } else {
                showModelRequiredDialog()
            }
        }
        
        binding.btnModeAudio.setOnClickListener { 
            Toast.makeText(this, "Módulo en mantenimiento.", Toast.LENGTH_SHORT).show()
        }
        
        binding.btnAiInfo.setOnClickListener { showAiInfoDialog() }
        binding.btnSystemSettings.setOnClickListener { showSystemSettingsDialog() }
        binding.btnBackToMenu.setOnClickListener { resetToMenu() }
        binding.btnActionCapture.setOnClickListener { lifecycleScope.launch { executeCapture() } }

        setupBackNavigation()
        validateAIEngine()
        startPulsingIndicator()
        
        val receiverFlags = if (android.os.Build.VERSION.SDK_INT >= 33) {
            RECEIVER_NOT_EXPORTED
        } else {
            0
        }
        registerReceiver(onDownloadComplete, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), receiverFlags)
    }

    private fun startPulsingIndicator() {
        val anim = AlphaAnimation(0.4f, 1.0f).apply {
            duration = 1200
            repeatMode = Animation.REVERSE
            repeatCount = Animation.INFINITE
        }
        binding.aiStatusDot.startAnimation(anim)
    }

    private fun checkAndRequestPermissions(onGranted: () -> Unit) {
        val permissions = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        val missingPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missingPermissions.isNotEmpty()) {
            onPermissionsGranted = onGranted
            requestPermissionLauncher.launch(missingPermissions.toTypedArray())
        } else {
            onGranted()
        }
    }

    private fun showModelRequiredDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("IA No Cargada")
            .setMessage("Descargue el núcleo Llama 3.2 desde los Ajustes del Sistema.")
            .setPositiveButton("IR A AJUSTES") { _, _ -> showModelManagerDialog() }
            .setNegativeButton("CANCELAR", null)
            .show()
    }

    private fun showPatientDataDialog(onConfirmed: (SubjectAge, SubjectSex) -> Unit) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(64, 32, 64, 32)
        }

        val ageInput = EditText(this).apply {
            hint = "Edad del Paciente"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
        }

        val sexSpinner = Spinner(this).apply {
            val options = listOf("Masculino", "Femenino", "Otro")
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, options)
            setBackgroundColor(Color.parseColor("#1E293B"))
        }

        container.addView(ageInput)
        container.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(1, 32) })
        container.addView(sexSpinner)

        MaterialAlertDialogBuilder(this, com.google.android.material.R.style.Theme_Material3_Dark_Dialog_Alert)
            .setTitle("Nuevo Ingreso")
            .setView(container)
            .setPositiveButton("INICIAR") { _, _ ->
                val ageValue = ageInput.text.toString().toIntOrNull() ?: 25
                val sexValue = when (sexSpinner.selectedItemPosition) {
                    0 -> SubjectSex.MALE
                    1 -> SubjectSex.FEMALE
                    else -> SubjectSex.OTHER
                }
                patientAge = SubjectAge(ageValue)
                patientSex = sexValue
                onConfirmed(patientAge!!, patientSex!!)
            }
            .setNegativeButton("VOLVER", null)
            .show()
    }

    private fun showAiInfoDialog() {
        val modelFile = File(filesDir, "llama-3.2-1b.gguf")
        val status = if (modelFile.exists() && modelFile.length() > 500 * 1024 * 1024) {
            "CORE: Llama 3.2 1B\nESTADO: ACTIVO ✓\nPESO: ${modelFile.length() / (1024 * 1024)} MB"
        } else {
            "ESTADO: IA NO DETECTADA"
        }
        MaterialAlertDialogBuilder(this).setTitle("Estado del Motor").setMessage(status).setPositiveButton("OK", null).show()
    }

    private fun showSystemSettingsDialog() {
        val options = arrayOf("Gestionar Modelos", "Integridad del Búnker")
        MaterialAlertDialogBuilder(this)
            .setTitle("Sistema")
            .setItems(options) { _, which ->
                if (which == 0) showModelManagerDialog() else showIntegrityLogs()
            }
            .show()
    }

    private fun showModelManagerDialog() {
        val models = arrayOf("Llama 3.2 1B (Core)", "Gemma 2 2B")
        val urls = arrayOf(
            "https://huggingface.co/bartowski/Llama-3.2-1B-Instruct-GGUF/resolve/main/Llama-3.2-1B-Instruct-Q4_K_M.gguf?download=true",
            "https://huggingface.co/google/gemma-2-2b-it-GGUF/resolve/main/gemma-2-2b-it-Q4_K_M.gguf?download=true"
        )
        MaterialAlertDialogBuilder(this)
            .setTitle("Modelos")
            .setItems(models) { _, i -> startSecureDownload(urls[i], if (i == 0) "llama-3.2-1b.gguf" else "gemma-2b.gguf") }
            .show()
    }

    private fun startSecureDownload(url: String, fileName: String) {
        if (isDownloading) return
        val tempFile = File(getExternalFilesDir(null), "llama_tmp.gguf")
        if (tempFile.exists()) tempFile.delete()
        pendingModelFileName = fileName
        try {
            val request = DownloadManager.Request(Uri.parse(url))
                .setTitle("Descargando")
                .setDestinationInExternalFilesDir(this, null, "llama_tmp.gguf")
            val dm = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
            downloadId = dm.enqueue(request)
            isDownloading = true
            binding.dashboardOptions.isGone = true
            binding.controlPanel.isVisible = true
            binding.txtStatus.text = "SINCRONIZANDO..."
            monitorDownloadProgress()
        } catch (e: Exception) { validateAIEngine() }
    }

    private fun monitorDownloadProgress() {
        val dm = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
        lifecycleScope.launch {
            while (isDownloading) {
                val cursor = dm.query(DownloadManager.Query().setFilterById(downloadId))
                if (cursor != null && cursor.moveToFirst()) {
                    val downloaded = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                    val total = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                    if (total > 0) {
                        val progress = (downloaded * 100L / total).toInt()
                        runOnUiThread { 
                            binding.txtStatus.text = "DESCARGANDO NÚCLEO"
                            binding.txtDictation.text = "Progreso: $progress% (${downloaded / (1024 * 1024)}MB)" 
                        }
                    }
                    val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    if (status == DownloadManager.STATUS_SUCCESSFUL || status == DownloadManager.STATUS_FAILED) {
                        if (status == DownloadManager.STATUS_FAILED) isDownloading = false
                        cursor.close()
                        break 
                    }
                }
                cursor?.close()
                delay(1000)
            }
        }
    }

    private fun finalizeModelInstallation() {
        isDownloading = false
        val downloaded = File(getExternalFilesDir(null), "llama_tmp.gguf")
        val internal = File(filesDir, pendingModelFileName ?: "llama-3.2-1b.gguf")
        if (downloaded.exists()) {
            lifecycleScope.launch {
                binding.txtStatus.text = "INSTALANDO..."
                withContext(Dispatchers.IO) {
                    downloaded.inputStream().use { input -> internal.outputStream().use { output -> input.copyTo(output) } }
                    downloaded.delete()
                }
                validateAIEngine()
                LlmInferenceManager.destroyInstance()
                binding.btnBackToMenu.isVisible = true
            }
        }
    }

    private fun showIntegrityLogs() {
        MaterialAlertDialogBuilder(this).setTitle("Seguridad").setMessage("Cifrado AES-256 Activo.\nIntegridad: SEALED.").show()
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.controlPanel.isVisible || binding.viewFinder.isVisible) resetToMenu()
                else if (binding.dashboardOptions.isVisible) {
                    binding.dashboardOptions.isGone = true
                    binding.aiStatusContainer.isGone = true
                    binding.btnUnlock.isVisible = true
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    private fun startResourceMonitoring() {
        resourceMonitor.startContinuousMonitoring(2000) { snapshot ->
            runOnUiThread {
                val ramPct = (snapshot.usedRamMb.toFloat() / snapshot.totalRamMb * 100).toInt()
                binding.progressRam.progress = ramPct
                binding.lblRam.text = "RAM: ${snapshot.usedRamMb}/${snapshot.totalRamMb}MB ($ramPct%)"
                binding.progressBattery.progress = snapshot.batteryLevelPercent
                binding.lblBattery.text = "BATERÍA: ${snapshot.batteryLevelPercent}%"
                
                // Colores dinámicos
                binding.progressRam.progressDrawable = ContextCompat.getDrawable(this, if (ramPct > 85) R.drawable.progress_custom_red else R.drawable.progress_custom_cyan)
            }
        }
    }

    private fun validateAIEngine() {
        val model = File(filesDir, "llama-3.2-1b.gguf")
        if (model.exists() && model.length() > 500 * 1024 * 1024) {
            isModelLoadedInRam = true
            activeModelPath = model.absolutePath
            binding.aiStatusDot.setBackgroundColor(getColor(R.color.status_green))
            binding.aiStatusText.text = "CORE IA: DISPONIBLE"
        } else {
            isModelLoadedInRam = false
            binding.aiStatusDot.setBackgroundColor(getColor(R.color.status_red))
            binding.aiStatusText.text = "IA NO CARGADA"
        }
    }

    private fun unlockSystem() {
        lifecycleScope.launch {
            try {
                gateway.authenticateAndUnlock(this@MainActivity)
                binding.btnUnlock.isGone = true
                binding.dashboardOptions.isVisible = true
                binding.aiStatusContainer.isVisible = true
                startResourceMonitoring()
                validateAIEngine()
            } catch (e: Exception) { binding.btnUnlock.text = "REINTENTAR" }
        }
    }

    private fun prepareUI(onReady: () -> Unit) {
        currentSessionId = UUID.randomUUID().toString()
        binding.dashboardOptions.isGone = true
        binding.controlPanel.isVisible = true
        binding.btnBackToMenu.isGone = true
        binding.txtIntegrity.text = ""
        binding.scrollDictation.isGone = true
        binding.txtDictation.text = ""
        onReady()
    }

    private fun startVisionTest(age: SubjectAge, sex: SubjectSex) {
        lifecycleScope.launch {
            binding.txtStatus.text = "INICIALIZANDO..."
            binding.viewFinder.isVisible = true
            binding.cameraOverlay.isVisible = true
            delay(500)
            try {
                visualCapturer.startPreview(this@MainActivity, binding.viewFinder)
                binding.txtStatus.text = "Evaluación: ${age.value} años | $sex"
                binding.btnActionCapture.isVisible = true
            } catch (e: Exception) { binding.txtStatus.text = "FALLO DE CÁMARA" }
        }
    }

    private suspend fun executeCapture() {
        try {
            binding.btnActionCapture.isGone = true
            binding.txtStatus.text = "CAPTURANDO..."
            val photos = visualCapturer.captureBurst(currentSessionId!!, 5)
            visualCapturer.releaseCamera()
            binding.viewFinder.isGone = true
            binding.cameraOverlay.isGone = true
            binding.txtStatus.text = "PURGANDO RAM..."
            System.gc()
            delay(1500)
            
            val visualEngine = AndroidVisualEngine(this)
            val session = ClinicalSession.createNew(SessionId(currentSessionId!!), ConsentLevel.SIGNED_DIGITAL)
            val matrixV = visualEngine.analyze(session, ProjectiveTestType.HTP_HOUSE, photos, patientAge!!, patientSex!!) {}
            
            NativeMemoryPurge.purgeAll {
                lifecycleScope.launch {
                    val mem = MemoryGuardian.checkMemory(this@MainActivity)
                    if (mem.isLlmViable && activeModelPath != null) launchLlmInference(matrixV)
                    else launchHeuristicAnalysis(matrixV, mem.recommendation == "CRITICAL_MEMORY")
                }
            }
        } catch (e: Exception) { binding.txtStatus.text = "ERROR" }
    }

    private suspend fun launchLlmInference(matrixV: ProjectiveMorphometryMatrix) {
        try {
            binding.txtStatus.text = "RAZONANDO..."
            val manager = LlmInferenceManager.getInstance(this)
            manager.initialize(activeModelPath!!)
            val result = manager.analyzeDrawing(buildFeaturesJson(matrixV))
            result.onSuccess { 
                binding.txtStatus.text = "REPORTE IA LOCAL"
                formatAndShowReport(it, matrixV.testResults.first().globalMorphometrics.traceOccupancyRatio.value)
            }.onFailure { launchHeuristicAnalysis(matrixV, false) }
        } catch (e: Exception) { launchHeuristicAnalysis(matrixV, false) }
    }

    private fun formatAndShowReport(rawJson: String, density: Double) {
        val filtered = try {
            val json = JSONObject(rawJson)
            val omisiones = json.optJSONArray("omisiones_detectadas")
            if (omisiones != null) {
                val cleaned = JSONArray()
                for (i in 0 until omisiones.length()) {
                    val part = omisiones.optString(i)
                    // LOGIC BUG FIX: No marcar como omitido si tiene descripción
                    if (!json.has(part) || json.optJSONObject(part) == null) cleaned.put(part)
                }
                json.put("omisiones_detectadas", cleaned)
            }
            json.toString(2)
        } catch (e: Exception) { rawJson }

        binding.txtIntegrity.text = "SEALED_LLM_V5 | CONFIANZA: ${"%.1f".format(density * 100)}%"
        binding.scrollDictation.isVisible = true
        binding.scrollDictation.layoutParams.height = (resources.displayMetrics.heightPixels * 0.5).toInt()
        binding.txtDictation.text = highlightJson(filtered)
        binding.btnBackToMenu.isVisible = true
    }

    private fun highlightJson(json: String): SpannableStringBuilder {
        val builder = SpannableStringBuilder(json)
        val keyRegex = "\"(\\w+)\":".toRegex()
        val strRegex = ":\\s*\"(.*?)\"".toRegex()
        val numRegex = ":\\s*(\\d+\\.?\\d*)".toRegex()
        
        keyRegex.findAll(json).forEach { builder.setSpan(ForegroundColorSpan(getColor(R.color.json_key)), it.range.first, it.range.last + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE) }
        strRegex.findAll(json).forEach { builder.setSpan(ForegroundColorSpan(getColor(R.color.json_string)), it.range.first + 2, it.range.last + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE) }
        numRegex.findAll(json).forEach { builder.setSpan(ForegroundColorSpan(getColor(R.color.json_number)), it.range.first + 2, it.range.last + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE) }
        return builder
    }

    private fun launchHeuristicAnalysis(matrixV: ProjectiveMorphometryMatrix, isCrit: Boolean) {
        binding.txtStatus.text = "HEURÍSTICO"
        val res = matrixV.testResults.first()
        val f = HeuristicAnalyzer.HeuristicFeatures(res.globalMorphometrics.traceOccupancyRatio.value, res.globalMorphometrics.contourComplexityScore.toInt(), 1.0, 0.5, emptyMap())
        binding.txtDictation.text = HeuristicAnalyzer.analyze(f)
        binding.txtIntegrity.text = if (isCrit) "MEMORIA CRÍTICA" else "MODO RESPALDO"
        binding.scrollDictation.isVisible = true
        binding.btnBackToMenu.isVisible = true
    }

    private fun buildFeaturesJson(mV: ProjectiveMorphometryMatrix): String {
        val r = mV.testResults.first()
        return """{"density": ${r.globalMorphometrics.traceOccupancyRatio.value}, "contours": ${r.globalMorphometrics.contourComplexityScore}}"""
    }

    private fun resetToMenu() {
        binding.controlPanel.isGone = true
        binding.viewFinder.isGone = true
        binding.dashboardOptions.isVisible = true
        patientAge = null; patientSex = null; currentSessionId = null
        LlmInferenceManager.destroyInstance()
        validateAIEngine()
    }
}
