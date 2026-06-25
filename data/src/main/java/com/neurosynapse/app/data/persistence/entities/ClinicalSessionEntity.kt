package com.neurosynapse.app.data.persistence.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.neurosynapse.domain.common.ConsentLevel
import com.neurosynapse.domain.common.PipelinePhase
import com.neurosynapse.domain.common.SessionId
import com.neurosynapse.domain.common.UtcTimestamp
import com.neurosynapse.domain.common.IntegrityHash
import com.neurosynapse.domain.session.ClinicalSession

// ─────────────────────────────────────────────────────────────────────────────
// NEURO-SYNAPSE :data — persistence/entities/ClinicalSessionEntity.kt
//
// RESOLUCIÓN ISS-05: ClinicalSessionEntity alineada estrictamente con el
// contrato definido en ADR-005. Campos:
//   session_id, current_phase, consent_level, opened_at_utc,
//   last_updated_at_utc, is_frozen, root_hash
//
// PRINCIPIO DE SEPARACIÓN:
//   Esta entity pertenece EXCLUSIVAMENTE a la capa :data.
//   El dominio (:domain) nunca conoce Room, ni esta clase.
//   El mapeo domain ↔ entity es responsabilidad de los Mappers en :data.
//
// ENUM COMO STRING (ADR-003):
//   PipelinePhase y ConsentLevel se almacenan como String (nombre del enum).
//   Los nombres están protegidos de R8 por consumer-rules.pro.
//   Nunca usar ordinal — es frágil ante reordenamiento de valores.
// ─────────────────────────────────────────────────────────────────────────────

@Entity(
    tableName = "clinical_sessions",
    indices = [
        // Índice único sobre session_id garantizado a nivel de BD
        // (además del PrimaryKey — defensa en profundidad)
        Index(value = ["session_id"], unique = true)
    ]
)
data class ClinicalSessionEntity(

    /**
     * UUID v4 sintético — nunca contiene PII del paciente.
     * Formato: "550e8400-e29b-4d88-a456-426614174000"
     * Validado por SessionId value class en el dominio antes de persistir.
     */
    @PrimaryKey
    @ColumnInfo(name = "session_id")
    val sessionId: String,

    /**
     * Fase actual de la FSM clínica.
     * Almacenada como nombre del enum (String) — protegido por consumer-rules.pro.
     * Valores posibles: IDLE, CAPTURE, ACOUSTIC_ANALYSIS, VISUAL_ANALYSIS,
     *                   CLINICAL_SYNTHESIS, COMPLETED, ABORTED
     */
    @ColumnInfo(name = "current_phase")
    val currentPhase: String,

    /**
     * Nivel de consentimiento informado registrado en la sesión.
     * Almacenado como nombre del enum (String).
     * Valores posibles: NONE, CLINICAL_USE_ONLY, CLINICAL_AND_ANONYMIZED_DONATION
     */
    @ColumnInfo(name = "consent_level")
    val consentLevel: String,

    /**
     * Timestamp UTC ISO-8601 de apertura de sesión.
     * Formato: "2026-06-22T10:00:00.000Z"
     * Inmutable después de creación — nunca se actualiza.
     */
    @ColumnInfo(name = "opened_at_utc")
    val openedAtUtc: String,

    /**
     * Timestamp UTC ISO-8601 de la última actualización de estado.
     * Se actualiza en cada transición de fase y en actualizaciones de telemetría.
     */
    @ColumnInfo(name = "last_updated_at_utc")
    val lastUpdatedAtUtc: String,

    /**
     * Indica si la sesión está congelada (COMPLETED o ABORTED).
     * Una sesión congelada no puede recibir nuevas transiciones de fase.
     * Redundante con current_phase pero permite queries eficientes:
     *   SELECT * FROM clinical_sessions WHERE is_frozen = 0
     * para listar sesiones activas sin parsear el enum.
     */
    @ColumnInfo(name = "is_frozen")
    val isFrozen: Boolean,

    /**
     * Hash SHA-256 raíz que sella el estado completo de la sesión.
     * Calculado sobre: sessionId || currentPhase || consentLevel ||
     *                  openedAtUtc || lastUpdatedAtUtc || lastAuditEntryHash
     *
     * Permite detectar manipulación directa de la BD (e.g., edición con
     * SQLite Browser) sin pasar por los controles de la aplicación.
     *
     * Formato: 64 caracteres hexadecimales en minúsculas.
     * IntegrityHash.PENDING ("0"*64) indica sesión recién abierta sin seal.
     */
    @ColumnInfo(name = "root_hash")
    val rootHash: String
)

// ── Mapper: Entity ↔ Domain ───────────────────────────────────────────────────

/**
 * Convierte una ClinicalSessionEntity en los campos necesarios para
 * reconstruir el estado de la sesión en el dominio.
 *
 * NOTA: ClinicalSession es un Aggregate Root con constructor privado y
 * factory suspend fun open(). Para rehidratar desde BD se necesita un
 * mecanismo de reconstrucción que respete las invariantes del dominio.
 * Esta función retorna un ClinicalSessionSnapshot — un DTO intermedio
 * que el repositorio usa para reconstruir la sesión via ClinicalSession.restore().
 *
 * (ClinicalSession.restore() se añade al dominio en el siguiente commit
 * como factory alternativo para rehidratación desde persistencia.)
 */
fun ClinicalSessionEntity.toDomainSnapshot(): ClinicalSessionSnapshot =
    ClinicalSessionSnapshot(
        sessionId = SessionId(this.sessionId),
        currentPhase = PipelinePhase.valueOf(this.currentPhase),
        consentLevel = ConsentLevel.valueOf(this.consentLevel),
        openedAtUtc = UtcTimestamp(this.openedAtUtc),
        lastUpdatedAtUtc = UtcTimestamp(this.lastUpdatedAtUtc),
        isFrozen = this.isFrozen,
        rootHash = IntegrityHash(this.rootHash)
    )

/**
 * DTO intermedio para rehidratación de ClinicalSession desde la BD.
 * Evita que la entity de Room contamine el contrato del dominio.
 */
data class ClinicalSessionSnapshot(
    val sessionId: SessionId,
    val currentPhase: PipelinePhase,
    val consentLevel: ConsentLevel,
    val openedAtUtc: UtcTimestamp,
    val lastUpdatedAtUtc: UtcTimestamp,
    val isFrozen: Boolean,
    val rootHash: IntegrityHash
)

/**
 * Produce una ClinicalSessionEntity a partir de los datos de dominio
 * necesarios para persistir el estado actual de la sesión.
 *
 * @param session El Aggregate Root a persistir.
 * @param lastUpdatedAtUtc Timestamp de la operación de guardado.
 * @param rootHash Hash de sellado calculado por el repositorio.
 */
fun buildEntityFrom(
    sessionId: SessionId,
    currentPhase: PipelinePhase,
    consentLevel: ConsentLevel,
    openedAtUtc: UtcTimestamp,
    lastUpdatedAtUtc: UtcTimestamp,
    isFrozen: Boolean,
    rootHash: IntegrityHash
): ClinicalSessionEntity = ClinicalSessionEntity(
    sessionId = sessionId.value,
    currentPhase = currentPhase.name,       // ADR-003: .name protegido por consumer-rules.pro
    consentLevel = consentLevel.name,        // ADR-003: ídem
    openedAtUtc = openedAtUtc.iso8601,
    lastUpdatedAtUtc = lastUpdatedAtUtc.iso8601,
    isFrozen = isFrozen,
    rootHash = rootHash.hex
)
