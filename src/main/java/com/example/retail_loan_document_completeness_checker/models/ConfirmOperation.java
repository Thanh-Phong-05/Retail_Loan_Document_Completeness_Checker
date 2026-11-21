package com.example.retail_loan_document_completeness_checker.models;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.Nationalized;

import java.time.Instant;

@Entity
@Table(name = "ConfirmOperations", uniqueConstraints =
        @UniqueConstraint(name = "UQ_ConfirmOperation_Cycle", columnNames = {"application_id", "dossier_version"}))
public class ConfirmOperation {
    public enum State { PENDING, COMPLETING, RECONCILING, COMPLETED, REQUIRES_INTERVENTION }

    @Id
    @Column(name = "operation_id", length = 36)
    private String id;
    @Column(name = "application_id", nullable = false, length = 50)
    private String applicationId;
    @Column(name = "dossier_version", nullable = false)
    private long dossierVersion;
    @Column(name = "actor_id", nullable = false, length = 200)
    private String actorId;
    @Column(name = "actor_role", nullable = false, length = 50)
    private String actorRole;
    @Column(name = "reviewer_name", nullable = false, length = 200)
    @Nationalized
    private String reviewerName;
    @Column(name = "note", length = 1000)
    @Nationalized
    private String note;
    @Column(name = "snapshot_manifest", nullable = false, length = 128)
    private String snapshotManifest;
    @Column(name = "snapshot_context", nullable = false, length = 128)
    private String snapshotContext;
    @Column(name = "review_fingerprint", nullable = false, length = 64)
    private String reviewFingerprint;
    @Column(name = "evidence_valid_until", nullable = false)
    private Instant evidenceValidUntil;
    @Column(name = "process_instance_key", nullable = false)
    private long processInstanceKey;
    @Column(name = "user_task_key")
    private Long userTaskKey;
    @Column(name = "audit_log_id", length = 36)
    private String auditLogId;
    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 30)
    private State state;
    @Column(name = "attempts", nullable = false)
    private int attempts;
    @Column(name = "last_error", length = 200)
    private String lastError;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getApplicationId() { return applicationId; }
    public void setApplicationId(String applicationId) { this.applicationId = applicationId; }
    public long getDossierVersion() { return dossierVersion; }
    public void setDossierVersion(long dossierVersion) { this.dossierVersion = dossierVersion; }
    public String getActorId() { return actorId; }
    public void setActorId(String actorId) { this.actorId = actorId; }
    public String getActorRole() { return actorRole; }
    public void setActorRole(String actorRole) { this.actorRole = actorRole; }
    public String getReviewerName() { return reviewerName; }
    public void setReviewerName(String reviewerName) { this.reviewerName = reviewerName; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
    public String getSnapshotManifest() { return snapshotManifest; }
    public void setSnapshotManifest(String snapshotManifest) { this.snapshotManifest = snapshotManifest; }
    public String getSnapshotContext() { return snapshotContext; }
    public void setSnapshotContext(String snapshotContext) { this.snapshotContext = snapshotContext; }
    public String getReviewFingerprint() { return reviewFingerprint; }
    public void setReviewFingerprint(String reviewFingerprint) { this.reviewFingerprint = reviewFingerprint; }
    public Instant getEvidenceValidUntil() { return evidenceValidUntil; }
    public void setEvidenceValidUntil(Instant evidenceValidUntil) { this.evidenceValidUntil = evidenceValidUntil; }
    public long getProcessInstanceKey() { return processInstanceKey; }
    public void setProcessInstanceKey(long processInstanceKey) { this.processInstanceKey = processInstanceKey; }
    public Long getUserTaskKey() { return userTaskKey; }
    public void setUserTaskKey(Long userTaskKey) { this.userTaskKey = userTaskKey; }
    public String getAuditLogId() { return auditLogId; }
    public void setAuditLogId(String auditLogId) { this.auditLogId = auditLogId; }
    public State getState() { return state; }
    public void setState(State state) { this.state = state; }
    public int getAttempts() { return attempts; }
    public void setAttempts(int attempts) { this.attempts = attempts; }
    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
