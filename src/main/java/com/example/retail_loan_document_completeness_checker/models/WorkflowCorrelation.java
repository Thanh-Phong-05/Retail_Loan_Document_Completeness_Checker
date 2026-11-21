package com.example.retail_loan_document_completeness_checker.models;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/** Engine-returned identity for one dossier review cycle. Never accepts a client-supplied key. */
@Entity
@Table(name = "WorkflowCorrelations", uniqueConstraints =
        @UniqueConstraint(name = "UQ_WorkflowCorrelation_Cycle", columnNames = {"application_id", "dossier_version"}))
public class WorkflowCorrelation {
    public enum State { START_PENDING, STARTED, REQUIRES_INTERVENTION }
    @Id
    @Column(name = "correlation_id", length = 36)
    private String id;
    @Column(name = "application_id", nullable = false, length = 50)
    private String applicationId;
    @Column(name = "dossier_version", nullable = false)
    private long dossierVersion;
    @Column(name = "process_instance_key")
    private Long processInstanceKey;
    @Column(name = "process_definition_key")
    private Long processDefinitionKey;
    @Column(name = "process_definition_version")
    private Integer processDefinitionVersion;
    @Column(name = "process_id", nullable = false, length = 150)
    private String processId;
    @Column(name = "tenant_id", nullable = false, length = 100)
    private String tenantId;
    @Column(name = "confirm_element_id", nullable = false, length = 100)
    private String confirmElementId;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 30)
    private State state;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getApplicationId() { return applicationId; }
    public void setApplicationId(String applicationId) { this.applicationId = applicationId; }
    public long getDossierVersion() { return dossierVersion; }
    public void setDossierVersion(long dossierVersion) { this.dossierVersion = dossierVersion; }
    public Long getProcessInstanceKey() { return processInstanceKey; }
    public void setProcessInstanceKey(Long processInstanceKey) { this.processInstanceKey = processInstanceKey; }
    public Long getProcessDefinitionKey() { return processDefinitionKey; }
    public void setProcessDefinitionKey(Long processDefinitionKey) { this.processDefinitionKey = processDefinitionKey; }
    public Integer getProcessDefinitionVersion() { return processDefinitionVersion; }
    public void setProcessDefinitionVersion(Integer processDefinitionVersion) { this.processDefinitionVersion = processDefinitionVersion; }
    public String getProcessId() { return processId; }
    public void setProcessId(String processId) { this.processId = processId; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getConfirmElementId() { return confirmElementId; }
    public void setConfirmElementId(String confirmElementId) { this.confirmElementId = confirmElementId; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public State getState() { return state; }
    public void setState(State state) { this.state = state; }
}
