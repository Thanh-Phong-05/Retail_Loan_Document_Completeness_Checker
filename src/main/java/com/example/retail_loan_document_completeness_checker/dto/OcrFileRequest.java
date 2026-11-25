package com.example.retail_loan_document_completeness_checker.dto;

public record OcrFileRequest(
        String fileId,
        String fileName,
        String filePath
) {
}