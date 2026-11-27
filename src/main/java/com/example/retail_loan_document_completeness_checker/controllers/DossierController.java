package com.example.retail_loan_document_completeness_checker.controllers;

import com.example.retail_loan_document_completeness_checker.dto.ConfirmDossierRequest;
import com.example.retail_loan_document_completeness_checker.services.ConfirmService;
import com.example.retail_loan_document_completeness_checker.services.DurableConfirmCoordinator;
import com.example.retail_loan_document_completeness_checker.models.ConfirmOperation;
import com.example.retail_loan_document_completeness_checker.dto.DocumentContent;
import com.example.retail_loan_document_completeness_checker.dto.DossierResponse;
import com.example.retail_loan_document_completeness_checker.dto.UploadDossierRequest;
import com.example.retail_loan_document_completeness_checker.services.DossierService;
import jakarta.validation.Valid;
import jakarta.validation.Validator;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.Authentication;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
    private final Validator validator;
    private final ObjectProvider<ConfirmService> confirmServices;
    private final ObjectProvider<DurableConfirmCoordinator> durableConfirm;

    public DossierController(DossierService dossierService, Validator validator,
                             ObjectProvider<ConfirmService> confirmServices,
                             ObjectProvider<DurableConfirmCoordinator> durableConfirm) {
        this.dossierService = dossierService;
        this.validator = validator;
        this.confirmServices = confirmServices;
        this.durableConfirm = durableConfirm;
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

    @PostMapping(value = "/{applicationId}/confirm", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> confirm(
            @PathVariable String applicationId,
            @RequestBody ConfirmDossierRequest request,
            Authentication authentication
    ) {
        // Class-level @Validated otherwise throws before this route can return a confirm-specific 400.
        if (!applicationId.matches("[A-Za-z0-9_-]{1,50}") || !validator.validate(request).isEmpty()) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                    HttpStatus.BAD_REQUEST, "The confirmation request is invalid");
            problem.setTitle("Invalid confirmation request");
            problem.setProperty("code", "INVALID_CONFIRM_REQUEST");
            return ResponseEntity.badRequest().body(problem);
        }

        DurableConfirmCoordinator coordinator = durableConfirm.getIfAvailable();
        if (coordinator != null) {
            var result = coordinator.confirm(applicationId, request, authentication);
            return result.state() == ConfirmOperation.State.COMPLETED
                    ? ResponseEntity.ok(result)
                    : ResponseEntity.accepted().location(URI.create(result.statusUrl())).body(result);
        }
        ConfirmService service = confirmServices.getIfAvailable();
        if (service == null) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                    HttpStatus.SERVICE_UNAVAILABLE, "Dossier confirmation is currently unavailable");
            problem.setTitle("Dossier confirmation unavailable");
            problem.setProperty("code", "DOSSIER_CONFIRMATION_UNAVAILABLE");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(problem);
        }
        return ResponseEntity.ok(service.confirm(applicationId, request, authentication));
    }

    @GetMapping("/{applicationId}/confirm-operations/{operationId}")
    public ResponseEntity<?> confirmStatus(@PathVariable String applicationId,
                                           @PathVariable String operationId,
                                           Authentication authentication) {
        if (!applicationId.matches("[A-Za-z0-9_-]{1,50}")
                || !operationId.matches("[0-9a-fA-F-]{36}")) {
            return ResponseEntity.badRequest().build();
        }
        DurableConfirmCoordinator coordinator = durableConfirm.getIfAvailable();
        if (coordinator == null) return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        return ResponseEntity.ok(coordinator.status(applicationId, operationId, authentication));
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
