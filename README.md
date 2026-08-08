# 🧠 Neuro-Synapse Core
### El Asistente de Documentación Clínica con IA 100% Local
---

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](https://www.gnu.org/licenses/gpl-3.0)
[![Version](https://img.shields.io/badge/Version-0.2.0--beta-green.svg)]()
[![Status](https://img.shields.io/badge/Status-Beta%20Ready-blue.svg)]()

> [!IMPORTANT]  
> **DISCLAIMER:** Esta es una herramienta de asistencia clínica, no un dispositivo de diagnóstico. Todos los borradores generados por la IA deben ser verificados y firmados por un profesional licenciado. Neuro-Synapse no reemplaza el juicio clínico humano.

---

## 😟 El Problema
**"Los terapeutas pasan hasta el 30% de su tiempo redactando notas, sacrificando la calidad de la atención al paciente."**

La documentación clínica es tediosa, propensa a errores y consume horas valiosas que podrían dedicarse a la terapia. Además, las soluciones actuales de IA basadas en la nube representan un riesgo crítico para la privacidad y la confidencialidad del paciente.

## ✨ La Solución
**Neuro-Synapse Core** es una terminal segura de "IA Perimetral" (Edge AI) diseñada para transformar la práctica psicoterapéutica. 

Nuestra herramienta captura y procesa la información de la sesión de forma privada para generar **borradores de notas SOAP** automáticamente, permitiendo que el terapeuta se enfoque en lo que realmente importa: el ser humano frente a él.

---

## 🛡️ Los 3 Pilares de Soberanía Clínica

### 1. Zero-Cloud Data (Privacidad Absoluta)
A diferencia de otras IAs, Neuro-Synapse **jamás envía datos clínicos fuera de tu dispositivo**. Todo el procesamiento (transcripción y razonamiento) ocurre directamente en el chip de tu teléfono (NPU/CPU), garantizando que la intimidad de la sesión permanezca bajo tu control total.

### 2. Military-Grade Security
Implementamos una arquitectura de búnker de datos:
- **SQLCipher:** Persistencia cifrada de extremo a extremo.
- **Biometría TEE:** Las llaves de acceso están vinculadas al hardware del dispositivo y protegidas por tu huella dactilar o rostro en el entorno de ejecución confiable (Trusted Execution Environment).

### 3. Transparencia FOSS (Auditabilidad)
Como software libre (Open Source), nuestro código está abierto a auditorías de seguridad y ética clínica por parte de la comunidad científica y tecnológica global. Sin cajas negras.

---

## 🚀 Estado del Proyecto (Beta v0.2.0)

Actualmente, el búnker de datos y los motores de captura son operativos. Hemos alcanzado la fase de razonamiento local estable:

- **[Operativo]** Búnker de Datos Cifrado (Room v5 + SQLCipher).
- **[Operativo]** Dashboard Clínico Premium con Telemetría Dinámica.
- **[Operativo]** Inferencia Local de Llama 3.2 (GGUF) vía llama.cpp.
- **[Operativo]** Análisis Proyectivo HTP Estabilizado (OpenCV Burst Capture).
- **[En Desarrollo]** Módulo de Prosodia Vocal v2.
- **[En Desarrollo]** Exportación de Reportes a PDF Cifrado.

---

## 🛠️ Stack Tecnológico

- **Language:** Kotlin 2.1 (Multiplatform)
- **AI Core:** Llama 3.2 via llama.cpp (GGUF Support).
- **Vision:** OpenCV Native SDK.
- **Security:** SQLCipher + BiometricPrompt API.
- **UI:** Material 3 (Deep Clinical Aesthetic).

---

## 🤝 Colaboración
Buscamos expertos en **Salud Mental**, **Edge AI** y **Ciberseguridad**. Consulta nuestro [CONTRIBUTING.md](CONTRIBUTING.md) para unirte a la revolución de la IA soberana.

---

## ⚖️ Licencia
Distribuido bajo la licencia **GNU GPL v3.0**. Consulta el archivo `LICENSE` para más detalles.
