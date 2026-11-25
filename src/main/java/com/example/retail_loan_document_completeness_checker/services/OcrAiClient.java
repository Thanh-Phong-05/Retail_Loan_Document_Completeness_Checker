package com.example.retail_loan_document_completeness_checker.services;

import com.example.retail_loan_document_completeness_checker.dto.OcrAnalyzeRequest;
import com.example.retail_loan_document_completeness_checker.dto.OcrAnalyzeResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class OcrAiClient {

    private final RestClient restClient;

    public OcrAiClient(
            @Value("${app.ai.base-url:http://localhost:8000}") String baseUrl
    ) {
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .build();
    }

    public OcrAnalyzeResponse analyze(OcrAnalyzeRequest request) {

        OcrAnalyzeResponse response = restClient.post()
                .uri("/api/ai/analyze")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(OcrAnalyzeResponse.class);

        if (response == null) {
            throw new IllegalStateException(
                    "Python AI API returned an empty response"
            );
        }

        return response;
    }
}
