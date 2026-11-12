package com.example.retail_loan_document_completeness_checker.services;

import com.example.retail_loan_document_completeness_checker.exceptions.FileValidationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileStorageServiceTests {

    @TempDir
    Path temporaryDirectory;

    @Test
    void storesDocumentWithGeneratedNameAndSha256() throws Exception {
        byte[] content = "%PDF-1.4\nexample".getBytes(StandardCharsets.US_ASCII);
        var file = new MockMultipartFile("files", "statement.pdf", "application/pdf", content);
        var storage = new FileStorageService(temporaryDirectory.toString());

        StoredFile stored = storage.store(file);

        assertThat(stored.originalFileName()).isEqualTo("statement.pdf");
        assertThat(stored.storedFileName()).endsWith(".pdf").doesNotContain("statement");
        assertThat(stored.sha256()).isEqualTo(HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(content)));
        assertThat(Files.readAllBytes(stored.storagePath())).isEqualTo(content);
    }

    @Test
    void rejectsFileWhoseContentDoesNotMatchDeclaredType() {
        var file = new MockMultipartFile(
                "files",
                "fake.pdf",
                "application/pdf",
                "not a pdf".getBytes(StandardCharsets.UTF_8)
        );
        var storage = new FileStorageService(temporaryDirectory.toString());

        assertThatThrownBy(() -> storage.store(file))
                .isInstanceOf(FileValidationException.class)
                .hasMessageContaining("PDF, PNG and JPEG");
    }

    @Test
    void removesAlreadyStoredFilesWhenAFollowingFileIsInvalid() throws Exception {
        var valid = new MockMultipartFile(
                "files",
                "valid.pdf",
                "application/pdf",
                "%PDF-1.4\nexample".getBytes(StandardCharsets.US_ASCII)
        );
        var invalid = new MockMultipartFile(
                "files",
                "invalid.pdf",
                "application/pdf",
                "not a pdf".getBytes(StandardCharsets.UTF_8)
        );
        var storage = new FileStorageService(temporaryDirectory.toString());

        assertThatThrownBy(() -> storage.storeAll(List.of(valid, invalid)))
                .isInstanceOf(FileValidationException.class);
        try (var storedPaths = Files.list(temporaryDirectory)) {
            assertThat(storedPaths).isEmpty();
        }
    }
}
