package com.example.retail_loan_document_completeness_checker.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record UploadDossierRequest(
        @NotBlank
        @Size(max = 50)
        @Pattern(regexp = "[A-Za-z0-9_-]+", message = "applicationId may contain only letters, numbers, '_' and '-'")
        String applicationId,

        @NotBlank
        @Size(max = 100)
        String productType,

        @NotBlank
        @Size(max = 200)
        String applicantName
) {
}
