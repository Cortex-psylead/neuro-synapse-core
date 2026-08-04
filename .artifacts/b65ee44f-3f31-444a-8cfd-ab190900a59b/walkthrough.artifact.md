# Rediseño de UI/UX: Neuro-Synapse Core

Se ha implementado una interfaz de usuario moderna y profesional para la pantalla principal, siguiendo los estándares de aplicaciones médicas de alta gama.

## Características de Diseño

### 1. Estética Clínica Oscura
- **Paleta de Colores**: Se utiliza un fondo neutro profundo (`#0F172A`) para reducir la fatiga visual en entornos clínicos y mejorar el contraste.
- **Material Design 3**: Implementación completa de componentes M3 con bordes redondeados y elevaciones sutiles.

### 2. ClinicalModuleCard
Componente reutilizable diseñado para maximizar la legibilidad y facilitar la navegación táctil:
- **Iconografía**: Uso de contenedores con colores de acento tenues (10% de opacidad) para diferenciar visualmente los módulos sin saturar la pantalla.
- **Jerarquía Visual**: Títulos en negrita con subtítulos descriptivos que guían al profesional de la salud.
- **Feedback Visual**: Indicadores de navegación (`KeyboardArrowRight`) para sugerir interactividad.

### 3. Header Informativo
- **Estado del Sistema**: Un indicador tipo "dot" verde (`Modelo Local Activo`) proporciona seguridad inmediata sobre la privacidad de los datos (Edge AI).

### 4. IA & Seguridad
- Sección diferenciada mediante un `OutlinedCard` para configuraciones técnicas, manteniendo las acciones principales despejadas.

## Cambios Realizados

- **[NUEVO] [DashboardScreen.kt](file:///C:/Users/need9/StudioProjects/neuro-synapse-core/app/src/main/kotlin/com/neurosynapse/app/ui/DashboardScreen.kt)**: Implementación de la UI en Compose.
- **[MODIFICADO] [MainActivity.kt](file:///C:/Users/need9/StudioProjects/neuro-synapse-core/app/src/main/kotlin/com/neurosynapse/app/MainActivity.kt)**: Migración parcial a Compose, integrando el Dashboard con la lógica existente.
- **[MODIFICADO] [build.gradle.kts](file:///C:/Users/need9/StudioProjects/neuro-synapse-core/app/build.gradle.kts)**: Configuración de dependencias de Jetpack Compose y Material 3.

## Captura de Pantalla (Preview Sugerido)
El diseño presenta una lista vertical de tarjetas elegantes que contrastan suavemente con el fondo oscuro, transmitiendo precisión y seguridad.
