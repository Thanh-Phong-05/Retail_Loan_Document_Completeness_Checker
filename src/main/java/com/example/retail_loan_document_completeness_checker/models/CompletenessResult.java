package com.example.retail_loan_document_completeness_checker.models;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "CompletenessResults")
public class CompletenessResult {

    @Id
    @Column(name = "result_id", length = 50)
    private String resultId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private DocStatus status;

    @Column(name = "reason", length = 500)
    private String reason;

    @Column(name = "missing_period", length = 100)
    private String missingPeriod;

    @Column(name = "page_anchor")
    private Integer pageAnchor;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "file_id", nullable = false)
    private DocumentFile documentFile;

    public CompletenessResult() {}

    public String getResultId() { return resultId; }
    public void setResultId(String resultId) { this.resultId = resultId; }

    public DocStatus getStatus() { return status; }
    public void setStatus(DocStatus status) { this.status = status; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    public String getMissingPeriod() { return missingPeriod; }
    public void setMissingPeriod(String missingPeriod) { this.missingPeriod = missingPeriod; }

    public Integer getPageAnchor() { return pageAnchor; }
    public void setPageAnchor(Integer pageAnchor) { this.pageAnchor = pageAnchor; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public DocumentFile getDocumentFile() { return documentFile; }
    public void setDocumentFile(DocumentFile documentFile) { this.documentFile = documentFile; }
}
