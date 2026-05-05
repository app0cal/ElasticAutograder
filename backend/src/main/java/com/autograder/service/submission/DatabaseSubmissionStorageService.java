package com.autograder.service.submission;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.autograder.model.Submission;
import com.autograder.repository.SubmissionRepository;

/**
 * Postgres-backed submission storage shared by backend and worker pods.
 */
@Primary
@Service
public class DatabaseSubmissionStorageService implements SubmissionStorageService {

    private static final String STORAGE_KEY_PREFIX = "db:";
    private static final String DEFAULT_CONTENT_TYPE = "text/plain";

    private final SubmissionRepository submissionRepository;

    public DatabaseSubmissionStorageService(SubmissionRepository submissionRepository) {
        this.submissionRepository = submissionRepository;
    }

    /**
     * Stores a single uploaded text submission as a durable database row.
     *
     * @param file uploaded submission file
     * @return durable submission reference
     * @throws IOException if uploaded bytes cannot be read
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public StoredSubmission storeSingle(MultipartFile file) throws IOException {
        String fileName = sanitizeFileName(file.getOriginalFilename());
        return saveSubmission(fileName, file.getContentType(), file.getBytes());
    }

    /**
     * Extracts a zip upload and stores each file entry as its own submission row.
     *
     * @param file uploaded zip archive
     * @return durable references for all extracted submissions
     * @throws IOException if the archive cannot be read
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<StoredSubmission> storeZip(MultipartFile file) throws IOException {
        List<StoredSubmission> submissions = new ArrayList<>();

        try (ZipInputStream zipInputStream = new ZipInputStream(file.getInputStream())) {
            Set<String> seenBasenames = new LinkedHashSet<>();
            ZipEntry entry;

            while ((entry = zipInputStream.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    zipInputStream.closeEntry();
                    continue;
                }

                Path relativeEntryPath = sanitizeZipEntryName(entry.getName());
                String baseName = relativeEntryPath.getFileName().toString();

                if (!seenBasenames.add(baseName)) {
                    throw new IllegalArgumentException("Zip archive contains duplicate file names: " + baseName);
                }

                submissions.add(saveSubmission(baseName, DEFAULT_CONTENT_TYPE, readEntryBytes(zipInputStream)));
                zipInputStream.closeEntry();
            }
        }

        if (submissions.isEmpty()) {
            throw new IllegalArgumentException("Zip archive does not contain any files.");
        }

        return submissions;
    }

    @Override
    public boolean isZipUpload(MultipartFile file) {
        String originalFileName = file.getOriginalFilename();
        return originalFileName != null && originalFileName.toLowerCase().endsWith(".zip");
    }

    /**
     * Chooses the persisted durable key when available, otherwise falls back to
     * the legacy request body sent by older clients.
     */
    @Override
    public String resolveSubmissionKey(String storedKey, String fallbackRawRequestBody) {
        if (storedKey != null && !storedKey.isBlank()) {
            return sanitizeSubmissionKey(storedKey);
        }

        return sanitizeSubmissionKey(fallbackRawRequestBody);
    }

    /**
     * Normalizes database storage keys as db-prefixed UUID strings.
     */
    @Override
    public String sanitizeSubmissionKey(String rawPath) {
        if (rawPath == null) {
            throw new IllegalArgumentException("Submission key is required.");
        }

        String cleaned = stripJsonStringQuotes(rawPath.trim());
        if (cleaned.isBlank()) {
            throw new IllegalArgumentException("Submission key is required.");
        }

        UUID storageKey = parseStorageKey(cleaned);
        return STORAGE_KEY_PREFIX + storageKey;
    }

    @Override
    public String readSubmission(String submissionKey) {
        UUID storageKey = parseStorageKey(sanitizeSubmissionKey(submissionKey));
        return submissionRepository.findByStorageKey(storageKey)
                .map(Submission::getContent)
                .orElseThrow(() -> new IllegalArgumentException("Submission file not found: " + submissionKey));
    }

    @Override
    public boolean exists(String submissionKey) {
        UUID storageKey = parseStorageKey(sanitizeSubmissionKey(submissionKey));
        return submissionRepository.findByStorageKey(storageKey).isPresent();
    }

    @Override
    @Transactional
    public boolean delete(String submissionKey) {
        UUID storageKey = parseStorageKey(sanitizeSubmissionKey(submissionKey));
        return submissionRepository.findByStorageKey(storageKey)
                .map(submission -> {
                    submissionRepository.delete(submission);
                    return true;
                })
                .orElse(false);
    }

    @Override
    public void deleteIfExists(String submissionKey) {
        delete(submissionKey);
    }

    private StoredSubmission saveSubmission(String originalFileName, String contentType, byte[] bytes) {
        UUID storageKey = UUID.randomUUID();
        String content = new String(bytes, StandardCharsets.UTF_8);
        String resolvedContentType = contentType == null || contentType.isBlank()
                ? DEFAULT_CONTENT_TYPE
                : contentType;

        Submission submission = new Submission(
                storageKey,
                originalFileName,
                content,
                resolvedContentType,
                (long) bytes.length
        );
        Submission savedSubmission = submissionRepository.save(submission);
        return new StoredSubmission(savedSubmission.getId(), STORAGE_KEY_PREFIX + storageKey, originalFileName);
    }

    private byte[] readEntryBytes(ZipInputStream zipInputStream) throws IOException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        zipInputStream.transferTo(outputStream);
        return outputStream.toByteArray();
    }

    private String sanitizeFileName(String rawFileName) {
        if (rawFileName == null) {
            throw new IllegalArgumentException("File name is required.");
        }

        String cleaned = stripJsonStringQuotes(rawFileName.trim());
        if (cleaned.isBlank()) {
            throw new IllegalArgumentException("File name is required.");
        }

        if (cleaned.contains("..") || cleaned.contains("/") || cleaned.contains("\\")) {
            throw new IllegalArgumentException("Invalid file name.");
        }

        return cleaned;
    }

    private Path sanitizeZipEntryName(String rawEntryName) {
        if (rawEntryName == null || rawEntryName.isBlank()) {
            throw new IllegalArgumentException("Zip archive contains an invalid file path.");
        }

        String cleanedEntry = rawEntryName.replace('\\', '/');
        Path normalized = Path.of(cleanedEntry).normalize();

        if (normalized.isAbsolute() || normalized.startsWith("..")) {
            throw new IllegalArgumentException("Zip archive contains an invalid file path.");
        }

        if (normalized.getFileName() == null) {
            throw new IllegalArgumentException("Zip archive contains an invalid file path.");
        }

        return normalized;
    }

    private UUID parseStorageKey(String rawStorageKey) {
        String cleaned = stripJsonStringQuotes(rawStorageKey.trim());
        if (cleaned.startsWith(STORAGE_KEY_PREFIX)) {
            cleaned = cleaned.substring(STORAGE_KEY_PREFIX.length());
        }

        try {
            return UUID.fromString(cleaned);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid submission key.");
        }
    }

    private String stripJsonStringQuotes(String cleaned) {
        if (cleaned.startsWith("\"") && cleaned.endsWith("\"") && cleaned.length() >= 2) {
            return cleaned.substring(1, cleaned.length() - 1).trim();
        }

        return cleaned;
    }
}
