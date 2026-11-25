
package com.example.retail_loan_document_completeness_checker;

import com.example.retail_loan_document_completeness_checker.dto.OcrAnalyzeRequest;
import com.example.retail_loan_document_completeness_checker.dto.OcrFileRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

class OcrRequestTest {

    @Test
    void shouldConvertOcrRequestToJson() throws Exception {

        OcrFileRequest file = new OcrFileRequest(
                "FILE001",
                "cccd.pdf",
                "uploads/APP001/cccd.pdf"
        );

        OcrAnalyzeRequest request = new OcrAnalyzeRequest(
                "APP001",
                1,
                List.of(file)
        );

        ObjectMapper objectMapper = new ObjectMapper();

        String json = objectMapper
                .writerWithDefaultPrettyPrinter()
                .writeValueAsString(request);

        System.out.println(json);
    }
}
