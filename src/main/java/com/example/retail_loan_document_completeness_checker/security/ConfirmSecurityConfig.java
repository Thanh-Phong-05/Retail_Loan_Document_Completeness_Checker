package com.example.retail_loan_document_completeness_checker.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.util.matcher.RequestMatcher;

import java.io.IOException;

@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
public class ConfirmSecurityConfig {

    private static final RequestMatcher CONFIRM_ROUTES = request ->
            (HttpMethod.POST.matches(request.getMethod())
                    && requestPath(request).matches("/api/v1/dossiers/[^/]+/confirm"))
                    || (HttpMethod.GET.matches(request.getMethod())
                    && requestPath(request).matches("/api/v1/dossiers/[^/]+/confirm-operations/[^/]+"));

    @Bean
    SecurityFilterChain confirmSecurityFilterChain(HttpSecurity http) throws Exception {
        http.securityMatcher("/api/v1/**")
                .cors(Customizer.withDefaults())
                .authorizeHttpRequests(requests -> requests.anyRequest().permitAll())
                // Preserve the existing CSRF boundary: Confirm POST, not the previously unfiltered upload API.
                .csrf(csrf -> csrf.requireCsrfProtectionMatcher(request ->
                        HttpMethod.POST.matches(request.getMethod()) && CONFIRM_ROUTES.matches(request)))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, exception) -> sendProblem(response,
                                HttpStatus.UNAUTHORIZED, "Confirmation authentication required",
                                "CONFIRM_AUTHENTICATION_REQUIRED"))
                        .accessDeniedHandler((request, response, exception) -> sendProblem(response,
                                HttpStatus.FORBIDDEN, "Confirmation access denied",
                                "CONFIRM_ACCESS_DENIED")))
                .formLogin(login -> login.disable())
                .httpBasic(basic -> basic.disable());
        // CSRF remains enabled for this POST while the future authentication mechanism is undecided.
        return http.build();
    }

    @Bean
    UserDetailsService noProductionUsers() {
        // Suppress Boot's generated development password; no login provider has been approved.
        return username -> { throw new UsernameNotFoundException("No production users are configured"); };
    }

    private static String requestPath(HttpServletRequest request) {
        return request.getRequestURI().substring(request.getContextPath().length());
    }

    private static void sendProblem(HttpServletResponse response, HttpStatus status,
                                    String title, String code) throws IOException {
        // Values are fixed constants; no caller-controlled text is written into this JSON.
        response.setStatus(status.value());
        response.setCharacterEncoding("UTF-8");
        response.setContentType("application/problem+json");
        response.getWriter().write("{\"type\":\"about:blank\",\"title\":\"" + title
                + "\",\"status\":" + status.value() + ",\"detail\":\"" + title
                + "\",\"code\":\"" + code + "\"}");
    }
}
