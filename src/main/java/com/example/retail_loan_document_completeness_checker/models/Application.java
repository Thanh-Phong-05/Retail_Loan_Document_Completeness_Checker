package com.example.retail_loan_document_completeness_checker.models;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.util.List;

// [SUB-104.2] Bảng Applications lưu hồ sơ
@Entity
@Table(name = "Applications")
public class Application {

    @Id
    @Column(name = "application_id", length = 50)
    private String applicationId;

    @Column(name = "product_type", nullable = false)
    private String productType;

    @Column(name = "applicant_name", nullable = false)
    private String applicantName;

    @Column(name = "submission_date")
    private LocalDate submissionDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "completeness_status")
    private CompletenessStatus completenessStatus;

    @OneToMany(mappedBy = "application", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<DocumentFile> documents;

    public Application() {}

    public String getApplicationId() { return applicationId; }
    public void setApplicationId(String applicationId) { this.applicationId = applicationId; }

    public String getProductType() { return productType; }
    public void setProductType(String productType) { this.productType = productType; }

    public String getApplicantName() { return applicantName; }
    public void setApplicantName(String applicantName) { this.applicantName = applicantName; }

    public LocalDate getSubmissionDate() { return submissionDate; }
    public void setSubmissionDate(LocalDate submissionDate) { this.submissionDate = submissionDate; }

    public CompletenessStatus getCompletenessStatus() { return completenessStatus; }
    public void setCompletenessStatus(CompletenessStatus completenessStatus) { this.completenessStatus = completenessStatus; }

    public List<DocumentFile> getDocuments() { return documents; }
    public void setDocuments(List<DocumentFile> documents) { this.documents = documents; }
}