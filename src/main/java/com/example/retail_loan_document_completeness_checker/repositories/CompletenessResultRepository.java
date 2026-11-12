package com.example.retail_loan_document_completeness_checker.repositories;

import com.example.retail_loan_document_completeness_checker.models.CompletenessResult;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CompletenessResultRepository extends JpaRepository<CompletenessResult, String> {
}
