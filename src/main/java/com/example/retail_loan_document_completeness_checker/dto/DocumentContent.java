package com.example.retail_loan_document_completeness_checker.dto;

import org.springframework.core.io.Resource;

public record DocumentContent(
        Resource resource,
        String fileName,
        String contentType
) {
}