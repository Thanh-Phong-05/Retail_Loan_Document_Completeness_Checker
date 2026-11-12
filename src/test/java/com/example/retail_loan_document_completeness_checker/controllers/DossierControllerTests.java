package com.example.retail_loan_document_completeness_checker.controllers;

import com.example.retail_loan_document_completeness_checker.dto.DocumentResponse;
import com.example.retail_loan_document_completeness_checker.dto.DossierResponse;
import com.example.retail_loan_document_completeness_checker.exceptions.DossierNotFoundException;
import com.example.retail_loan_document_completeness_checker.models.CompletenessStatus;
import com.example.retail_loan_document_completeness_checker.models.DocStatus;
import com.example.retail_loan_document_completeness_checker.models.DocType;
import com.example.retail_loan_document_completeness_checker.services.DossierService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(value = DossierController.class, properties = "debug=false")
class DossierControllerTests {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    DossierService dossierService;

    @Test
    void uploadsDossierAndReturnsCreatedResponse() throws Exception {
        given(dossierService.upload(any(), anyList())).willReturn(dossierResponse());
        var metadata = new MockMultipartFile(
                "metadata",
                "",
                MediaType.APPLICATION_JSON_VALUE,
                """
                        {
                          "applicationId": "APP-001",
                          "productType": "UNSECURED_SALARIED_LOAN",
                          "applicantName": "Dao Quoc Thanh"
                        }
                        """.getBytes()
        );
        var document = new MockMultipartFile(
                "files",
                "statement.pdf",
                MediaType.APPLICATION_PDF_VALUE,
                "%PDF-1.4\nexample".getBytes()
        );

        mockMvc.perform(multipart("/api/v1/dossiers/upload").file(metadata).file(document))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/dossiers/APP-001"))
                .andExpect(jsonPath("$.applicationId").value("APP-001"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.documents[0].fileHashSha256").value("abc123"));
    }

    @Test
    void returnsLatestDossier() throws Exception {
        given(dossierService.getLatest("APP-001")).willReturn(dossierResponse());

        mockMvc.perform(get("/api/v1/dossiers/APP-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.completenessStatus").value("REVIEW_NEEDED"))
                .andExpect(jsonPath("$.documents[0].status").value("PENDING"));
    }

    @Test
    void returnsProblemDetailWhenDossierDoesNotExist() throws Exception {
        given(dossierService.getLatest("APP-404")).willThrow(new DossierNotFoundException("APP-404"));

        mockMvc.perform(get("/api/v1/dossiers/APP-404"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DOSSIER_NOT_FOUND"));
    }

    private DossierResponse dossierResponse() {
        var document = new DocumentResponse(
                "FILE-001",
                DocType.UNKNOWN,
                "statement.pdf",
                MediaType.APPLICATION_PDF_VALUE,
                16,
                "abc123",
                DocStatus.PENDING,
                null,
                0,
                0
        );
        return new DossierResponse(
                "APP-001",
                "UNSECURED_SALARIED_LOAN",
                "Dao Quoc Thanh",
                LocalDate.of(2026, 11, 11),
                1,
                Instant.parse("2026-11-11T10:00:00Z"),
                CompletenessStatus.REVIEW_NEEDED,
                List.of(document)
        );
    }
}
