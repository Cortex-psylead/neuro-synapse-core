# 🧠 Neuro-Synapse Core
### The Open-Source Sovereign Edge-AI for Psychotherapy
---

**Neuro-Synapse Core** is a clinical assistant tool designed to empower mental health professionals through local, private, and high-performance Edge AI. By processing speech and visual projective tests (like HTP) directly on the therapist's device, it ensures absolute data sovereignty and clinical integrity.

> [!IMPORTANT]  
> **DISCLAIMER:** This is a clinical assistant tool, not a diagnostic device. All AI drafts must be verified by a licensed professional. Neuro-Synapse does not replace human clinical judgment.

---

## 🇪🇸 Resumen del Proyecto
**Neuro-Synapse Core** es un orquestador de IA perimetral diseñado para psicoterapeutas. Permite el análisis de prosodia vocal y tests proyectivos gráficos (como el HTP) de forma 100% local, garantizando la privacidad del paciente y la soberanía de los datos clínicos mediante cifrado por hardware.

---

## 🛠️ Tech Stack & Architecture

- **Language:** Kotlin 2.1 (Multiplatform Ready)
- **AI Engine:** Llama 3.2 (1B/3B) via MediaPipe LLM Inference.
- **Computer Vision:** OpenCV Native SDK for projective analysis.
- **Persistence:** Room v5 + SQLCipher (Hardware-backed encryption).
- **Architecture:** Clean Architecture + Domain-Driven Design (DDD).
- **UI:** Material 3 + Jetpack Compose (Modern Edge-to-Edge).

---

## 🏛️ Project Structure

- `:domain` - The core clinical logic, entities, and analysis ports. Immutable and pure Kotlin.
- `:data` - Implementation of engines (Acoustic, Visual, Synthesis) and Room persistence.
- `:app` - Android UI, Secure Camera/Audio capture, and biometric gateway.

---

## 🚀 How to Build

1. **Clone the repository:**
   ```bash
   git clone https://github.com/Cortex-psylead/neuro-synapse-core.git
   ```
2. **Setup AI Weights:**
   Download the `llama-3.2-1b-instruct.gguf` (or similar) model and place it in the application's internal storage directory (`/data/user/0/com.neurosynapse.app/files/`).
3. **Environment:**
   Requires Android Studio Ladybug (or newer) and Android SDK 35.
4. **Build:**
   ```bash
   ./gradlew :app:assembleDebug
   ```

---

## 🤝 Contributing

We welcome contributions to the Acoustic and Visual engines! Please read our [CONTRIBUTING.md](CONTRIBUTING.md) to understand our "Zero-Cloud" philosophy and architectural constraints.

---

## ⚖️ License

Distributed under the **GNU GPL v3.0**. See `LICENSE` for more information.
