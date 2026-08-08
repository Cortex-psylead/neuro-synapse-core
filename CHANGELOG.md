# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.2.0-beta] - 2026-08-08

### Added
- **Manual Model Rescan:** Added "RE-ESCANEAR NÚCLEO" in Advanced Settings for real-time model detection.
- **Burst Capture System:** Implemented 5-frame burst capture with consensus algorithm for drawing density stabilization.
- **Advanced Vision Pipeline:** Integrated Bilateral Filter and Adaptive Thresholding in OpenCV to eliminate environmental light noise.
- **Dynamic Telemetry:** Added visual progress bars for RAM and Battery with real-time health alerts.
- **Clinical Report Formatter:** Built-in JSON parser and formatter for structured SOAP drafts (Subjective, Objective, Analysis, Plan).
- **Pulse Indicator:** Animated status pill for Core AI background monitoring.

### Changed
- **AI Engine Migration:** Migrated from MediaPipe LLM Inference to **llama.cpp** (`llama-kotlin-android`) for superior GGUF support and RAM management.
- **Memory Sovereignty:** Implemented sequential resource pipeline ("Zero-Camera-Footprint") to free GPU/Camera memory before inference.
- **UI Overhaul:** Applied "Deep Clinical" premium palette (#0F172A) and cybernetic interface standards.
- **Download Stability:** Reconfigured Secure Download system with User-Agent headers and direct mirrors to bypass 401/403 errors.

### Fixed
- **Spanish Encoding:** Enforced UTF-8 across the entire project, fixing corrupted characters (tildes/eñes).
- **JSON Logic:** Fixed contradiction in `omisiones_detectadas` where described parts were also listed as omitted.
- **UI Overflow:** Added NestedScrollView to clinical reports and download screens.

## [0.1.0-alpha] - 2026-08-04

### Added
- Initial project structure following Clean Architecture.
- Local AI Orchestration using Llama 3.2 (via MediaPipe).
- Secure persistence with Room 5 and SQLCipher encryption.
- Visual Analysis port for OpenCV-based projective tests.
- Modern Material 3 Dashboard with Edge-to-Edge support.
- Patient metadata dialog (Age/Sex) for clinical context.
- Hardware telemetry (RAM/Battery) in the dashboard.
- GitHub documentation (README, CONTRIBUTING, SECURITY).
