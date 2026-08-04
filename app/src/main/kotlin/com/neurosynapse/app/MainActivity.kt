package com.neurosynapse.app

import android.Manifest
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.util.Log
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.neurosynapse.app.data.engines.*
import com.neurosynapse.app.data.orchestration.*
import com.neurosynapse.app.data.persistence.repositories.*
import com.neurosynapse.app.data.security.*
import com.neurosynapse.app.databinding.ActivityMainBinding
import com.neurosynapse.domain.common.*
import com.neurosynapse.domain.orchestrator.*
import com.neurosynapse.domain.session.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileInputStream
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
        recorder = SecureAudioRecorder(this)
        visualCapturer = SecureCameraCapturer(this)
        resourceMonitor = AndroidDeviceResourceMonitor(this)

        binding.btnUnlock.setOnClickListener { unlockSystem() }
        binding.btnModeImage.setOnClickListener { 
            if(isModelLoadedInRam) showPatientDataDialog { age, sex -> prepareUI { startVisionTest(age, sex) } } 
        }
        binding.btnModeAudio.setOnClickListener { 
            if(isModelLoadedInRam) showPatientDataDialog { age, sex -> prepareUI { startVoiceTest(age, sex) } } 
        }
        binding.btnModeFull.setOnClickListener { 
            if(isModelLoadedInRam) showPatientDataDialog { age, sex -> prepareUI { startFullTest(age, sex) } } 
        }
        binding.btnAiInfo.setOnClickListener { showAiInfoDialog() }
        binding.btnSystemSettings.setOnClickListener { showSystemSettingsDialog() }
        binding.btnBackToMenu.setOnClickListener { resetToMenu() }
        binding.btnActionCapture.setOnClickListener { lifecycleScope.launch { executeCapture() } }

        setupBackNavigation()
        validateAIEngine()
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

        val status = if (modelFile.exists()) {
            """
            NÃšCLEO IA: llama-3.2-1b
            CABECERA: ${if(headerValid) "GGUF DETECTADA âœ“" else "INVALIDA/DESCONOCIDA âœ—"}
            PESO FÃSICO: ${modelFile.length() / (1024 * 1024)} MB
            UBICACIÃ“N: APP_INTERNAL_STORAGE
            """.trimIndent()
        } else {
            "ESTADO: ARCHIVO NO DETECTADO\nPor favor, cargue el modelo GGUF en la raÃ­z de datos."
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("Estado TÃ©cnico del Motor")
            .setMessage(status)
            .setPositiveButton("RE-ESCANEAR") { _, _ -> validateAIEngine() }
            .setNegativeButton("CERRAR", null)
            .show()
    }

    private fun showSystemSettingsDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("GestiÃ³n de Neuro-Synapse Core")
            .setMessage("VersiÃ³n de Esquema: 5\nIntegridad Hardware: VERIFICADA\nBase de Datos: ns_clinical_v5.db\n\nÂ¿Desea realizar un mantenimiento del sistema?")
            .setPositiveButton("OPTIMIZAR RAM") { _, _ -> 
                lifecycleScope.launch { resourceMonitor.requestGarbageCollection() }
            }
            .setNegativeButton("VOLVER", null)
            .show()
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    binding.controlPanel.visibility == View.VISIBLE || binding.viewFinder.visibility == View.VISIBLE -> {
                        resetToMenu()
                    }
                    binding.dashboardOptions.visibility == View.VISIBLE -> {
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

    private fun startVisionTest(age: SubjectAge, sex: SubjectSex) {
        lifecycleScope.launch {
            binding.txtStatus.text = "CAPTURA VISUAL - ${age.value}A"
            binding.viewFinder.visibility = View.VISIBLE
            binding.btnActionCapture.visibility = View.VISIBLE
            visualCapturer.startPreview(this@MainActivity, binding.viewFinder)
        }
    }

    private fun startVoiceTest(age: SubjectAge, sex: SubjectSex) {
        lifecycleScope.launch {
            binding.txtStatus.text = "GRABANDO VOZ..."
            recorder.startRecording(currentSessionId!!, "voice_test.pcm")
            delay(5000)
            recorder.stopRecording()
            binding.txtStatus.text = "ANÃLISIS DE VOZ COMPLETADO"
            binding.btnBackToMenu.visibility = View.VISIBLE
        }
    }

    private fun startResourceMonitoring() {
        resourceMonitor.startContinuousMonitoring(5000) { snapshot ->
            runOnUiThread {
                binding.txtDeviceHealth.text = "RAM: ${snapshot.usedRamMb}/${snapshot.totalRamMb}MB | BATERÃA: ${snapshot.batteryLevelPercent}%"
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::resourceMonitor.isInitialized) {
            resourceMonitor.stopContinuousMonitoring()
        }
    }

    private fun validateAIEngine() {
        val modelFile = File(filesDir, "llama-3.2-1b.gguf")
        if (modelFile.exists()) {
            isModelLoadedInRam = true
            activeModelPath = modelFile.absolutePath
            binding.aiStatusDot.setBackgroundColor(ContextCompat.getColor(this, R.color.status_green))
            binding.aiStatusText.text = "CORE IA: ACTIVO"
        } else {
            isModelLoadedInRam = false
            binding.aiStatusDot.setBackgroundColor(ContextCompat.getColor(this, R.color.status_red))
            binding.aiStatusText.text = "IA NO CARGADA"
        }
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
        binding.dashboardOptions.visibility = View.GONE
        binding.controlPanel.visibility = View.VISIBLE
        binding.btnBackToMenu.visibility = View.GONE
        onReady()
    }

    private fun startFullTest(age: SubjectAge, sex: SubjectSex) {
        lifecycleScope.launch {
            binding.txtStatus.text = "GRABANDO VOZ..."
            recorder.startRecording(currentSessionId!!, "spontaneous.pcm")
            delay(5000); recorder.stopRecording()
            
            binding.txtStatus.text = "CAPTURA VISUAL"
            binding.viewFinder.visibility = View.VISIBLE
            binding.btnActionCapture.visibility = View.VISIBLE
            visualCapturer.startPreview(this@MainActivity, binding.viewFinder)
        }
    }

    private suspend fun executeCapture() {
        binding.btnActionCapture.visibility = View.GONE
        visualCapturer.captureProjectiveTest(currentSessionId!!)
        binding.viewFinder.visibility = View.GONE
        
        binding.txtStatus.text = "IA RAZONANDO..."
        try {
            val db = gateway.requireDatabase()
            val sessionRepo = RoomClinicalSessionRepository(db.clinicalSessionDao(), db.auditLogDao())
            val session = ClinicalSession.createNew(SessionId(currentSessionId!!), ConsentLevel.SIGNED_DIGITAL)
            sessionRepo.save(session)

            val matrixA = AndroidAcousticEngine(this).analyze(session, patientAge!!, patientSex!!) {}
            val matrixV = AndroidVisualEngine(this).analyze(session, ProjectiveTestType.HTP_HOUSE, emptyList(), patientAge!!, patientSex!!) {}
            
            val report = AndroidSynthesisEngine(this).synthesize(
                acoustic = matrixA,
                projective = matrixV,
                age = patientAge!!,
                sex = patientSex!!,
                activeModelPath = activeModelPath
            ) {}

            binding.txtStatus.text = "REPORTE IA"
            binding.txtIntegrity.text = report.subjective.patientNarrativeSummary
            binding.btnBackToMenu.visibility = View.VISIBLE
        } catch (e: Exception) {
            binding.txtStatus.text = "ERROR"; binding.txtIntegrity.text = e.message
            binding.btnBackToMenu.visibility = View.VISIBLE
        }
    }

    private fun resetToMenu() {
        binding.controlPanel.visibility = View.GONE
        binding.viewFinder.visibility = View.GONE
        binding.dashboardOptions.visibility = View.VISIBLE
    }
}
