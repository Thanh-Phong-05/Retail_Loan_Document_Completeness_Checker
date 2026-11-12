package com.example.retail_loan_document_completeness_checker.models;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(
        name = "DossierVersions",
        uniqueConstraints = @UniqueConstraint(
                name = "UQ_DossierVersions_ApplicationVersion",
                columnNames = {"application_id", "version_number"}
        )
)
public class DossierVersion {

    @Id
    @Column(name = "dossier_version_id", length = 50)
    private String dossierVersionId;

    @Column(name = "version_number", nullable = false)
    private int versionNumber;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "application_id", nullable = false)
    private Application application;

    @OneToMany(mappedBy = "dossierVersion", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<DocumentFile> documents = new ArrayList<>();

    public DossierVersion() {}

    public String getDossierVersionId() { return dossierVersionId; }
    public void setDossierVersionId(String dossierVersionId) { this.dossierVersionId = dossierVersionId; }

    public int getVersionNumber() { return versionNumber; }
    public void setVersionNumber(int versionNumber) { this.versionNumber = versionNumber; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Application getApplication() { return application; }
    public void setApplication(Application application) { this.application = application; }

    public List<DocumentFile> getDocuments() { return documents; }

    public void addDocument(DocumentFile document) {
        documents.add(document);
        document.setDossierVersion(this);
    }
}
