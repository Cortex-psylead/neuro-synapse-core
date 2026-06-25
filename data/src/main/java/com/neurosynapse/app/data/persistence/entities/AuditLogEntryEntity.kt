package com.neurosynapse.app.data.persistence.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.neurosynapse.domain.common.IntegrityHash
import com.neurosynapse.domain.common.SessionId
import com.neurosynapse.domain.common.UtcTimestamp
import com.neurosynapse.domain.session.AuditEventType
import com.neurosynapse.domain.session.AuditLogEntry
import com.neurosynapse.domain.session.SubsystemSnapshot
import com.neurosynapse.domain.session.SubsystemHealth

// ─────────────────────────────────────────────────────────────────────────────
// NEURO-SYNAPSE :data — persistence/entities/AuditLogEntryEntity.kt
//
// Entidad Room para el log de auditoría Merkle-chained del dominio.
//
// CAMPO CRÍTICO: previous_entry_hash
//   Permite reconstruir y verificar la cadena completa desde BD:
//     entry[0].previousEntryHash = GENESIS_HASH
//     entry[n].previousEntryHash = entry[n-1].entryHashSha256
//   Sin este campo, la verificación de integridad requeriría recomputar
//   todos los hashes desde el principio — inviable para logs largos.
//
// APPEND-ONLY (ISS-03):
//   La restricción de sólo inserción se implementa mediante triggers SQL
//   definidos en NeuroSynapseDatabase.Migration_1_2 (o en el callback
//   onCreate). Ver AuditLogDao.kt para la documentación completa del
//   mecanismo bypass_retention_lock para la purga regulatoria (ADR-008).
//
// FOREIGN KEY hacia clinical_sessions:
//   Garantiza integridad referencial: no puede existir una entrada de log
//   para una sesión que no existe en la tabla principal.
//   onDelete = CASCADE: si una sesión se borra (purga regulatoria),
//   sus entradas de log se borran en cascada DESPUÉS de que el trigger
//   bypass_retention_lock habilite la operación.
// ─────────────────────────────────────────────────────────────────────────────

@Entity(
    tableName = "audit_log_entries",
    foreignKeys = [
        ForeignKey(
            entity = ClinicalSessionEntity::class,
            parentColumns = ["session_id"],
            childColumns = ["session_id"],
            // La purga regulatoria borra la sesión → las entradas se eliminan
            // en cascada. Sólo ocurre cuando bypass_retention_lock = 1.
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.RESTRICT   // El session_id nunca cambia
        )
    ],
    indices = [
        // Índice único sobre entry_id — cada entrada es irrepetible
        Index(value = ["entry_id"], unique = true),
        // Índice compuesto para queries de verificación de cadena por sesión
        Index(value = ["session_id", "created_at_epoch_ms"])
    ]
)
data class AuditLogEntryEntity(

    /**
     * UUID v4 único por entrada. Generado por el dominio (AuditEventContent.entryId).
     * No es autoincremental — el dominio controla la identidad de las entradas.
     */
    @PrimaryKey
    @ColumnInfo(name = "entry_id")
    val entryId: String,

    /**
     * UUID v4 de la sesión clínica a la que pertenece esta entrada.
     * Foreign key hacia clinical_sessions.session_id.
     */
    @ColumnInfo(name = "session_id", index = true)
    val sessionId: String,

    /**
     * Timestamp UTC ISO-8601 del evento auditado.
     * Formato: "2026-06-22T10:00:00.000Z"
     */
    @ColumnInfo(name = "timestamp_utc")
    val timestampUtc: String,

    /**
     * Tipo de evento auditado. Almacenado como nombre del enum (String).
     * Protegido de R8 por consumer-rules.pro (ADR-003).
     */
    @ColumnInfo(name = "event_type")
    val eventType: String,

    /**
     * Versión del SLM activo en el momento del evento.
     * Null para eventos no relacionados con el motor de síntesis.
     * Ej: "onnx-qwen25-1.5b-q4km-v1" para REPORT_GENERATED.
     */
    @ColumnInfo(name = "slm_version")
    val slmVersion: String?,

    /**
     * Estado canónico del subsistema de audio en el momento del evento.
     * Almacenado como canonicalCode (ADR-003): "HEALTHY", "DEGRADED", "ISOLATED".
     * Nunca ::class.simpleName — vulnerable a R8 obfuscation.
     */
    @ColumnInfo(name = "audio_health_code")
    val audioHealthCode: String,

    /**
     * Estado canónico del subsistema de visión.
     */
    @ColumnInfo(name = "vision_health_code")
    val visionHealthCode: String,

    /**
     * Estado canónico del subsistema NLP/SLM.
     */
    @ColumnInfo(name = "nlp_health_code")
    val nlpHealthCode: String,

    /**
     * Hash SHA-256 del reporte clínico sellado.
     * Null para todos los eventos excepto REPORT_GENERATED.
     * Permite vincular la entrada de auditoría con el artefacto exacto.
     */
    @ColumnInfo(name = "report_hash")
    val reportHash: String?,

    /**
     * CAMPO CRÍTICO — Hash de la entrada ANTERIOR en la cadena Merkle.
     *
     * Para la primera entrada de cada sesión:
     *   previousEntryHash = AuditLogEntry.GENESIS_HASH
     *
     * Para las entradas subsiguientes:
     *   previousEntryHash = entry[n-1].entryHashSha256
     *
     * Permite verificar la cadena completa en O(n) sin recomputar hashes:
     *   chain_valid = entry[n].previousEntryHash == entry[n-1].entryHashSha256
     *
     * Sin este campo, detectar manipulación requeriría recomputar toda la cadena.
     */
    @ColumnInfo(name = "previous_entry_hash")
    val previousEntryHash: String,

    /**
     * Hash SHA-256 de esta entrada, encadenado con el hash anterior.
     *
     * Calculado por ClinicalSession.appendAuditChained() en el dominio:
     *   entryHashSha256 = SHA-256(canonicalPayload || previousEntryHash)
     *
     * Formato: 64 caracteres hexadecimales en minúsculas.
     */
    @ColumnInfo(name = "entry_hash_sha256")
    val entryHashSha256: String,

    /**
     * Epoch Unix en milisegundos para ordenación eficiente sin parsear ISO-8601.
     * Derivado de timestampUtc en el mapper — no viene del dominio directamente.
     */
    @ColumnInfo(name = "created_at_epoch_ms")
    val createdAtEpochMs: Long
)

