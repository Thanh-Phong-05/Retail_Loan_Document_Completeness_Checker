package com.example.retail_loan_document_completeness_checker.services;

import java.nio.file.Path;

public record StoredFile(
        String fileId,
        String originalFileName,
        String storedFileName,
        Path storagePath,
        String contentType,
        long fileSize,
        String sha256
) {
}
