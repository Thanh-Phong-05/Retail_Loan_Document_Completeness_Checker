package com.example.retail_loan_document_completeness_checker.repositories;

import com.example.retail_loan_document_completeness_checker.models.Application;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

import java.util.Optional;

public interface ApplicationRepository extends JpaRepository<Application, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select application from Application application where application.applicationId = :applicationId")
    Optional<Application> findByIdForUpdate(@Param("applicationId") String applicationId);
}
