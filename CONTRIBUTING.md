# Contributing to Neuro-Synapse Core

**EN:** Thank you for your interest! This is a clinical tool. Code quality is not just technical: it is ethical.  
**ES:** ¡Gracias por tu interés! Este proyecto es una herramienta clínica. La calidad del código no es solo técnica: es ética.

---

## 🧭 Philosophy / Filosofía

- **Zero-Cloud:** No patient data leaves the device. Never. / Ningún dato del paciente sale del dispositivo. Nunca.
- **Clinical-First:** Every technical decision is evaluated from clinical ethics. / Cada decisión técnica se evalúa desde la ética clínica.
- **Human-AI Collaboration:** AI assists the therapist. Therapist has final control. / La IA asiste al terapeuta. El terapeuta tiene control final.
- **Open & Auditable:** Code must be readable and verifiable by mental health professionals. / El código debe ser legible y verificable por profesionales de salud mental.

---

## 🚀 How to Contribute / Cómo Contribuir

### 1. Find an Issue / Encuentra un Issue

**EN:** Review [open issues](https://github.com/Cortex-psylead/neuro-synapse-core/issues). Look for:  
**ES:** Revisa [issues abiertos](https://github.com/Cortex-psylead/neuro-synapse-core/issues). Busca:

- `good first issue` — New contributors / Nuevos contribuyentes
- `help wanted` — Urgent help / Ayuda urgente
- `clinical` — Mental health perspective / Perspectiva de salud mental
- `security` — Privacy or encryption / Privacidad o cifrado

### 2. Read the RFC / Lee el RFC

**EN:** Each module has an RFC in [Cortex-psylead/rfcs](https://github.com/Cortex-psylead/Cortex-psylead/tree/main/rfcs). Understand the **clinical why** before the **technical how**.

**ES:** Cada módulo tiene un RFC en [Cortex-psylead/rfcs](https://github.com/Cortex-psylead/Cortex-psylead/tree/main/rfcs). Entiende el **por qué clínico** antes del **cómo técnico**.

### 3. Sign the CLA / Firma el CLA

**EN:** Before merge, sign the [CLA](https://cla-assistant.io/Cortex-psylead/neuro-synapse-core). One-click digital process.  
**ES:** Antes del merge, firma el [CLA](https://cla-assistant.io/Cortex-psylead/neuro-synapse-core). Proceso digital de un clic.

### 4. Fork and Work / Haz Fork y Trabaja

```bash
git clone https://github.com/YOUR-USERNAME/neuro-synapse-core.git
cd neuro-synapse-core
git checkout -b feature/module-name
```

### 5. Code Standards / Estándares

- **Language / Lenguaje:** Kotlin (preferred), Java if needed / Kotlin (preferido), Java si es necesario
- **Architecture / Arquitectura:** Clean Architecture + DDD
- **Tests:** Unit tests required. Security modules need integration tests. / Tests unitarios requeridos. Módulos de seguridad necesitan tests de integración.
- **Documentation / Documentación:** Comment the **why**, not the **what**. / Comenta el **por qué**, no el **qué**.

### 6. Pull Request Template / Template de PR

```markdown
## What changes? / ¿Qué cambia?
[Brief technical / Breve técnico]

## Why clinically necessary? / ¿Por qué clínicamente necesario?
[Benefit for therapist or patient / Beneficio para terapeuta o paciente]

## Complies with RFC? / ¿Cumple con el RFC?
[Link / Enlace]

## Tests / Tests
- [ ] Unit / Unitarios
- [ ] Integration / Integración
- [ ] Security / Seguridad

## Checklist
- [ ] Read CONTRIBUTING.md / Leí CONTRIBUTING.md
- [ ] Signed CLA / Firmé el CLA
- [ ] Code is 100% human / Código 100% humano
- [ ] No data transmission / No hay transmisión de datos
```

---

## ⚠️ Golden Rules / Reglas de Oro

1. **Never send real patient data in tests.** / **Nunca envíes datos de pacientes reales en tests.**
2. **Never introduce internet dependencies** without discussion. / **Nunca introduzcas dependencias de internet** sin discutirlo.
3. **Never implement "automatic diagnosis."** AI suggests; therapist decides. / **Nunca implementes "diagnóstico automático."** La IA sugiere, el terapeuta decide.
4. **If you have ethical doubts, ask.** / **Si tienes dudas éticas, pregunta.**

---

## 💬 Communication / Comunicación

- **Technical / Técnico:** GitHub Issues
- **Clinical/ethical / Clínico/ético:** [GitHub Discussions](https://github.com/Cortex-psylead/Cortex-psylead/discussions)
- **Urgent / Urgente:** Issue tagged `question` / Issue etiquetado `question`

---

## 🏅 Recognition / Reconocimiento

**EN:** All contributors recognized in `CONTRIBUTORS.md` and release notes.  
**ES:** Todos los contribuyentes reconocidos en `CONTRIBUTORS.md` y release notes.

---

*"The code we write protects the vulnerability of real people. Let us write it with that responsibility."*  
*"El código que escribimos protege la vulnerabilidad de personas reales. Escribámoslo con esa responsabilidad."*
