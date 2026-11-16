package com.example.retail_loan_document_completeness_checker.services;

import com.example.retail_loan_document_completeness_checker.exceptions.FileStorageException;
import com.example.retail_loan_document_completeness_checker.exceptions.FileValidationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.DigestInputStream;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class FileStorageService {

    static final int MAX_FILES = 20;
    static final long MAX_FILE_SIZE = 10L * 1024 * 1024;

    private final Path storageRoot;

    public FileStorageService(@Value("${app.storage.location:uploads}") String storageLocation) {
        storageRoot = Path.of(storageLocation).toAbsolutePath().normalize();
    }

    public List<StoredFile> storeAll(List<MultipartFile> files) {
        if (files == null || files.isEmpty()) {
            throw new FileValidationException("At least one document is required");
        }
        if (files.size() > MAX_FILES) {
            throw new FileValidationException("A dossier may contain at most " + MAX_FILES + " files");
        }

        List<StoredFile> storedFiles = new ArrayList<>();
        try {
            files.forEach(file -> storedFiles.add(store(file)));
            return List.copyOf(storedFiles);
        } catch (RuntimeException exception) {
            deleteAll(storedFiles);
            throw exception;
        }
    }

    StoredFile store(MultipartFile file) {
        validateBasicMetadata(file);

        String fileId = UUID.randomUUID().toString();
        Path temporaryFile = null;
        try {
            Files.createDirectories(storageRoot);
            temporaryFile = Files.createTempFile(storageRoot, "upload-", ".tmp");

            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String detectedContentType;
            try (InputStream raw = new BufferedInputStream(file.getInputStream())) {
                raw.mark(8);
                byte[] header = raw.readNBytes(8);
                raw.reset();
                detectedContentType = detectContentType(header);
                validateDeclaredContentType(file.getContentType(), detectedContentType);

                try (DigestInputStream input = new DigestInputStream(raw, digest)) {
                    Files.copy(input, temporaryFile, StandardCopyOption.REPLACE_EXISTING);
                }
            }

            String storedFileName = fileId + extensionFor(detectedContentType);
            Path destination = storageRoot.resolve(storedFileName).normalize();
            if (!destination.startsWith(storageRoot)) {
                throw new FileValidationException("Invalid destination path");
            }
            try {
                Files.move(temporaryFile, destination, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicMoveFailure) {
                Files.move(temporaryFile, destination);
            }
            temporaryFile = null;

            return new StoredFile(
                    fileId,
                    normalizedOriginalName(file.getOriginalFilename()),
                    storedFileName,
                    destination,
                    detectedContentType,
                    file.getSize(),
                    HexFormat.of().formatHex(digest.digest())
            );
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new FileStorageException("Could not store uploaded document", exception);
        } finally {
            deleteQuietly(temporaryFile);
        }
    }

    public void deleteAll(List<StoredFile> files) {
        files.forEach(file -> deleteQuietly(file.storagePath()));
    }

    private void validateBasicMetadata(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new FileValidationException("Uploaded documents must not be empty");
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new FileValidationException("Each document must not exceed 10 MB");
        }
        normalizedOriginalName(file.getOriginalFilename());
    }

    private String normalizedOriginalName(String originalFilename) {
        if (originalFilename == null || originalFilename.isBlank()) {
            throw new FileValidationException("Each document must have a file name");
        }
        String normalized = originalFilename.replace('\\', '/');
        String fileName = normalized.substring(normalized.lastIndexOf('/') + 1);
        if (fileName.isBlank() || fileName.equals(".") || fileName.equals("..")
                || fileName.length() > 255 || normalized.contains("../")) {
            throw new FileValidationException("Invalid document file name");
        }
        return fileName;
    }

    private String detectContentType(byte[] header) {
        if (startsWith(header, new byte[]{'%', 'P', 'D', 'F', '-'})) {
            return "application/pdf";
        }
        if (startsWith(header, new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A})) {
            return "image/png";
        }
        if (startsWith(header, new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF})) {
            return "image/jpeg";
        }
        throw new FileValidationException("Only PDF, PNG and JPEG documents are supported");
    }

    private boolean startsWith(byte[] value, byte[] prefix) {
        if (value.length < prefix.length) {
            return false;
        }
        for (int index = 0; index < prefix.length; index++) {
            if (value[index] != prefix[index]) {
                return false;
            }
        }
        return true;
    }

    private void validateDeclaredContentType(String declaredContentType, String detectedContentType) {
        if (declaredContentType == null || declaredContentType.isBlank()) {
            return;
        }
        String normalized = declaredContentType.toLowerCase(Locale.ROOT);
        if ("image/jpg".equals(normalized)) {
            normalized = "image/jpeg";
        }
        if (!detectedContentType.equals(normalized)) {
            throw new FileValidationException("Document content does not match its declared content type");
        }
    }

    private String extensionFor(String contentType) {
        return switch (contentType) {
            case "application/pdf" -> ".pdf";
            case "image/png" -> ".png";
            case "image/jpeg" -> ".jpg";
            default -> throw new FileValidationException("Unsupported document type");
        };
    }

    private void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // The original failure is more useful to the caller than cleanup failure.
        }
    }
}
