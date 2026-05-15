package com.autograder.service.submission;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.autograder.service.identity.RequestIdentity;

/**
 * Local filesystem implementation for staged submission storage.
 *
 * This keeps all upload path validation and cleanup rules in one place so the
 * rest of the backend works with logical submission keys.
 */
@Service
public class LocalSubmissionStorageService implements SubmissionStorageService {

    private static final Path UPLOAD_ROOT = Path.of("grading/uploads");
    private static final String PROJECT_STORAGE_KEY_PREFIX = "project-local:";
    private static final int MAX_PROJECT_FILES = 100;
    private static final long MAX_PROJECT_TOTAL_BYTES = 1_000_000;
    private static final long MAX_PROJECT_FILE_BYTES = 200_000;
    private static final int MAX_PROJECT_PATH_DEPTH = 8;

    /**
     * Stores a single file under the upload root using a sanitized base name.
     *
     * @param file uploaded submission file
     * @return logical reference to the staged file
     * @throws IOException if the upload directory or file cannot be written
     */
    @Override
    public StoredSubmission storeSingle(MultipartFile file, RequestIdentity identity) throws IOException {
        ensureUploadRootExists();

        String fileName = sanitizeFileName(file.getOriginalFilename());
        Path filePath = resolveUploadPath(fileName);

        if (Files.exists(filePath)) {
            throw new IllegalArgumentException("File with this name already exists.");
        }

        Files.write(filePath, file.getBytes(), StandardOpenOption.CREATE_NEW);
        return new StoredSubmission(fileName, fileName);
    }

