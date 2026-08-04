# Contributing to Neuro-Synapse Core

Thank you for your interest in contributing to the first sovereign AI clinical core. To maintain clinical integrity and patient privacy, we follow strict architectural guidelines.

## 🛡️ The Zero-Cloud Manifesto
**No clinical data shall ever leave the device.** 
- All contributions involving AI, processing, or data handling must be implemented as **On-Device** solutions.
- Pull Requests (PRs) that introduce calls to external LLM APIs (OpenAI, Anthropic, etc.) or cloud analytics will be **rejected immediately**.

## 🏗️ Architectural Constraints
1. **Domain Immutability:** The `:domain` module represents the clinical truth. Changes here must be discussed via an Issue first and require approval from the Lead Architect.
2. **Deterministic Processing:** Analysis engines should aim for determinism to ensure that audit logs (Merkle Chains) remain consistent across builds.
3. **OpenCV/MediaPipe:** Prefer native performance for image and speech processing to minimize battery impact.

## 🛠️ Workflow
1. Fork the repo and create your branch from `main`.
2. Ensure your code follows the established Kotlin 2.1 style.
3. Verify your changes with `./gradlew check`.
4. Submit a PR with a clear description of the improvement (Acoustic deltas, Visual morphometrics, etc.).

We are specifically looking for experts in **Signal Processing (DSP)** and **Computer Vision** to help us refine the projective test detection algorithms.
