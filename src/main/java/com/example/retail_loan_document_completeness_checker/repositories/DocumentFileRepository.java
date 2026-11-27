package com.example.retail_loan_document_completeness_checker.repositories;

import com.example.retail_loan_document_completeness_checker.models.DocumentFile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface DocumentFileRepository extends JpaRepository<DocumentFile, String> {

    Optional<DocumentFile> findByFileIdAndDossierVersionApplicationApplicationId(String fileId, String applicationId);
}