package com.example.retail_loan_document_completeness_checker.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfirmDossierRequestTests {

    private static final String REVIEWER_NAME = "Nguyễn Văn Ánh";

    private static ValidatorFactory validatorFactory;
    private static Validator validator;

    @BeforeAll
    static void createValidator() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        validatorFactory.close();
    }

    @Test
    void acceptsPositiveExpectedVersion() {
        assertValid(new ConfirmDossierRequest(REVIEWER_NAME, "Optional confirmation note", 3L));
    }

    @Test
    void acceptsValidReviewerName() {
        assertValid(new ConfirmDossierRequest("Loan Officer", null, 1L));
    }

    @Test
    void rejectsNullReviewerName() {
        assertSingleViolation(new ConfirmDossierRequest(null, "note", 1L), "reviewerName", NotBlank.class);
    }

    @Test
    void rejectsEmptyReviewerName() {
        assertSingleViolation(new ConfirmDossierRequest("", "note", 1L), "reviewerName", NotBlank.class);
    }

    @Test
    void rejectsWhitespaceOnlyReviewerName() {
        assertSingleViolation(new ConfirmDossierRequest(" \t ", "note", 1L), "reviewerName", NotBlank.class);
    }

    @Test
    void acceptsReviewerNameOfExactlyTwoHundredCharacters() {
        assertValid(new ConfirmDossierRequest("N".repeat(200), "note", 1L));
    }

    @Test
    void rejectsReviewerNameLongerThanTwoHundredCharacters() {
        assertSingleViolation(new ConfirmDossierRequest("N".repeat(201), "note", 1L), "reviewerName", Size.class);
    }

    @Test
    void acceptsVietnameseReviewerName() {
        assertValid(new ConfirmDossierRequest(REVIEWER_NAME, "Đã kiểm tra hồ sơ", 3L));
    }

    @Test
    void acceptsCompleteRequest() {
        assertValid(new ConfirmDossierRequest("Nguyen Van A", "Da kiem tra ho so", 3L));
    }

    @Test
    void rejectsNullExpectedVersion() {
        assertSingleViolation(new ConfirmDossierRequest(REVIEWER_NAME, "note", null), "expectedVersion", NotNull.class);
    }

    @Test
    void rejectsZeroExpectedVersion() {
        assertSingleViolation(new ConfirmDossierRequest(REVIEWER_NAME, "note", 0L), "expectedVersion", Positive.class);
    }

    @Test
    void rejectsNegativeExpectedVersion() {
        assertSingleViolation(new ConfirmDossierRequest(REVIEWER_NAME, "note", -1L), "expectedVersion", Positive.class);
    }

    @Test
    void acceptsNullNote() {
        assertValid(new ConfirmDossierRequest(REVIEWER_NAME, null, 1L));
    }

    @Test
    void acceptsEmptyNote() {
        assertValid(new ConfirmDossierRequest(REVIEWER_NAME, "", 1L));
    }

    @Test
    void acceptsNoteOfExactlyOneThousandCharacters() {
        assertValid(new ConfirmDossierRequest(REVIEWER_NAME, "x".repeat(1000), 1L));
    }

    @Test
    void rejectsNoteLongerThanOneThousandCharacters() {
        assertSingleViolation(new ConfirmDossierRequest(REVIEWER_NAME, "x".repeat(1001), 1L), "note", Size.class);
    }

    private void assertValid(ConfirmDossierRequest request) {
        assertTrue(validator.validate(request).isEmpty());
    }

    private void assertSingleViolation(
            ConfirmDossierRequest request,
            String property,
            Class<? extends Annotation> constraint
    ) {
        Set<ConstraintViolation<ConfirmDossierRequest>> violations = validator.validate(request);
        assertEquals(1, violations.size());
        ConstraintViolation<ConfirmDossierRequest> violation = violations.iterator().next();
        assertEquals(property, violation.getPropertyPath().toString());
        assertEquals(constraint, violation.getConstraintDescriptor().getAnnotation().annotationType());
    }
}
