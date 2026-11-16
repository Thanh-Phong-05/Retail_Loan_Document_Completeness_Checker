package com.example.retail_loan_document_completeness_checker.models;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

// [SUB-104.2] Bảng Applications lưu hồ sơ
@Entity
@Table(name = "Applications")
public class Application {

    @Id
    @Column(name = "application_id", length = 50)
    private String applicationId;

    @Column(name = "product_type", nullable = false, length = 100)
    private String productType;

    @Column(name = "applicant_name", nullable = false, length = 200)
    private String applicantName;

    @Column(name = "submission_date")
    private LocalDate submissionDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "completeness_status", length = 40)
    private CompletenessStatus completenessStatus;

    @OneToMany(mappedBy = "application", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<DossierVersion> dossierVersions = new ArrayList<>();

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

    public List<DossierVersion> getDossierVersions() { return dossierVersions; }

    public void addDossierVersion(DossierVersion dossierVersion) {
        dossierVersions.add(dossierVersion);
        dossierVersion.setApplication(this);
    }
}
