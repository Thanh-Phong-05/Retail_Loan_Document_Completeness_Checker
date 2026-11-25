package com.example.retail_loan_document_completeness_checker.controllers;

import com.example.retail_loan_document_completeness_checker.config.DevCorsConfig;
import com.example.retail_loan_document_completeness_checker.security.ConfirmAuthorizationService;
import com.example.retail_loan_document_completeness_checker.security.ConfirmSecurityConfig;
import com.example.retail_loan_document_completeness_checker.services.DossierService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(value = DossierController.class, properties = "debug=false")
@Import({DevCorsConfig.class, DossierCorsTests.OtherController.class,
        ConfirmSecurityConfig.class, ConfirmAuthorizationService.class})
@ActiveProfiles("dev")
class DossierCorsTests {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ApplicationContext context;

    @MockitoBean
    DossierService dossierService;

    @Test
    void acceptsGetPreflightFromViteOnCollection() throws Exception {
        mockMvc.perform(options("/api/v1/dossiers")
                        .header(HttpHeaders.ORIGIN, "http://localhost:5173")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:5173"))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, "GET,POST"))
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS));
    }

    @Test
    void acceptsPostPreflightFromReactOnUpload() throws Exception {
        mockMvc.perform(options("/api/v1/dossiers/upload")
                        .header(HttpHeaders.ORIGIN, "http://localhost:3000")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Content-Type"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:3000"))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, "GET,POST"))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS, "Content-Type"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://localhost:5173", "http://localhost:3000"})
    void acceptsConfirmPreflightWithAuthorizationJsonAndCsrfHeader(String origin) throws Exception {
        mockMvc.perform(options("/api/v1/dossiers/APP-001/confirm")
                        .header(HttpHeaders.ORIGIN, origin)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS,
                                "authorization,content-type,x-xsrf-token"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, origin))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, "GET,POST"))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS,
                        "authorization, content-type, x-xsrf-token"))
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS));
    }

    @Test
    void acceptsGetPreflightOnNestedFileContentPath() throws Exception {
        mockMvc.perform(options("/api/v1/dossiers/APP-001/files/FILE-001/content")
                        .header(HttpHeaders.ORIGIN, "http://localhost:5173")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:5173"));
    }

    @Test
    void rejectsUnlistedOrigin() throws Exception {
        mockMvc.perform(options("/api/v1/dossiers")
                        .header(HttpHeaders.ORIGIN, "http://localhost:9999")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    @Test
    void rejectsUnlistedRequestHeader() throws Exception {
        mockMvc.perform(options("/api/v1/dossiers/upload")
                        .header(HttpHeaders.ORIGIN, "http://localhost:5173")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "X-Unapproved"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    @Test
    void rejectsMethodOutsideGetAndPost() throws Exception {
        mockMvc.perform(options("/api/v1/dossiers")
                        .header(HttpHeaders.ORIGIN, "http://localhost:5173")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "PUT"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    @Test
    void doesNotGrantCorsOutsideDossierApi() throws Exception {
        mockMvc.perform(get("/api/v1/other")
                        .header(HttpHeaders.ORIGIN, "http://localhost:5173"))
                .andExpect(status().isOk())
                .andExpect(content().string("other"))
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    @Test
    void doesNotGrantPreflightCorsOutsideDossierApi() throws Exception {
        mockMvc.perform(options("/api/v1/other")
                        .header(HttpHeaders.ORIGIN, "http://localhost:5173")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    @Test
    void actualGetStillWorksAndReceivesCorsHeader() throws Exception {
        mockMvc.perform(get("/api/v1/dossiers")
                        .header(HttpHeaders.ORIGIN, "http://localhost:3000"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:3000"));
        assertEquals(1, context.getBeanNamesForType(DevCorsConfig.class).length);
    }

    @RestController
    @Profile("dev")
    static class OtherController {

        @GetMapping("/api/v1/other")
        String getOther() {
            return "other";
        }
    }
}

@WebMvcTest(value = DossierController.class, properties = "debug=false")
@Import({DevCorsConfig.class, ConfirmSecurityConfig.class, ConfirmAuthorizationService.class})
@ActiveProfiles("test")
class DossierCorsWithoutDevProfileTests {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ApplicationContext context;

    @MockitoBean
    DossierService dossierService;

    @Test
    void doesNotEnableCorsWithoutDevProfile() throws Exception {
        assertEquals(0, context.getBeanNamesForType(DevCorsConfig.class).length);
        mockMvc.perform(get("/api/v1/dossiers")
                        .header(HttpHeaders.ORIGIN, "http://localhost:5173"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
        mockMvc.perform(options("/api/v1/dossiers")
                        .header(HttpHeaders.ORIGIN, "http://localhost:5173")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }
}