    /**
     * Extracts a zip archive into an isolated batch directory.
     *
     * Duplicate base file names are rejected because the frontend presents only
     * the base name when showing created jobs.
     *
     * @param file uploaded zip archive
     * @return logical references to extracted submissions
     * @throws IOException if archive extraction fails
     */
    @Override
    public List<StoredSubmission> storeZip(MultipartFile file, RequestIdentity identity) throws IOException {
        ensureUploadRootExists();

        Path batchDirectory = createZipUploadDirectory(file.getOriginalFilename());
        List<Path> extractedFiles = new ArrayList<>();

        try (InputStream inputStream = file.getInputStream();
             ZipInputStream zipInputStream = new ZipInputStream(inputStream)) {
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

                Path extractedPath = batchDirectory.resolve(relativeEntryPath).normalize();
                if (!extractedPath.startsWith(batchDirectory)) {
                    throw new IllegalArgumentException("Zip archive contains an invalid file path.");
                }

                Files.createDirectories(extractedPath.getParent());
                Files.copy(zipInputStream, extractedPath);
                extractedFiles.add(extractedPath);
                zipInputStream.closeEntry();
            }

            if (extractedFiles.isEmpty()) {
                throw new IllegalArgumentException("Zip archive does not contain any files.");
            }

            return extractedFiles.stream()
                    .map(this::toStoredSubmission)
                    .toList();
        } catch (IOException | RuntimeException e) {
            cleanupDirectory(batchDirectory);
            throw e;
        }
    }

    @Override
    public StoredProjectSubmission storeProjectZip(MultipartFile file, RequestIdentity identity) throws IOException {
        ensureUploadRootExists();

        String zipFileName = sanitizeFileName(file.getOriginalFilename());
        Path projectDirectory = createZipUploadDirectory(zipFileName);
        int fileCount = 0;
        long totalBytes = 0;

        try (InputStream inputStream = file.getInputStream();
             ZipInputStream zipInputStream = new ZipInputStream(inputStream)) {
            Set<String> seenPaths = new LinkedHashSet<>();
            ZipEntry entry;

            while ((entry = zipInputStream.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    zipInputStream.closeEntry();
                    continue;
                }

                String relativePath = sanitizeProjectZipEntryPath(entry.getName());
                if (!seenPaths.add(relativePath)) {
                    throw new IllegalArgumentException("Project archive contains duplicate file path: " + relativePath);
                }

                byte[] bytes = zipInputStream.readAllBytes();
                if (bytes.length > MAX_PROJECT_FILE_BYTES) {
                    throw new IllegalArgumentException("Project archive contains a file larger than 200000 bytes.");
                }

                totalBytes += bytes.length;
                if (totalBytes > MAX_PROJECT_TOTAL_BYTES) {
                    throw new IllegalArgumentException("Project archive is larger than 1000000 bytes.");
                }

                Path extractedPath = projectDirectory.resolve(relativePath).normalize();
                if (!extractedPath.startsWith(projectDirectory)) {
                    throw new IllegalArgumentException("Zip archive contains an invalid file path.");
                }

                Files.createDirectories(extractedPath.getParent());
                Files.write(extractedPath, bytes, StandardOpenOption.CREATE_NEW);
                fileCount++;
                if (fileCount > MAX_PROJECT_FILES) {
                    throw new IllegalArgumentException("Project archive contains more than 100 files.");
                }

                zipInputStream.closeEntry();
            }

            if (fileCount == 0) {
                throw new IllegalArgumentException("Project archive does not contain any files.");
            }

            String key = PROJECT_STORAGE_KEY_PREFIX + UPLOAD_ROOT.relativize(projectDirectory).toString().replace('\\', '/');
            return new StoredProjectSubmission(null, key, zipFileName, fileCount);
        } catch (IOException | RuntimeException e) {
            cleanupDirectory(projectDirectory);
            throw e;
        }
    }

    @Override
    public StoredProject readProject(String projectKey) throws IOException {
        String projectPath = sanitizeLocalProjectKey(projectKey);
        Path projectDirectory = resolveUploadPath(projectPath);
        if (!Files.isDirectory(projectDirectory)) {
            throw new IllegalArgumentException("Project submission not found: " + projectKey);
        }

        List<StoredProjectFile> files;
        try (var walk = Files.walk(projectDirectory)) {
            files = walk
                    .filter(Files::isRegularFile)
                    .sorted()
                    .map(filePath -> toStoredProjectFile(projectDirectory, filePath))
                    .toList();
        }

        return new StoredProject(projectKey, projectDirectory.getFileName().toString(), files);
    }

    @Override
    public boolean deleteProject(String projectKey) throws IOException {
        String projectPath = sanitizeLocalProjectKey(projectKey);
        Path projectDirectory = resolveUploadPath(projectPath);
        if (!Files.exists(projectDirectory)) {
            return false;
        }

        cleanupDirectory(projectDirectory);
        return true;
    }

    @Override
    public boolean isZipUpload(MultipartFile file) {
        String originalFileName = file.getOriginalFilename();
        return originalFileName != null && originalFileName.toLowerCase().endsWith(".zip");
    }

    /**
     * Chooses the persisted submission key when available, otherwise falls back
     * to the legacy request body sent by the frontend.
     *
     * @param storedKey persisted job submission key
     * @param fallbackRawRequestBody raw request body from older clients
     * @return sanitized logical submission key
     */
    @Override
    public String resolveSubmissionKey(String storedKey, String fallbackRawRequestBody) {
        if (storedKey != null && !storedKey.isBlank()) {
            return sanitizeSubmissionKey(storedKey);
        }

        return sanitizeSubmissionKey(fallbackRawRequestBody);
    }

    /**
     * Sanitizes a storage-relative submission key.
     *
     * This allows nested zip paths but rejects absolute paths and traversal
     * outside the configured upload root.
     *
     * @param rawPath raw submission key or quoted request body
     * @return normalized storage-relative key
     */
    @Override
    public String sanitizeSubmissionKey(String rawPath) {
        if (rawPath == null) {
            throw new IllegalArgumentException("File name is required.");
        }

        String cleaned = stripJsonStringQuotes(rawPath.trim());

        if (cleaned.isBlank()) {
            throw new IllegalArgumentException("File name is required.");
        }

        cleaned = cleaned.replace('\\', '/');
        Path normalized = Path.of(cleaned).normalize();

        if (normalized.isAbsolute() || normalized.startsWith("..")) {
            throw new IllegalArgumentException("Invalid file name.");
        }

        String normalizedString = normalized.toString().replace('\\', '/');
        if (normalizedString.isBlank() || normalizedString.equals(".")) {
            throw new IllegalArgumentException("File name is required.");
        }

        return normalizedString;
    }

    @Override
    public String readSubmission(String submissionKey) throws IOException {
        String cleanedKey = sanitizeSubmissionKey(submissionKey);
        Path submissionPath = resolveUploadPath(cleanedKey);

        if (!Files.exists(submissionPath)) {
            throw new IllegalArgumentException("Submission file not found: " + cleanedKey);
        }

        return Files.readString(submissionPath);
    }

    @Override
    public boolean exists(String submissionKey) {
        return Files.exists(resolveUploadPath(submissionKey));
    }

    /**
     * Deletes a staged submission and prunes empty batch directories.
     *
     * @param submissionKey logical submission key to remove
     * @return true if the file existed and was deleted
     * @throws IOException if deletion fails
     */
    @Override
    public boolean delete(String submissionKey) throws IOException {
        Path filePath = resolveUploadPath(submissionKey);
        boolean deleted = Files.deleteIfExists(filePath);
        if (deleted) {
            deleteEmptyUploadParents(filePath);
        }
        return deleted;
    }

    @Override
    public void deleteIfExists(String submissionKey) throws IOException {
        if (isLocalProjectKey(submissionKey)) {
            deleteProject(submissionKey);
            return;
        }

        delete(submissionKey);
    }

    private void ensureUploadRootExists() throws IOException {
        if (!Files.exists(UPLOAD_ROOT)) {
            Files.createDirectories(UPLOAD_ROOT);
        }
    }

    private StoredSubmission toStoredSubmission(Path extractedFile) {
        String key = UPLOAD_ROOT.relativize(extractedFile).toString().replace('\\', '/');
        return new StoredSubmission(key, extractedFile.getFileName().toString());
    }

    private Path createZipUploadDirectory(String rawZipFileName) throws IOException {
        String zipFileName = sanitizeFileName(rawZipFileName);
        String lowerZipFileName = zipFileName.toLowerCase();
        int extensionStart = lowerZipFileName.lastIndexOf(".zip");
        String baseName = zipFileName.substring(0, extensionStart);
        String extension = zipFileName.substring(extensionStart);

        int suffix = 1;
        while (true) {
            String candidateName = suffix == 1
                    ? zipFileName
                    : baseName + "-" + suffix + extension;
            Path candidateDirectory = resolveUploadPath(candidateName);

            try {
                Files.createDirectory(candidateDirectory);
                return candidateDirectory;
            } catch (FileAlreadyExistsException e) {
                suffix++;
            }
        }
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

    private String sanitizeProjectZipEntryPath(String rawEntryName) {
        Path normalized = sanitizeZipEntryName(rawEntryName);
        if (normalized.getNameCount() > MAX_PROJECT_PATH_DEPTH) {
            throw new IllegalArgumentException("Project archive contains a path deeper than 8 levels.");
        }

        return normalized.toString().replace('\\', '/');
    }

    private StoredProjectFile toStoredProjectFile(Path projectDirectory, Path filePath) {
        try {
            String relativePath = projectDirectory.relativize(filePath).toString().replace('\\', '/');
            byte[] bytes = Files.readAllBytes(filePath);
            return new StoredProjectFile(
                    relativePath,
                    new String(bytes, StandardCharsets.UTF_8),
                    "text/plain",
                    bytes.length
            );
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read project file: " + filePath, e);
        }
    }

    private String sanitizeLocalProjectKey(String projectKey) {
        if (!isLocalProjectKey(projectKey)) {
            throw new IllegalArgumentException("Invalid project submission key.");
        }

        return sanitizeSubmissionKey(stripJsonStringQuotes(projectKey.trim()).substring(PROJECT_STORAGE_KEY_PREFIX.length()));
    }

    private boolean isLocalProjectKey(String projectKey) {
        if (projectKey == null) {
            return false;
        }

        return stripJsonStringQuotes(projectKey.trim()).startsWith(PROJECT_STORAGE_KEY_PREFIX);
    }

    private Path resolveUploadPath(String rawRelativePath) {
        String cleanedPath = sanitizeSubmissionKey(rawRelativePath);
        Path resolvedPath = UPLOAD_ROOT.resolve(cleanedPath).normalize();
        if (!resolvedPath.startsWith(UPLOAD_ROOT)) {
            throw new IllegalArgumentException("Invalid file name.");
        }
        return resolvedPath;
    }

    private void cleanupDirectory(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }

        try (var walk = Files.walk(directory)) {
            walk.sorted((left, right) -> right.compareTo(left))
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException ignored) {
                            // Best-effort cleanup for failed zip uploads.
                        }
                    });
        }
    }

    private void deleteEmptyUploadParents(Path filePath) throws IOException {
        Path parent = filePath.getParent();
        while (parent != null && !parent.equals(UPLOAD_ROOT)) {
            if (!Files.isDirectory(parent)) {
                break;
            }

            try (var children = Files.list(parent)) {
                if (children.findAny().isPresent()) {
                    break;
                }
            }

            Files.deleteIfExists(parent);
            parent = parent.getParent();
        }
    }

    private String stripJsonStringQuotes(String cleaned) {
        if (cleaned.startsWith("\"") && cleaned.endsWith("\"") && cleaned.length() >= 2) {
            return cleaned.substring(1, cleaned.length() - 1).trim();
        }

        return cleaned;
    }
}
