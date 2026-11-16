package com.example.retail_loan_document_completeness_checker.models;

// [SUB-104.1] Trạng thái kiểm tra file
public enum DocStatus {
    VALID,
    MISSING,
    BLURRY,
    PERIOD_GAP,
    NO_SIGNATURE
}