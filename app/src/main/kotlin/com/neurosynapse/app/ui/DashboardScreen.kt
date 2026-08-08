package com.neurosynapse.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.automirrored.rounded.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// --- Configuración de Colores WCAG AAA ---
val DarkBackground = Color(0xFF090D16)
val CardBackground = Color(0xFF131C2E)
val CardBorder = Color(0xFF223049)
val AccentGreen = Color(0xFF10B981)
val SubtleAccent = Color(0xFF38BDF8)
val TextPrimary = Color(0xFFF8FAFC)
val TextSecondary = Color(0xFF94A3B8)
val IconContainerBg = Color(0xFF1E293B)

@Composable
fun NeuroSynapseTheme(content: @Composable () -> Unit) {
    val darkColorScheme = darkColorScheme(
        primary = SubtleAccent,
        background = DarkBackground,
        surface = CardBackground,
        onBackground = Color.White,
        onSurface = Color.White
    )

    MaterialTheme(
        colorScheme = darkColorScheme,
        typography = Typography(),
        content = content
    )
}

// --- Componente Reutilizable ---
@Composable
fun ClinicalModuleCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    accentColor: Color,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .clickable { onClick() }
            .border(1.dp, CardBorder, RoundedCornerShape(16.dp)),
        colors = CardDefaults.cardColors(containerColor = CardBackground),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Contenedor del Icono optimizado
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(IconContainerBg),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(28.dp)
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            // Textos con contraste WCAG AAA
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Text(
                    text = subtitle,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = TextSecondary
                )
            }

            // Flecha final
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.3f)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    isModelReady: Boolean,
    onModuleClick: (String) -> Unit,
    onSettingsClick: () -> Unit
) {
    NeuroSynapseTheme {
        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    title = {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                "Neuro-Synapse Core",
                                fontWeight = FontWeight.Bold,
                                fontSize = 20.sp,
                                color = TextPrimary
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(top = 4.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .background(
                                            if (isModelReady) AccentGreen else Color.Gray, 
                                            CircleShape
                                        )
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    if (isModelReady) "Modelo Local Activo" else "Sin Modelo Instalado",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Medium,
                                    color = if (isModelReady) AccentGreen else TextSecondary
                                )
                            }
                        }
                    },
                    modifier = Modifier.statusBarsPadding(),
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                        containerColor = DarkBackground
                    )
                )
            },
            containerColor = DarkBackground
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 24.dp)
            ) {
                Spacer(modifier = Modifier.height(24.dp))
                
                Text(
                    text = "Módulos de Diagnóstico",
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White.copy(alpha = 0.5f),
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                // Lista de Módulos
                val modules = listOf(
                    ModuleData(Icons.Default.Draw, "Captura HTP", "Análisis gráfico proyectivo", Color(0xFFF43F5E)),
                    ModuleData(Icons.Default.RecordVoiceOver, "Test de Prosodia", "Análisis de entonación clínica", Color(0xFF8B5CF6)),
                    ModuleData(Icons.AutoMirrored.Filled.Assignment, "Evaluación Integral", "Reporte multidimensional", Color(0xFF0EA5E9))
                )

                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(modules) { module ->
                        ClinicalModuleCard(
                            icon = module.icon,
                            title = module.title,
                            subtitle = module.subtitle,
                            accentColor = module.accentColor,
                            onClick = { onModuleClick(module.title) }
                        )
                    }
                }

                // Sección Inferior (IA & Seguridad)
                Text(
                    text = "IA & Seguridad",
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White.copy(alpha = 0.5f),
                    modifier = Modifier.padding(top = 24.dp, bottom = 12.dp)
                )

                OutlinedCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 32.dp)
                        .clickable { onSettingsClick() },
                    colors = CardDefaults.outlinedCardColors(containerColor = Color.White.copy(alpha = 0.03f)),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.1f)),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .padding(20.dp)
                            .fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .background(Color.White.copy(alpha = 0.05f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Memory,
                                contentDescription = null,
                                tint = Color.White.copy(alpha = 0.8f),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        
                        Spacer(modifier = Modifier.width(16.dp))
                        
                        Text(
                            "Configurar Modelo Local",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White.copy(alpha = 0.9f)
                        )
                        
                        Spacer(modifier = Modifier.weight(1f))
                        
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.4f),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}

private data class ModuleData(
    val icon: ImageVector,
    val title: String,
    val subtitle: String,
    val accentColor: Color
)
