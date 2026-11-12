package com.example.retail_loan_document_completeness_checker.models;

import jakarta.persistence.*;
import java.util.ArrayList;
import java.util.List;

// [SUB-104.2] Bảng DocumentFiles lưu file tài liệu
@Entity
@Table(name = "DocumentFiles")
public class DocumentFile {

    @Id
    @Column(name = "file_id", length = 50)
    private String fileId;

    @Enumerated(EnumType.STRING)
    @Column(name = "doc_type", nullable = false)
    private DocType docType;

    @Column(name = "file_name", nullable = false)
    private String fileName;

    @Column(name = "stored_file_name", nullable = false, length = 100)
    private String storedFileName;

    @Column(name = "storage_path", nullable = false, length = 500)
    private String storagePath;

    @Column(name = "content_type", nullable = false, length = 100)
    private String contentType;

    @Column(name = "file_size", nullable = false)
    private long fileSize;

    @Column(name = "file_hash_sha256", nullable = false, length = 64)
    private String fileHashSha256;

    @Enumerated(EnumType.STRING)
    @Column(name = "status")
    private DocStatus status;

    @Column(name = "missing_period")
    private String missingPeriod;

    @Column(name = "page_count")
    private int pageCount;

    @Column(name = "page_anchor")
    private int pageAnchor;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "dossier_version_id", nullable = false)
    private DossierVersion dossierVersion;

    @OneToMany(mappedBy = "documentFile", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<CompletenessResult> completenessResults = new ArrayList<>();

    public DocumentFile() {}

    public String getFileId() { return fileId; }
    public void setFileId(String fileId) { this.fileId = fileId; }

    public DocType getDocType() { return docType; }
    public void setDocType(DocType docType) { this.docType = docType; }

    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }

    public String getStoredFileName() { return storedFileName; }
    public void setStoredFileName(String storedFileName) { this.storedFileName = storedFileName; }

    public String getStoragePath() { return storagePath; }
    public void setStoragePath(String storagePath) { this.storagePath = storagePath; }

    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }

    public long getFileSize() { return fileSize; }
    public void setFileSize(long fileSize) { this.fileSize = fileSize; }

    public String getFileHashSha256() { return fileHashSha256; }
    public void setFileHashSha256(String fileHashSha256) { this.fileHashSha256 = fileHashSha256; }

    public DocStatus getStatus() { return status; }
    public void setStatus(DocStatus status) { this.status = status; }

    public String getMissingPeriod() { return missingPeriod; }
    public void setMissingPeriod(String missingPeriod) { this.missingPeriod = missingPeriod; }

    public int getPageCount() { return pageCount; }
    public void setPageCount(int pageCount) { this.pageCount = pageCount; }

    public int getPageAnchor() { return pageAnchor; }
    public void setPageAnchor(int pageAnchor) { this.pageAnchor = pageAnchor; }

    public DossierVersion getDossierVersion() { return dossierVersion; }
    public void setDossierVersion(DossierVersion dossierVersion) { this.dossierVersion = dossierVersion; }

    public List<CompletenessResult> getCompletenessResults() { return completenessResults; }
}
