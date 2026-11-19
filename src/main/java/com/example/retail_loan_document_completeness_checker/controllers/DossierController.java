package com.example.retail_loan_document_completeness_checker.controllers;

import com.example.retail_loan_document_completeness_checker.dto.DocumentContent;
import com.example.retail_loan_document_completeness_checker.dto.DossierResponse;
import com.example.retail_loan_document_completeness_checker.dto.UploadDossierRequest;
import com.example.retail_loan_document_completeness_checker.services.DossierService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Validated
@RestController
@RequestMapping("/api/v1/dossiers")
public class DossierController {

    private final DossierService dossierService;

    public DossierController(DossierService dossierService) {
        this.dossierService = dossierService;
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<DossierResponse> upload(
            @Valid @RequestPart("metadata") UploadDossierRequest metadata,
            @RequestPart("files") List<MultipartFile> files
    ) {
        DossierResponse dossier = dossierService.upload(metadata, files);
        return ResponseEntity
                .created(URI.create("/api/v1/dossiers/" + dossier.applicationId()))
                .body(dossier);
    }

    @GetMapping
    public List<DossierResponse> getAllDossiers() {
        return dossierService.getAllDossiers();
    }

    @GetMapping("/{applicationId}")
    public DossierResponse getLatest(
            @PathVariable
            @Size(max = 50)
            @Pattern(regexp = "[A-Za-z0-9_-]+")
            String applicationId
    ) {
        return dossierService.getLatest(applicationId);
    }

    @GetMapping("/{applicationId}/files/{fileId}/content")
    public ResponseEntity<Resource> getFileContent(
            @PathVariable
            @Size(max = 50)
            @Pattern(regexp = "[A-Za-z0-9_-]+")
            String applicationId,
            @PathVariable
            @Size(max = 50)
            @Pattern(regexp = "[A-Za-z0-9_-]+")
            String fileId
    ) {
        DocumentContent content = dossierService.getFileContent(applicationId, fileId);
        MediaType mediaType = MediaType.parseMediaType(content.contentType());
        ContentDisposition contentDisposition = ContentDisposition.inline()
                .filename(content.fileName(), StandardCharsets.UTF_8)
                .build();

        return ResponseEntity.ok()
                .contentType(mediaType)
                .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition.toString())
                .body(content.resource());
    }
}