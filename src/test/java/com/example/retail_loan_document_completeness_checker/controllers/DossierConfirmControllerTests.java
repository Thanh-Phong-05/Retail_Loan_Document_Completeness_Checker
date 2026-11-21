package com.example.retail_loan_document_completeness_checker.controllers;

import com.example.retail_loan_document_completeness_checker.security.ConfirmAuthorizationService;
import com.example.retail_loan_document_completeness_checker.security.ConfirmSecurityConfig;
import com.example.retail_loan_document_completeness_checker.services.DossierService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(value = DossierController.class, properties = "debug=false")
@Import(ConfirmSecurityConfig.class)
class DossierConfirmControllerTests {

    private static final String CONFIRM_PATH = "/api/v1/dossiers/APP-001/confirm";
    private static final String VALID_BODY = """
            {"reviewerName":"Nguyen Van A","note":"Da kiem tra ho so","expectedVersion":3}
            """;

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    DossierService dossierService;

    @MockitoBean
    ConfirmAuthorizationService confirmAuthorizationService;

    @Test
    void rejectsInvalidApplicationId() throws Exception {
        mockMvc.perform(authorizedPost("/api/v1/dossiers/APP.BAD/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(dossierService);
    }

    @Test
    void rejectsApplicationIdLongerThanFiftyCharacters() throws Exception {
        mockMvc.perform(authorizedPost("/api/v1/dossiers/" + "A".repeat(51) + "/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(dossierService);
    }

    @Test
    void rejectsMissingBody() throws Exception {
        mockMvc.perform(authorizedPost(CONFIRM_PATH).contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(dossierService);
    }

    @Test
    void rejectsNullReviewerName() throws Exception {
        assertInvalidBody("""
                {"reviewerName":null,"note":null,"expectedVersion":3}
                """);
    }

    @Test
    void rejectsBlankReviewerName() throws Exception {
        assertInvalidBody("""
                {"reviewerName":"   ","note":null,"expectedVersion":3}
                """);
    }

    @Test
    void rejectsReviewerNameLongerThanTwoHundredCharacters() throws Exception {
        assertInvalidBody("""
                {"reviewerName":"%s","note":null,"expectedVersion":3}
                """.formatted("N".repeat(201)));
    }

    @Test
    void rejectsNullExpectedVersion() throws Exception {
        assertInvalidBody("""
                {"reviewerName":"Nguyen Van A","note":null,"expectedVersion":null}
                """);
    }

    @Test
    void rejectsZeroExpectedVersion() throws Exception {
        assertInvalidBody("""
                {"reviewerName":"Nguyen Van A","note":null,"expectedVersion":0}
                """);
    }

    @Test
    void rejectsNegativeExpectedVersion() throws Exception {
        assertInvalidBody("""
                {"reviewerName":"Nguyen Van A","note":null,"expectedVersion":-1}
                """);
    }

    @Test
    void rejectsNoteLongerThanOneThousandCharacters() throws Exception {
        assertInvalidBody("""
                {"reviewerName":"Nguyen Van A","note":"%s","expectedVersion":3}
                """.formatted("n".repeat(1001)));
    }

    @Test
    void validRequestFailsClosedWithoutCallingService() throws Exception {
        mockMvc.perform(authorizedPost(CONFIRM_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("DOSSIER_CONFIRMATION_UNAVAILABLE"))
                .andExpect(jsonPath("$.completenessStatus").doesNotExist())
                .andExpect(jsonPath("$.auditLogs").doesNotExist());
        verifyNoInteractions(dossierService);
    }

    @Test
    void selfDeclaredReviewerNameDoesNotGrantConfirmation() throws Exception {
        mockMvc.perform(post(CONFIRM_PATH).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reviewerName":"Chief Officer","note":null,"expectedVersion":3}
                                """))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(dossierService);
    }

    private void assertInvalidBody(String body) throws Exception {
        mockMvc.perform(authorizedPost(CONFIRM_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CONFIRM_REQUEST"));
        verifyNoInteractions(dossierService);
    }

    private MockHttpServletRequestBuilder authorizedPost(String path) {
        // Test-only authorization allows this slice to verify validation and the unchanged 503 gate.
        given(confirmAuthorizationService.evaluate(any(Authentication.class), anyString()))
                .willReturn(ConfirmAuthorizationService.Outcome.AUTHORIZED);
        return post(path).with(user("test-reviewer").roles("REVIEWER")).with(csrf());
    }
}
