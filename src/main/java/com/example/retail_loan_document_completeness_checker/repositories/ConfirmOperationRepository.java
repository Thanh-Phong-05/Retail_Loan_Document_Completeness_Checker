package com.example.retail_loan_document_completeness_checker.repositories;

import com.example.retail_loan_document_completeness_checker.models.ConfirmOperation;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ConfirmOperationRepository extends JpaRepository<ConfirmOperation, String> {
    Optional<ConfirmOperation> findByApplicationIdAndDossierVersion(String applicationId, long dossierVersion);
    boolean existsByApplicationIdAndStateIn(String applicationId, List<ConfirmOperation.State> states);
    List<ConfirmOperation> findByStateIn(List<ConfirmOperation.State> states);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select operation from ConfirmOperation operation where operation.id = :id")
    Optional<ConfirmOperation> findByIdForUpdate(@Param("id") String id);
}
