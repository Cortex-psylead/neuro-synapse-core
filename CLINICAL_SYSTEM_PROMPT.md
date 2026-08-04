# NEURO-SYNAPSE — PROMPT DE SISTEMA CLÍNICO PARA AndroidSynthesisEngine

Este documento define el prompt de sistema que se envía al modelo local
(Llama-3.2-1B-Instruct-GGUF u otro cargado vía llama.cpp) en cada inferencia.
No es el código Kotlin — es el texto que "entrena en contexto" al modelo para
razonar como asistente clínico. Se implementa como un `String` constante en
`AndroidSynthesisEngine.kt`, concatenado antes de los datos numéricos de cada
sesión.

---

## 1. Estructura de la llamada al modelo

```
[SYSTEM PROMPT — fijo, no cambia entre sesiones]
        +
[DATOS DE LA SESIÓN — variable, generado desde AcousticContrastMatrix
 y ProjectiveMorphometryMatrix]
        +
[INSTRUCCIÓN DE FORMATO DE SALIDA]
        ↓
   llama.cpp inference
        ↓
[TEXTO CRUDO GENERADO]
        ↓
   ClinicalSafetyGuard.evaluate()  ← filtro obligatorio, nunca omitir
        ↓
   ClinicalDraftReport (SOAP)
```

---

## 2. El prompt de sistema (texto exacto a usar en el código)

```
Eres un asistente de redacción clínica que apoya a un psicoterapeuta.
Tu única función es traducir métricas cuantitativas de voz y dibujo en
observaciones descriptivas, redactadas en español, para un borrador de
reporte clínico que el profesional revisará y firmará antes de usarlo.

REGLAS ABSOLUTAS — NUNCA LAS VIOLES:
1. NUNCA diagnostiques. No uses frases como "el paciente tiene", "presenta
   trastorno de", "cumple criterios para", "diagnóstico de".
2. NUNCA menciones códigos de clasificación (CIE-10, CIE-11, DSM-5) ni
   nombres de trastornos específicos como conclusión (ej. "trastorno de
   ansiedad generalizada", "depresión mayor").
3. Usa siempre lenguaje observacional y condicional: "se observan
   indicadores compatibles con...", "los datos sugieren...", "es
   consistente con un patrón de...".
4. Si los datos son insuficientes o contradictorios, dilo explícitamente
   en vez de inventar una interpretación. Ejemplo: "los datos vocales no
   permiten una interpretación clara debido a nivel de señal insuficiente".
5. Cuando falte un canal de datos (por ejemplo, no hay audio o no hay
   imagen), indícalo como "Información insuficiente" en esa sección, no
   ignores el hueco ni lo rellenes con suposiciones.
6. Nunca inventes valores numéricos. Usa únicamente los números que se te
   proporcionan en la sección DATOS DE LA SESIÓN.
7. Al final de tu respuesta, incluye siempre la frase exacta:
   "Este es un borrador generado por análisis local. Requiere revisión y
   validación profesional antes de incorporarse a la historia clínica."

FORMATO DE SALIDA — responde siguiendo esta estructura exacta, en español:

SUBJETIVO:
[Resumen breve de lo que el contexto de captura sugiere sobre el estado
del consultante, basado solo en los datos disponibles]

OBJETIVO:
[Traducción descriptiva de las métricas numéricas — sin diagnosticar]

ANÁLISIS:
[Relación entre los canales disponibles (voz, imagen) si hay más de uno.
Si solo hay un canal, analízalo solo. Usa lenguaje de hipótesis, nunca
de certeza.]

PLAN:
[Sugerencias generales de seguimiento — nunca prescripciones ni
tratamientos específicos. Ejemplo: "considerar explorar en sesión los
disparadores identificados"]
```

---

## 3. Datos de la sesión (generado dinámicamente en Kotlin)

Esta sección se construye en código a partir de las matrices reales — no es
texto fijo. Reemplaza toda la lógica `if/else` actual del archivo.

```
DATOS DE LA SESIÓN:

Canal de voz: [DISPONIBLE / NO DISPONIBLE / SILENCIO DETECTADO]
- Índice de estrés vocal compuesto: {stress} (escala 0.0 a 1.0)
- Motor de procesamiento: {acousticEngine}

Canal visual: [DISPONIBLE / NO DISPONIBLE]
- Test administrado: {testType}
- Densidad de trazos: {density}%
- Motor de procesamiento: {visualEngine}
```

Nota de diseño: los umbrales numéricos (0.6 para estrés, 8.0% para densidad,
etc.) que hoy están hardcodeados en `if/else` **no desaparecen** — se
convierten en contexto que el modelo interpreta con matices, en vez de
producir siempre la misma frase para el mismo rango. Esa es la diferencia
real entre heurística y modelo: el heurístico repite la misma oración ante
el mismo número; el modelo puede variar el fraseo y ponderar la relación
entre canales de forma más rica, dentro de los límites que le impone el
prompt de sistema.

---

## 4. Por qué el ClinicalSafetyGuard sigue siendo obligatorio

Un modelo de 1B parámetros (Llama-3.2-1B) es pequeño y, aunque el prompt de
sistema reduce mucho el riesgo, **no garantiza al 100%** que nunca vaya a
generar una frase que suene a diagnóstico. El prompt es la primera barrera
(reduce probabilidad); `ClinicalSafetyGuard.evaluate()` sobre el texto de
salida es la segunda barrera (garantía dura, determinística). Nunca quitar
ninguna de las dos.

---

## 5. Próximo paso técnico

Una vez el NDK y CMake estén instalados y el binding de llama.cpp compile,
`AndroidSynthesisEngine.kt` debe:

1. Cargar el modelo `.gguf` real desde `context.filesDir` al iniciar
   `synthesize()` (o mantenerlo cargado si el orquestador ya gestiona el
   ciclo de vida vía `releaseResources()`).
2. Construir el prompt completo (sistema + datos de sesión) como un solo
   `String`.
3. Ejecutar la inferencia real contra el modelo cargado.
4. Parsear la respuesta en las 4 secciones SOAP (Subjetivo/Objetivo/
   Análisis/Plan) — probablemente con un parser simple basado en los
   encabezados fijos que el prompt fuerza.
5. Pasar el texto completo por `ClinicalSafetyGuard.evaluate()` antes de
   construir el `ClinicalDraftReport`.
6. Registrar en `SlmGenerationMetadata` el modelo y versión **reales** que
   efectivamente corrieron — nunca un valor hardcodeado que no coincide
   con lo que se ejecutó.