// ── Mappers ───────────────────────────────────────────────────────────────────

/**
 * Convierte AuditLogEntry del dominio a AuditLogEntryEntity para persistencia.
 *
 * @param epochMs Timestamp en milisegundos para la columna de ordenación.
 *                El repositorio lo calcula desde entry.timestampUtc.
 */
fun AuditLogEntry.toEntity(epochMs: Long): AuditLogEntryEntity =
    AuditLogEntryEntity(
        entryId = this.entryId,
        sessionId = this.sessionId.value,
        timestampUtc = this.timestampUtc.iso8601,
        eventType = this.eventType.name,            // ADR-003: .name protegido
        slmVersion = this.slmVersion,
        // ADR-003: canonicalCode explícito — nunca ::class.simpleName
        audioHealthCode = this.subsystemStates.audio.canonicalCode,
        visionHealthCode = this.subsystemStates.vision.canonicalCode,
        nlpHealthCode = this.subsystemStates.nlp.canonicalCode,
        reportHash = this.reportHash?.hex,
        previousEntryHash = this.previousEntryHash.hex,
        entryHashSha256 = this.entryHashSha256.hex,
        createdAtEpochMs = epochMs
    )

/**
 * Convierte AuditLogEntryEntity a AuditLogEntry del dominio.
 * Usado por el repositorio para reconstruir el log desde la BD.
 */
fun AuditLogEntryEntity.toDomain(): AuditLogEntry =
    AuditLogEntry(
        entryId = this.entryId,
        sessionId = SessionId(this.sessionId),
        timestampUtc = UtcTimestamp(this.timestampUtc),
        eventType = AuditEventType.valueOf(this.eventType),  // ADR-003: protegido
        slmVersion = this.slmVersion,
        subsystemStates = SubsystemSnapshot(
            audio = healthFromCode(this.audioHealthCode),
            vision = healthFromCode(this.visionHealthCode),
            nlp = healthFromCode(this.nlpHealthCode)
        ),
        reportHash = this.reportHash?.let { IntegrityHash(it) },
        previousEntryHash = IntegrityHash(this.previousEntryHash),
        entryHashSha256 = IntegrityHash(this.entryHashSha256)
    )

/**
 * Reconstruye SubsystemHealth desde su canonicalCode.
 * Los códigos "DEGRADED" e "ISOLATED" pierden el campo reason al ser
 * persistidos (sólo se almacena el código). Esto es intencional:
 * el reason es información de diagnóstico en tiempo real, no histórica.
 * Para análisis forense, el eventType y el timestamp son suficientes.
 */
private fun healthFromCode(code: String): SubsystemHealth = when (code) {
    "HEALTHY"  -> SubsystemHealth.Healthy
    "DEGRADED" -> SubsystemHealth.Degraded("(restaurado desde BD)")
    "ISOLATED" -> SubsystemHealth.Isolated("(restaurado desde BD)", 0L)
    else       -> throw IllegalArgumentException(
        "canonicalCode desconocido en audit_log_entries: '$code'. " +
        "Posible corrupción o schema de versión diferente."
    )
}
