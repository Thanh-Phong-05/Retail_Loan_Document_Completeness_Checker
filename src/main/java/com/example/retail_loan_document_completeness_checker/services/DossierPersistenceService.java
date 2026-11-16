package com.example.retail_loan_document_completeness_checker.services;

import com.example.retail_loan_document_completeness_checker.dto.DocumentResponse;
import com.example.retail_loan_document_completeness_checker.dto.DossierResponse;
import com.example.retail_loan_document_completeness_checker.dto.UploadDossierRequest;
import com.example.retail_loan_document_completeness_checker.exceptions.DossierNotFoundException;
import com.example.retail_loan_document_completeness_checker.models.Application;
import com.example.retail_loan_document_completeness_checker.models.AuditLog;
import com.example.retail_loan_document_completeness_checker.models.CompletenessStatus;
import com.example.retail_loan_document_completeness_checker.models.DocStatus;
import com.example.retail_loan_document_completeness_checker.models.DocType;
import com.example.retail_loan_document_completeness_checker.models.DocumentFile;
import com.example.retail_loan_document_completeness_checker.models.DossierVersion;
import com.example.retail_loan_document_completeness_checker.repositories.ApplicationRepository;
import com.example.retail_loan_document_completeness_checker.repositories.AuditLogRepository;
import com.example.retail_loan_document_completeness_checker.repositories.DossierVersionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Service
public class DossierPersistenceService {

    private final ApplicationRepository applicationRepository;
    private final DossierVersionRepository dossierVersionRepository;
    private final AuditLogRepository auditLogRepository;

    public DossierPersistenceService(
            ApplicationRepository applicationRepository,
            DossierVersionRepository dossierVersionRepository,
            AuditLogRepository auditLogRepository
    ) {
        this.applicationRepository = applicationRepository;
        this.dossierVersionRepository = dossierVersionRepository;
        this.auditLogRepository = auditLogRepository;
    }

    @Transactional
    public DossierResponse saveUpload(UploadDossierRequest request, List<StoredFile> storedFiles) {
        Application application = applicationRepository.findById(request.applicationId())
                .orElseGet(() -> newApplication(request));
        application.setProductType(request.productType());
        application.setApplicantName(request.applicantName());
        application.setSubmissionDate(LocalDate.now());
        application.setCompletenessStatus(CompletenessStatus.REVIEW_NEEDED);

        int nextVersion = dossierVersionRepository
                .findFirstByApplicationApplicationIdOrderByVersionNumberDesc(request.applicationId())
                .map(version -> version.getVersionNumber() + 1)
                .orElse(1);

        DossierVersion dossierVersion = new DossierVersion();
        dossierVersion.setDossierVersionId(UUID.randomUUID().toString());
        dossierVersion.setVersionNumber(nextVersion);
        dossierVersion.setCreatedAt(Instant.now());
        storedFiles.stream().map(this::toDocument).forEach(dossierVersion::addDocument);
        application.addDossierVersion(dossierVersion);

        applicationRepository.save(application);
        auditLogRepository.save(uploadAuditLog(application, dossierVersion));
        return toResponse(application, dossierVersion);
    }

    @Transactional(readOnly = true)
    public DossierResponse getLatest(String applicationId) {
        Application application = applicationRepository.findById(applicationId)
                .orElseThrow(() -> new DossierNotFoundException(applicationId));
        DossierVersion version = dossierVersionRepository
                .findFirstByApplicationApplicationIdOrderByVersionNumberDesc(applicationId)
                .orElseThrow(() -> new DossierNotFoundException(applicationId));
        return toResponse(application, version);
    }

    private Application newApplication(UploadDossierRequest request) {
        Application application = new Application();
        application.setApplicationId(request.applicationId());
        return application;
    }

    private DocumentFile toDocument(StoredFile storedFile) {
        DocumentFile document = new DocumentFile();
        document.setFileId(storedFile.fileId());
        document.setDocType(DocType.UNKNOWN);
        document.setFileName(storedFile.originalFileName());
        document.setStoredFileName(storedFile.storedFileName());
        document.setStoragePath(storedFile.storagePath().toString());
        document.setContentType(storedFile.contentType());
        document.setFileSize(storedFile.fileSize());
        document.setFileHashSha256(storedFile.sha256());
        document.setStatus(DocStatus.PENDING);
        return document;
    }

    private AuditLog uploadAuditLog(Application application, DossierVersion version) {
        AuditLog auditLog = new AuditLog();
        auditLog.setAuditLogId(UUID.randomUUID().toString());
        auditLog.setAction("DOSSIER_UPLOADED");
        auditLog.setDetails("Created dossier version " + version.getVersionNumber()
                + " with " + version.getDocuments().size() + " document(s)");
        auditLog.setCreatedAt(Instant.now());
        auditLog.setApplication(application);
        return auditLog;
    }

    private DossierResponse toResponse(Application application, DossierVersion version) {
        List<DocumentResponse> documents = version.getDocuments().stream()
                .map(document -> new DocumentResponse(
                        document.getFileId(),
                        document.getDocType(),
                        document.getFileName(),
                        document.getContentType(),
                        document.getFileSize(),
                        document.getFileHashSha256(),
                        document.getStatus(),
                        document.getMissingPeriod(),
                        document.getPageCount(),
                        document.getPageAnchor()
                ))
                .toList();

        return new DossierResponse(
                application.getApplicationId(),
                application.getProductType(),
                application.getApplicantName(),
                application.getSubmissionDate(),
                version.getVersionNumber(),
                version.getCreatedAt(),
                application.getCompletenessStatus(),
                documents
        );
    }
}
