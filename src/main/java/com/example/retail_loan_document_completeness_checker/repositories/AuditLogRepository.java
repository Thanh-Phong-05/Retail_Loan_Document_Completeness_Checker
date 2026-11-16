package com.example.retail_loan_document_completeness_checker.repositories;

import com.example.retail_loan_document_completeness_checker.models.AuditLog;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditLogRepository extends JpaRepository<AuditLog, String> {
}
