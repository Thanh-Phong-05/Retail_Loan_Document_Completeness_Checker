
package com.example.retail_loan_document_completeness_checker.dto;

import java.util.List;

public record OcrPageResult(
        Integer pageAnchor,
        String documentClass,
        Double confidence,
        List<String> matchedKeywords,
        Boolean ocrUsed,
        Boolean needsReview
) {
}
