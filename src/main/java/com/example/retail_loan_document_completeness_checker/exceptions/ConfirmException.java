package com.example.retail_loan_document_completeness_checker.exceptions;

import org.springframework.http.HttpStatus;

public class ConfirmException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final String title;

    public ConfirmException(HttpStatus status, String code, String title, String detail) {
        super(detail);
        this.status = status;
        this.code = code;
        this.title = title;
    }

    public HttpStatus status() { return status; }
    public String code() { return code; }
    public String title() { return title; }
}
