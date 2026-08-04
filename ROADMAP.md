# 🗺️ Neuro-Synapse Core Roadmap

This document outlines the planned trajectory for the project. As we are in the **alpha phase**, priorities may shift based on clinical feedback and technical breakthroughs.

## 🟢 Phase 1: Foundation (Current)
- [x] Basic Clean Architecture (:domain, :data, :app).
- [x] Room Persistence with SQLCipher encryption.
- [x] Llama 3.2 Integration via MediaPipe.
- [x] Modern Dashboard UI (Material 3).

## 🟡 Phase 2: Refinement (Next Steps)
- [ ] **Acoustic Engine v2:** Implement real-time stress detection during recording.
- [ ] **Visual Engine v2:** Improve HTP element detection (windows, doors, roofs) using custom OpenCV filters.
- [ ] **Multi-Model Support:** Allow therapists to choose between Llama 3.2 1B and 3B based on device RAM.
- [ ] **Unit Testing:** Implement >80% coverage for the `:domain` module.

## 🔴 Phase 3: Advanced Clinical Features
- [ ] **Forensic Auditing:** Merkle-tree validation for every session state change.
- [ ] **Export to PDF:** Generate professional clinical reports directly from the app.
- [ ] **Biometric Continuity:** Ensure the session stays unlocked only while the therapist's biometry is active (where hardware allows).

---

## 💡 Contributing to the Roadmap
If you have a clinical or technical suggestion, please open a [Feature Request](.github/ISSUE_TEMPLATE/feature_request.md).
