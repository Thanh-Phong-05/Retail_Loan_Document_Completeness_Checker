package com.example.retail_loan_document_completeness_checker.repositories;

import com.example.retail_loan_document_completeness_checker.models.Application;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ApplicationRepository extends JpaRepository<Application, String> {
}
