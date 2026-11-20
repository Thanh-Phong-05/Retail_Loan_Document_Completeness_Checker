package com.example.retail_loan_document_completeness_checker.models;

// [SUB-104.1] Trạng thái kiểm tra file
public enum DocStatus {
    PENDING,
    VALID,
    MISSING_BACK_SIDE,
    BLURRY,
    PERIOD_GAP,
    MISSING_SIGNATURE
}
