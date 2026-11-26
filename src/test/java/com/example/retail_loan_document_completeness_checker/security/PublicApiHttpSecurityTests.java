package com.example.retail_loan_document_completeness_checker.security;

import com.example.retail_loan_document_completeness_checker.config.DevCorsConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Tests HTTP policy only. Actual Confirm service authorization is covered by ConfirmSecurityTests. */
@WebMvcTest(PublicApiHttpSecurityTests.ProbeController.class)
@Import({ConfirmSecurityConfig.class, DevCorsConfig.class, PublicApiHttpSecurityTests.ProbeController.class})
@ActiveProfiles("prod")
class PublicApiHttpSecurityTests {
    @Autowired MockMvc mvc;

    @Test void apiOutsideDossiersIsPublicWithoutDevelopmentProfile() throws Exception {
        mvc.perform(get("/api/v1/policy-probe")).andExpect(status().isOk()).andExpect(content().string("public"));
    }

    @Test void httpPolicyPermitsAnonymousConfirmWithCsrf() throws Exception {
        mvc.perform(post("/api/v1/dossiers/PROBE/confirm").with(csrf()))
                .andExpect(status().isOk()).andExpect(content().string("http-only"));
    }

    @Test void confirmPostStillRequiresValidCsrf() throws Exception {
        mvc.perform(post("/api/v1/dossiers/PROBE/confirm")).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/dossiers/PROBE/confirm").with(csrf().useInvalidToken()))
                .andExpect(status().isForbidden());
    }

    @Test void developmentCorsIsNotEnabledInProduction() throws Exception {
        mvc.perform(options("/api/v1/dossiers/PROBE/confirm")
                .header("Origin", "http://localhost:5173")
                .header("Access-Control-Request-Method", "POST"))
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    @RestController
    static class ProbeController {
        @GetMapping("/api/v1/policy-probe") String get() { return "public"; }
        @PostMapping("/api/v1/dossiers/PROBE/confirm") String post() { return "http-only"; }
    }
}
