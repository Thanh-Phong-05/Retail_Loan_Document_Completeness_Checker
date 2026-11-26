package com.example.retail_loan_document_completeness_checker.security;

import com.example.retail_loan_document_completeness_checker.dto.DossierResponse;
import com.example.retail_loan_document_completeness_checker.models.CompletenessStatus;
import com.example.retail_loan_document_completeness_checker.services.DossierService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "debug=false")
@AutoConfigureMockMvc
@ActiveProfiles({"test", "dev"})
class ConfirmSecurityTests {

    private static final String CONFIRM = "/api/v1/dossiers/APP-001/confirm";
    private static final String BODY = """
            {"reviewerName":"Chief Officer","note":null,"expectedVersion":3}
            """;

    @Autowired MockMvc mockMvc;
    @Autowired ApplicationContext context;
    @Autowired ConfirmAuthorizationService authorizationService;
    @MockitoBean DossierService dossierService;

    @Test
    void securityFilterIsRegisteredInMvcSlice() {
        assertTrue(context.containsBean("springSecurityFilterChain"));
    }

    @Test
    void anonymousCallerIsRejectedEvenWithValidBodyAndCsrf() throws Exception {
        mockMvc.perform(post(CONFIRM).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("CONFIRM_AUTHENTICATION_REQUIRED"));
        verifyNoInteractions(dossierService);
    }

    @Test
    void forgedClientIdentityHeadersDoNotAuthenticate() throws Exception {
        mockMvc.perform(post(CONFIRM).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .header("X-User", "reviewer")
                        .header("X-Role", "REVIEWER")
                        .content(BODY))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(dossierService);
    }

    @Test
    void authenticatedUserWithoutReviewerRoleIsForbidden() throws Exception {
        mockMvc.perform(post(CONFIRM).with(user("staff").roles("VIEWER")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CONFIRM_ACCESS_DENIED"));
        verifyNoInteractions(dossierService);
    }

    @Test
    void reviewerWithoutProvenCaseScopeIsForbidden() throws Exception {
        mockMvc.perform(post(CONFIRM).with(user("reviewer").roles("REVIEWER")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden());
        verifyNoInteractions(dossierService);
    }

    @Test
    void officerWithoutProvenCaseScopeIsForbidden() throws Exception {
        mockMvc.perform(post(CONFIRM).with(user("officer").roles("OFFICER")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden());
        verifyNoInteractions(dossierService);
    }

    @Test
    void trustedTestReviewerIsRecognizedButScopeStillFailsClosed() {
        var authentication = new UsernamePasswordAuthenticationToken("test-reviewer", null,
                List.of(new SimpleGrantedAuthority("ROLE_REVIEWER")));
        assertEquals(ConfirmAuthorizationService.Outcome.CASE_SCOPE_UNVERIFIED,
                authorizationService.evaluate(authentication, "APP-001"));
        assertEquals(ConfirmAuthorizationService.Outcome.UNAUTHENTICATED,
                authorizationService.evaluate(null, "APP-001"));
    }

    @Test
    void developmentPreflightWorksWithoutAuthentication() throws Exception {
        mockMvc.perform(options(CONFIRM)
                        .header(HttpHeaders.ORIGIN, "http://localhost:5173")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Content-Type"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:5173"));
    }

    @Test
    void dossierGetRemainsPublicInApiSecurityChain() throws Exception {
        mockMvc.perform(get("/api/v1/dossiers"))
                .andExpect(status().isOk());
    }

    @Test
    void dossierUploadRetainsItsExistingPublicCsrfBoundary() throws Exception {
        given(dossierService.upload(any(), anyList())).willReturn(new DossierResponse(
                "APP-001", "LOAN", "Sample Applicant", LocalDate.of(2026, 11, 20),
                1, Instant.parse("2026-11-20T00:00:00Z"), CompletenessStatus.REVIEW_NEEDED, List.of()));
        var metadata = new MockMultipartFile("metadata", "", MediaType.APPLICATION_JSON_VALUE,
                """
                        {"applicationId":"APP-001","productType":"LOAN","applicantName":"Sample Applicant"}
                        """.getBytes());
        var file = new MockMultipartFile("files", "sample.pdf", MediaType.APPLICATION_PDF_VALUE,
                "%PDF-1.4\nexample".getBytes());

        mockMvc.perform(multipart("/api/v1/dossiers/upload").file(metadata).file(file))
                .andExpect(status().isCreated());
    }
}
