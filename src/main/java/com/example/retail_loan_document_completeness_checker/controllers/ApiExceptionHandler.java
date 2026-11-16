package com.example.retail_loan_document_completeness_checker.controllers;

import com.example.retail_loan_document_completeness_checker.exceptions.DossierNotFoundException;
import com.example.retail_loan_document_completeness_checker.exceptions.FileStorageException;
import com.example.retail_loan_document_completeness_checker.exceptions.FileValidationException;
import com.example.retail_loan_document_completeness_checker.exceptions.ProcessStartException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(DossierNotFoundException.class)
    ProblemDetail dossierNotFound(DossierNotFoundException exception) {
        return problem(HttpStatus.NOT_FOUND, "Dossier not found", exception.getMessage(), "DOSSIER_NOT_FOUND");
    }

    @ExceptionHandler({FileValidationException.class, MethodArgumentNotValidException.class})
    ProblemDetail invalidRequest(Exception exception) {
        String detail = exception instanceof FileValidationException
                ? exception.getMessage()
                : "The request metadata is invalid";
        return problem(HttpStatus.BAD_REQUEST, "Invalid dossier upload", detail, "INVALID_DOSSIER_UPLOAD");
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ProblemDetail uploadTooLarge() {
        return problem(HttpStatus.CONTENT_TOO_LARGE, "Upload too large",
                "The upload exceeds the configured request size", "UPLOAD_TOO_LARGE");
    }

    @ExceptionHandler(FileStorageException.class)
    ProblemDetail storageFailure() {
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Document storage failed",
                "The uploaded documents could not be stored", "DOCUMENT_STORAGE_FAILED");
    }

    @ExceptionHandler(ProcessStartException.class)
    ProblemDetail processFailure(ProcessStartException exception) {
        return problem(HttpStatus.BAD_GATEWAY, "Camunda process start failed",
                exception.getMessage(), "CAMUNDA_PROCESS_START_FAILED");
    }

    private ProblemDetail problem(HttpStatus status, String title, String detail, String code) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        problem.setProperty("code", code);
        return problem;
    }
}
