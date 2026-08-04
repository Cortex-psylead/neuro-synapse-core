# Security Policy

## 🔒 Zero-Cloud-Data Commitment
Neuro-Synapse Core is designed for clinicians who handle extremely sensitive patient data. Our security model is based on **Local Sovereignty**: data is encrypted with a key derived from the user's biometry via SQLCipher.

## 🛡️ Reporting Vulnerabilities
If you discover a security vulnerability related to our encryption implementation, key management, or data persistence, please **do not open a public issue**. 

Instead, send a detailed report to `security@neurosynapse.com`. 

We are particularly interested in:
- SQLCipher implementation bypasses.
- Biometric key derivation flaws.
- Memory leaks in the AI inference pipeline that could expose raw patient data.

## 📜 Principles
- **Privacy by Design:** We do not collect telemetry or usage data.
- **Hardware-Backed Security:** We leverage Android Keystore and BiometricPrompt for all critical operations.
