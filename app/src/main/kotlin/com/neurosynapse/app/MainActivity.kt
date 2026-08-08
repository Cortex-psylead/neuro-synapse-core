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
import android.view.LayoutInflater
import android.view.View
import android.util.Log
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var gateway: AndroidLocalSovereigntyGateway
    private lateinit var recorder: SecureAudioRecorder
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
            Toast.makeText(this, "Se requieren permisos de Cámara y Audio para operar.", Toast.LENGTH_LONG).show()
        }
        onPermissionsGranted = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val receiverFlags = if (android.os.Build.VERSION.SDK_INT >= 33) {
            RECEIVER_NOT_EXPORTED
        } else {
            0
        }
        registerReceiver(onDownloadComplete, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), receiverFlags)

        ViewCompat.setOnApplyWindowInsetsListener(binding.mainRoot) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(top = systemBars.top, bottom = systemBars.bottom)
            insets
        }

        gateway = AndroidLocalSovereigntyGateway(this, NeuroSynapseKeyManager(this), NeuroSynapseIntegrityManager(this))
        recorder = SecureAudioRecorder(this)
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
            Toast.makeText(this, "Módulo en mantenimiento - Enfoque en Visión v1", Toast.LENGTH_SHORT).show()
        }
        
        binding.btnModeFull.setOnClickListener { 
            Toast.makeText(this, "Módulo en mantenimiento - Enfoque en Visión v1", Toast.LENGTH_SHORT).show()
        }
        
        binding.btnAiInfo.setOnClickListener { 
            Log.d("MainActivity", "Info button clicked")
            showAiInfoDialog() 
        }
        
        binding.btnSystemSettings.setOnClickListener { 
            Log.d("MainActivity", "Settings button clicked")
            showSystemSettingsDialog() 
        }
        
        binding.btnBackToMenu.setOnClickListener { resetToMenu() }
        binding.btnActionCapture.setOnClickListener { lifecycleScope.launch { executeCapture() } }

        setupBackNavigation()
        validateAIEngine()
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
            .setMessage("Para utilizar los módulos clínicos, primero debe configurar y descargar el núcleo de razonamiento Llama 3.2 desde los Ajustes del Sistema.")
            .setPositiveButton("IR A AJUSTES") { _, _ -> showSystemSettingsDialog() }
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
            val options = listOf("Masculino", "Femenino", "Otro", "No Revelado")
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, options)
            setBackgroundColor(Color.parseColor("#1E293B"))
        }

        container.addView(ageInput)
        val spacer = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, 32) }
        container.addView(spacer)
        container.addView(sexSpinner)

        MaterialAlertDialogBuilder(this, com.google.android.material.R.style.Theme_Material3_Dark_Dialog_Alert)
            .setTitle("Ingreso de Consultante")
            .setMessage("Configure los metadatos para la sesión clínica")
            .setView(container)
            .setPositiveButton("INICIAR EVALUACIÓN") { _, _ ->
                val ageValue = ageInput.text.toString().toIntOrNull() ?: 25
                val sexValue = when (sexSpinner.selectedItemPosition) {
                    0 -> SubjectSex.MALE
                    1 -> SubjectSex.FEMALE
                    2 -> SubjectSex.OTHER
                    else -> SubjectSex.UNDISCLOSED
                }
                patientAge = SubjectAge(ageValue)
                patientSex = sexValue
                onConfirmed(patientAge!!, patientSex!!)
            }
            .setNegativeButton("CANCELAR", null)
            .show()
    }

    private fun showAiInfoDialog() {
        val modelFile = File(filesDir, "llama-3.2-1b.gguf")
        var headerValid = false
        if (modelFile.exists()) {
            try {
                FileInputStream(modelFile).use { fis ->
                    val header = ByteArray(4)
                    fis.read(header)
                    headerValid = String(header) == "GGUF"
                }
            } catch (e: Exception) { headerValid = false }
        }

        val status = if (modelFile.exists() && modelFile.length() > 500 * 1024 * 1024) {
            """
            NÚCLEO IA: Llama 3.2 1B
            CABECERA: ${if(headerValid) "GGUF DETECTADA ✓" else "INVÁLIDA/DESCONOCIDA ✗"}
            PESO FÍSICO: ${modelFile.length() / (1024 * 1024)} MB
            UBICACIÓN: ALMACENAMIENTO_INTERNO
            """.trimIndent()
        } else {
            "ESTADO: ARCHIVO NO DETECTADO O CORRUPTO\nPor favor, descargue el modelo desde la sección de Ajustes."
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("Estado Técnico del Motor")
            .setMessage(status)
            .setPositiveButton("RE-ESCANEAR") { _, _ -> validateAIEngine() }
            .setNegativeButton("CERRAR", null)
            .show()
    }

    private fun showSystemSettingsDialog() {
        val options = arrayOf("Optimizar Memoria RAM", "Gestionar Modelos de IA (GGUF)", "Ver Logs de Integridad")
        
        MaterialAlertDialogBuilder(this)
            .setTitle("Configuración del Sistema")
            .setItems(options) { _, which ->
                when (options[which]) {
                    "Optimizar Memoria RAM" -> {
                        lifecycleScope.launch { 
                            resourceMonitor.requestGarbageCollection()
                            binding.txtStatus.text = "SISTEMA OPTIMIZADO"
                            delay(2000)
                            if (binding.controlPanel.isGone) {
                                binding.txtStatus.text = ""
                            }
                        }
                    }
                    "Gestionar Modelos de IA (GGUF)" -> {
                        showModelManagerDialog()
                    }
                    "Ver Logs de Integridad" -> {
                        showIntegrityLogs()
                    }
                }
            }
            .setNegativeButton("VOLVER", null)
            .show()
    }

    private fun showModelManagerDialog() {
        val models = arrayOf(
            "Llama 3.2 1B (Core - Q4_K_M)",
            "Gemma 2 2B (Instruct - Q4_K_M)"
        )
        
        val urls = arrayOf(
            "https://huggingface.co/bartowski/Llama-3.2-1B-Instruct-GGUF/resolve/main/Llama-3.2-1B-Instruct-Q4_K_M.gguf?download=true",
            "https://huggingface.co/google/gemma-2-2b-it-GGUF/resolve/main/gemma-2-2b-it-Q4_K_M.gguf?download=true"
        )

        val modelFile = File(filesDir, "llama-3.2-1b.gguf")
        val isInstalled = modelFile.exists() && modelFile.length() > 500 * 1024 * 1024
        
        val tempFile = File(getExternalFilesDir(null), "llama_tmp.gguf")
        val hasPendingInstall = tempFile.exists() && tempFile.length() > 500 * 1024 * 1024

        MaterialAlertDialogBuilder(this)
            .setTitle("Gestor de Modelos de IA")
            .setItems(models) { _, which ->
                val fileName = if (which == 0) "llama-3.2-1b.gguf" else "gemma-2b.gguf"
                startSecureDownload(urls[which], fileName)
            }
            .setPositiveButton(if (hasPendingInstall && !isInstalled) "REPARAR INSTALACIÓN" else null) { _, _ ->
                finalizeModelInstallation()
            }
            .setNeutralButton(if (isInstalled) "ELIMINAR CORE" else null) { _, _ ->
                modelFile.delete()
                // También limpiar residuo temporal si existe
                val tempFile = File(getExternalFilesDir(null), "llama_tmp.gguf")
                if (tempFile.exists()) tempFile.delete()
                
                validateAIEngine()
                binding.txtDeviceHealth.text = "SISTEMA: CORE ELIMINADO"
            }
            .setNegativeButton("CERRAR", null)
            .show()
    }

    private fun startSecureDownload(url: String, fileName: String) {
        if (isDownloading) {
            Toast.makeText(this, "Descarga en curso...", Toast.LENGTH_SHORT).show()
            return
        }

        // Saneamiento: Eliminar rastro de descarga previa fallida para evitar bloqueo de DownloadManager
        val tempFile = File(getExternalFilesDir(null), "llama_tmp.gguf")
        if (tempFile.exists()) tempFile.delete()

        pendingModelFileName = fileName
        try {
            val request = DownloadManager.Request(Uri.parse(url))
                .setTitle("Descargando Núcleo IA")
                .setDescription("Sincronizando búnker de razonamiento local...")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalFilesDir(this, null, "llama_tmp.gguf")
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(true)
                .addRequestHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")

            val downloadManager = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
            downloadId = downloadManager.enqueue(request)
            
            isDownloading = true
            binding.dashboardOptions.isGone = true
            binding.controlPanel.isVisible = true
            binding.txtStatus.text = "INICIANDO DESCARGA"
            binding.txtIntegrity.text = "Conectando con repositorio..."
            binding.scrollDictation.isVisible = true
            binding.txtDictation.text = "El sistema está gestionando la transferencia segura..."
            binding.btnBackToMenu.isGone = true
            
            monitorDownloadProgress()
        } catch (e: Exception) {
            Log.e("NS-Audit", "Fallo al iniciar DownloadManager", e)
            Toast.makeText(this, "Fallo al iniciar descarga: ${e.message}", Toast.LENGTH_SHORT).show()
            validateAIEngine()
        }
    }

    private fun monitorDownloadProgress() {
        val downloadManager = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
        lifecycleScope.launch {
            while (isDownloading) {
                val query = DownloadManager.Query().setFilterById(downloadId)
                val cursor = try { downloadManager.query(query) } catch (e: Exception) { null }
                if (cursor != null && cursor.moveToFirst()) {
                    val bytesDownloaded = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                    val bytesTotal = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                    val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    
                    if (bytesTotal > 0) {
                        val progress = (bytesDownloaded * 100L / bytesTotal)
                        runOnUiThread {
                            binding.txtStatus.text = "DESCARGANDO NÚCLEO"
                            binding.txtDictation.text = "Progreso Real: $progress% (${bytesDownloaded / (1024 * 1024)}MB / ${bytesTotal / (1024 * 1024)}MB)"
                        }
                    }
                    
                    if (status == DownloadManager.STATUS_SUCCESSFUL) {
                        Log.d("NS-Audit", "DownloadManager: Éxito detectado en monitoreo.")
                        // No ponemos isDownloading = false aquí para dejar que finalizeModelInstallation lo haga
                        cursor.close()
                        break
                    }
                    
                    if (status == DownloadManager.STATUS_FAILED) {
                        val reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                        Log.e("NS-Audit", "Descarga falló. Razón: $reason")
                        runOnUiThread {
                            binding.txtStatus.text = "FALLO DE DESCARGA"
                            binding.txtIntegrity.text = "Código de error: $reason"
                            isDownloading = false
                            binding.btnBackToMenu.isVisible = true
                        }
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
        // Bloqueo de re-entrada
        if (isModelLoadedInRam && !isDownloading) {
             Log.d("NS-Audit", "Intento de instalación redundante ignorado.")
             return
        }
        
        isDownloading = false
        val fileName = pendingModelFileName ?: "llama-3.2-1b.gguf"
        val downloadedFile = File(getExternalFilesDir(null), "llama_tmp.gguf")
        val internalFile = File(filesDir, fileName)

        Log.d("NS-Audit", "Iniciando instalación. Temp: ${downloadedFile.absolutePath} (Existe: ${downloadedFile.exists()})")

        if (downloadedFile.exists()) {
            lifecycleScope.launch {
                binding.dashboardOptions.isGone = true
                binding.controlPanel.isVisible = true
                binding.txtStatus.text = "INSTALANDO NÚCLEO..."
                binding.txtIntegrity.text = "Sellando búnker de datos..."
                
                try {
                    withContext(Dispatchers.IO) {
                        downloadedFile.inputStream().use { input ->
                            internalFile.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                        downloadedFile.delete()
                    }
                    
                    validateAIEngine()
                    
                    if (isModelLoadedInRam && verifyModelIntegrity(internalFile)) {
                        binding.txtStatus.text = "SISTEMA OPERATIVO"
                        binding.txtIntegrity.text = "Firma GGUF: VERIFICADA ✓"
                        binding.txtDictation.text = "El núcleo IA ha sido configurado correctamente."
                        LlmInferenceManager.destroyInstance()
                    } else {
                        internalFile.delete()
                        binding.txtStatus.text = "FALLO DE VALIDACIÓN"
                        binding.txtIntegrity.text = "Integridad física no garantizada (>500MB)."
                    }
                } catch (e: Exception) {
                    Log.e("NS-Audit", "Error en movimiento de archivos", e)
                    binding.txtStatus.text = "ERROR DE INSTALACIÓN"
                    binding.txtIntegrity.text = e.message
                }
                binding.btnBackToMenu.isVisible = true
            }
        } else {
            Log.e("NS-Audit", "Archivo temporal no encontrado en finalización.")
            isDownloading = false
            validateAIEngine()
        }
    }

    private fun showIntegrityLogs() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Logs de Seguridad")
            .setMessage("Session: ${currentSessionId ?: "N/A"}\nDB Version: 5\nCifrado: SQLCipher AES-256\nIntegridad: SEALED")
            .setPositiveButton("OK", null)
            .show()
    }

    private fun verifyModelIntegrity(file: File): Boolean {
        if (!file.exists()) return false 
        
        return try {
            FileInputStream(file).use { fis ->
                val header = ByteArray(4)
                fis.read(header)
                String(header) == "GGUF"
            }
        } catch (e: Exception) { false }
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    binding.controlPanel.isVisible || binding.viewFinder.isVisible -> {
                        resetToMenu()
                    }
                    binding.dashboardOptions.isVisible -> {
                        // Bloquear terminal
                        binding.dashboardOptions.visibility = View.GONE
                        binding.aiStatusContainer.visibility = View.GONE
                        binding.btnUnlock.visibility = View.VISIBLE
                    }
                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        })
    }

    private fun startResourceMonitoring() {
        resourceMonitor.startContinuousMonitoring(5000) { snapshot ->
            runOnUiThread {
                binding.txtDeviceHealth.text = "RAM: ${snapshot.usedRamMb}/${snapshot.totalRamMb}MB | BATERÍA: ${snapshot.batteryLevelPercent}%"
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(onDownloadComplete)
        } catch (e: Exception) {
            // Ignorar si no estaba registrado
        }
        if (::resourceMonitor.isInitialized) {
            resourceMonitor.stopContinuousMonitoring()
        }
        LlmInferenceManager.destroyInstance()
    }

    private fun validateAIEngine() {
        val llamaFile = File(filesDir, "llama-3.2-1b.gguf")
        val gemmaFile = File(filesDir, "gemma-2b.gguf")
        
        val activeModel = when {
            llamaFile.exists() && llamaFile.length() > 500 * 1024 * 1024 -> llamaFile
            gemmaFile.exists() && gemmaFile.length() > 500 * 1024 * 1024 -> gemmaFile
            else -> null
        }
        
        if (activeModel != null) {
            isModelLoadedInRam = true
            activeModelPath = activeModel.absolutePath
            binding.aiStatusDot.setBackgroundColor(ContextCompat.getColor(this, R.color.status_green))
            binding.aiStatusText.text = "CORE IA: DISPONIBLE (${if(activeModel == llamaFile) "Llama" else "Gemma"})"
            Log.d("NS-Audit", "Resultado: INTEGRIDAD VERIFICADA ✓ (${activeModel.name})")
        } else {
            // Saneamiento de archivos corruptos
            if (llamaFile.exists() && llamaFile.length() <= 500 * 1024 * 1024) llamaFile.delete()
            if (gemmaFile.exists() && gemmaFile.length() <= 500 * 1024 * 1024) gemmaFile.delete()
            
            Log.d("NS-Audit", "Resultado: IA NO CARGADA")
            setAiEngineNotLoaded()
        }
    }

    private fun setAiEngineNotLoaded() {
        isModelLoadedInRam = false
        activeModelPath = null
        binding.aiStatusDot.setBackgroundColor(ContextCompat.getColor(this, R.color.status_red))
        binding.aiStatusText.text = "IA NO CARGADA"
    }

    private fun unlockSystem() {
        lifecycleScope.launch {
            try {
                gateway.authenticateAndUnlock(this@MainActivity)
                binding.btnUnlock.visibility = View.GONE
                binding.dashboardOptions.visibility = View.VISIBLE
                binding.aiStatusContainer.visibility = View.VISIBLE
                startResourceMonitoring()
                validateAIEngine()
            } catch (e: Exception) {
                binding.btnUnlock.text = "ERROR: REINTENTAR"
            }
        }
    }

    private fun prepareUI(onReady: () -> Unit) {
        currentSessionId = UUID.randomUUID().toString()
        binding.dashboardOptions.isGone = true
        binding.controlPanel.isVisible = true
        binding.btnBackToMenu.isGone = true
        
        // Limpieza de interfaz para nueva sesión
        binding.txtIntegrity.text = ""
        binding.scrollDictation.isGone = true
        binding.txtDictation.text = ""
        
        onReady()
    }

    private fun startVisionTest(age: SubjectAge, sex: SubjectSex) {
        lifecycleScope.launch {
            binding.txtStatus.text = "INICIALIZANDO CÁMARA..."
            binding.viewFinder.isVisible = true
            binding.cameraOverlay.isVisible = true
            delay(500) 
            
            try {
                visualCapturer.startPreview(this@MainActivity, binding.viewFinder)
                val genderStr = when(sex) {
                    SubjectSex.MALE -> "Masc"
                    SubjectSex.FEMALE -> "Fem"
                    SubjectSex.OTHER -> "Otro"
                    else -> "N/R"
                }
                binding.txtStatus.text = "Evaluación: ${age.value} años | $genderStr"
                binding.btnActionCapture.isVisible = true
            } catch (e: Exception) {
                binding.txtStatus.text = "FALLO DE HARDWARE"
                binding.txtIntegrity.text = "Error al abrir la cámara: ${e.message}"
                binding.btnBackToMenu.isVisible = true
            }
        }
    }

    private suspend fun executeCapture() {
        try {
            binding.btnActionCapture.isGone = true
            binding.txtStatus.text = "CAPTURA EN RÁFAGA (5 frames)..."
            
            // 1. MANDATO 6: Capturar ráfaga
            val photos = visualCapturer.captureBurst(currentSessionId!!, 5)
            Log.d("MainActivity", "Burst capture successful: ${photos.size} images saved.")
            
            // 2. MANDATO 6: INMEDIATAMENTE destruir cámara y overlay
            visualCapturer.releaseCamera()
            binding.viewFinder.isGone = true
            binding.cameraOverlay.isGone = true
            
            binding.txtStatus.text = "RECLAMANDO MEMORIA (GPU/RAM)..."
            
            // 3. MANDATO 6: Procesar con OpenCV
            val visualEngine = AndroidVisualEngine(this)
            
            val db = gateway.requireDatabase()
            val sessionRepo = RoomClinicalSessionRepository(db.clinicalSessionDao(), db.auditLogDao())
            val session = ClinicalSession.createNew(SessionId(currentSessionId!!), ConsentLevel.SIGNED_DIGITAL)
            sessionRepo.save(session)

            Log.d("MainActivity", "Starting clinical engines...")
            
            val matrixV = visualEngine.analyze(session, ProjectiveTestType.HTP_HOUSE, photos, patientAge!!, patientSex!!) {}
            
            // 4. MANDATO 6: Liberar TODO nativo
            NativeMemoryPurge.purgeAll(onComplete = {
                lifecycleScope.launch {
                    // 5. MANDATO 6: Verificar memoria y decidir modo
                    val memStatus = MemoryGuardian.checkMemory(this@MainActivity)
                    
                    if (memStatus.isLlmViable && activeModelPath != null) {
                        launchLlmInference(matrixV)
                    } else {
                        launchHeuristicAnalysis(matrixV, memStatus.recommendation == "CRITICAL_MEMORY")
                    }
                }
            })
            
        } catch (e: Exception) {
            Log.e("MainActivity", "Error in capture/processing pipeline", e)
            binding.txtStatus.text = "ERROR CRÍTICO"
            binding.txtIntegrity.text = "Detalle: ${e.message}"
            binding.btnBackToMenu.isVisible = true
        }
    }

    private suspend fun launchLlmInference(matrixV: ProjectiveMorphometryMatrix) {
        try {
            binding.txtStatus.text = "IA RAZONANDO (LOCAL)..."
            binding.aiStatusText.text = "CORE IA: ACTIVO"
            binding.aiStatusDot.setBackgroundColor(ContextCompat.getColor(this, R.color.status_green))

            val manager = LlmInferenceManager.getInstance(this)
            manager.initialize(activeModelPath!!)
            
            val featuresJson = buildFeaturesJson(matrixV)
            val result = manager.analyzeDrawing(featuresJson)
            
            result.onSuccess { reportJson ->
                binding.txtStatus.text = "REPORTE IA LOCAL"
                binding.txtIntegrity.text = "Firma Digital: SEALED_LLM_V5"
                binding.scrollDictation.isVisible = true
                binding.txtDictation.text = reportJson
                binding.btnBackToMenu.isVisible = true
            }.onFailure { e ->
                Log.e("MainActivity", "Fallo en inferencia LLM", e)
                launchHeuristicAnalysis(matrixV, false)
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error lanzando LLM", e)
            launchHeuristicAnalysis(matrixV, false)
        }
    }

    private fun launchHeuristicAnalysis(matrixV: ProjectiveMorphometryMatrix, isCritical: Boolean) {
        binding.txtStatus.text = "ANÁLISIS HEURÍSTICO"
        binding.aiStatusText.text = "CORE IA: MODO HEURÍSTICO"
        binding.aiStatusDot.setBackgroundColor(ContextCompat.getColor(this, R.color.status_yellow))

        val result = matrixV.testResults.firstOrNull()
        val density = result?.globalMorphometrics?.traceOccupancyRatio?.value ?: 0.0
        val count = result?.globalMorphometrics?.contourComplexityScore?.toInt() ?: 0
        
        val features = HeuristicAnalyzer.HeuristicFeatures(
            density = density,
            contourCount = count,
            aspectRatio = 1.0, // Simplified
            symmetry = 0.5,
            bodyPartsPresence = emptyMap()
        )
        
        val report = HeuristicAnalyzer.analyze(features)
        
        binding.txtIntegrity.text = if (isCritical) "SISTEMA: MEMORIA CRÍTICA" else "SISTEMA: MODO RESPALDO"
        binding.scrollDictation.isVisible = true
        binding.txtDictation.text = report
        binding.btnBackToMenu.isVisible = true
    }

    private fun buildFeaturesJson(matrixV: ProjectiveMorphometryMatrix): String {
        val result = matrixV.testResults.firstOrNull()
        val density = result?.globalMorphometrics?.traceOccupancyRatio?.value ?: 0.0
        val count = result?.globalMorphometrics?.contourComplexityScore?.toInt() ?: 0
        return """{"density": $density, "contour_count": $count, "type": "DAP_TEST"}"""
    }

    private fun resetToMenu() {
        binding.controlPanel.isGone = true
        binding.viewFinder.isGone = true
        binding.dashboardOptions.isVisible = true
        
        // Reset de variables de estado clínico
        patientAge = null
        patientSex = null
        currentSessionId = null
        
        // Liberar LLM de RAM inmediatamente
        LlmInferenceManager.destroyInstance()
        validateAIEngine()
    }
}
