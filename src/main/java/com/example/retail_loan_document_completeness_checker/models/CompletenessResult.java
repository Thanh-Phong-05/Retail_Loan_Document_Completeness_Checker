package com.example.retail_loan_document_completeness_checker.models;

import jakarta.persistence.*;
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

    @Column(name = "document_class", length = 50)
    private String documentClass;

    @Column(name = "confidence")
    private Double confidence;

    @Column(name = "matched_keywords", length = 2000)
    private String matchedKeywords;

    @Column(name = "ocr_used")
    private Boolean ocrUsed;

    @Column(name = "needs_review")
    private Boolean needsReview;

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

    public String getDocumentClass() { return documentClass; }
    public void setDocumentClass(String documentClass) { this.documentClass = documentClass; }

    public Double getConfidence() { return confidence; }
    public void setConfidence(Double confidence) { this.confidence = confidence; }

    public String getMatchedKeywords() { return matchedKeywords; }
    public void setMatchedKeywords(String matchedKeywords) { this.matchedKeywords = matchedKeywords; }

    public Boolean getOcrUsed() { return ocrUsed; }
    public void setOcrUsed(Boolean ocrUsed) { this.ocrUsed = ocrUsed; }

    public Boolean getNeedsReview() { return needsReview; }
    public void setNeedsReview(Boolean needsReview) { this.needsReview = needsReview; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public DocumentFile getDocumentFile() { return documentFile; }
    public void setDocumentFile(DocumentFile documentFile) { this.documentFile = documentFile; }
}

