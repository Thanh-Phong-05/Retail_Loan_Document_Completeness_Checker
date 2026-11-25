package com.example.retail_loan_document_completeness_checker.services;

import com.example.retail_loan_document_completeness_checker.dto.DocumentContent;
import com.example.retail_loan_document_completeness_checker.dto.DossierResponse;
import com.example.retail_loan_document_completeness_checker.dto.UploadDossierRequest;
import com.example.retail_loan_document_completeness_checker.models.DocumentFile;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@Service
public class DossierService {

    private final FileStorageService fileStorageService;
    private final DossierPersistenceService persistenceService;
    private final DossierProcessStarter processStarter;

    public DossierService(
            FileStorageService fileStorageService,
            DossierPersistenceService persistenceService,
            DossierProcessStarter processStarter
    ) {
        this.fileStorageService = fileStorageService;
        this.persistenceService = persistenceService;
        this.processStarter = processStarter;
    }

    public DossierResponse upload(UploadDossierRequest request, List<MultipartFile> files) {
        List<StoredFile> storedFiles = fileStorageService.storeAll(files);
        DossierResponse response;
        try {
            response = persistenceService.saveUpload(request, storedFiles);
        } catch (RuntimeException exception) {
            fileStorageService.deleteAll(storedFiles);
            throw exception;
        }

        processStarter.start(response.applicationId(), response.version());
        return response;
    }

    public DossierResponse getLatest(String applicationId) {
        return persistenceService.getLatest(applicationId);
    }

    public List<DossierResponse> getAllDossiers() {
        return persistenceService.getAllLatest();
    }

    public DocumentContent getFileContent(String applicationId, String fileId) {
        DocumentFile documentFile = persistenceService.getDocumentFile(applicationId, fileId);
        Resource resource = fileStorageService.loadAsResource(documentFile.getStoredFileName());
        return new DocumentContent(
                resource,
                documentFile.getFileName(),
                documentFile.getContentType()
        );
    }
}