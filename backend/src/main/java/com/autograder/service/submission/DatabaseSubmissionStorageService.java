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
import com.autograder.model.SubmissionProject;
import com.autograder.model.SubmissionProjectFile;
import com.autograder.repository.SubmissionProjectFileRepository;
import com.autograder.repository.SubmissionProjectRepository;
import com.autograder.repository.SubmissionRepository;
import com.autograder.service.identity.RequestIdentity;

/**
 * Postgres-backed submission storage shared by backend and worker pods.
 */
@Primary
@Service
public class DatabaseSubmissionStorageService implements SubmissionStorageService {

    private static final String STORAGE_KEY_PREFIX = "db:";
    private static final String PROJECT_STORAGE_KEY_PREFIX = "project:";
    private static final String DEFAULT_CONTENT_TYPE = "text/plain";
    private static final int MAX_PROJECT_FILES = 100;
    private static final long MAX_PROJECT_TOTAL_BYTES = 1_000_000;
    private static final long MAX_PROJECT_FILE_BYTES = 200_000;
    private static final int MAX_PROJECT_PATH_DEPTH = 8;

    private final SubmissionRepository submissionRepository;
    private final SubmissionProjectRepository submissionProjectRepository;
    private final SubmissionProjectFileRepository submissionProjectFileRepository;

    public DatabaseSubmissionStorageService(
            SubmissionRepository submissionRepository,
            SubmissionProjectRepository submissionProjectRepository,
            SubmissionProjectFileRepository submissionProjectFileRepository
    ) {
        this.submissionRepository = submissionRepository;
        this.submissionProjectRepository = submissionProjectRepository;
        this.submissionProjectFileRepository = submissionProjectFileRepository;
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
    public StoredSubmission storeSingle(MultipartFile file, RequestIdentity identity) throws IOException {
        String fileName = sanitizeFileName(file.getOriginalFilename());
        return saveSubmission(fileName, file.getContentType(), file.getBytes(), identity);
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
    public List<StoredSubmission> storeZip(MultipartFile file, RequestIdentity identity) throws IOException {
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

                submissions.add(saveSubmission(baseName, DEFAULT_CONTENT_TYPE, readEntryBytes(zipInputStream), identity));
                zipInputStream.closeEntry();
            }
        }

        if (submissions.isEmpty()) {
            throw new IllegalArgumentException("Zip archive does not contain any files.");
        }

        return submissions;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public StoredProjectSubmission storeProjectZip(MultipartFile file, RequestIdentity identity) throws IOException {
        String originalFileName = sanitizeFileName(file.getOriginalFilename());
        List<ProjectFileEntry> files = readProjectZipEntries(file);
        long totalSizeBytes = files.stream().mapToLong(ProjectFileEntry::sizeBytes).sum();
        UUID storageKey = UUID.randomUUID();

        SubmissionProject project = new SubmissionProject(
                storageKey,
                originalFileName,
                identity.institution(),
                identity.user(),
                totalSizeBytes,
                files.size()
        );
        SubmissionProject savedProject = submissionProjectRepository.save(project);

        List<SubmissionProjectFile> projectFiles = files.stream()
                .map(projectFile -> new SubmissionProjectFile(
                        savedProject,
                        projectFile.relativePath(),
                        projectFile.content(),
                        DEFAULT_CONTENT_TYPE,
                        projectFile.sizeBytes()
                ))
                .toList();
        submissionProjectFileRepository.saveAll(projectFiles);

        return new StoredProjectSubmission(
                savedProject.getId(),
                PROJECT_STORAGE_KEY_PREFIX + storageKey,
                originalFileName,
                files.size()
        );
    }

    @Override
    public StoredProject readProject(String projectKey) {
        UUID storageKey = parseProjectStorageKey(projectKey);
        SubmissionProject project = submissionProjectRepository.findByStorageKey(storageKey)
                .orElseThrow(() -> new IllegalArgumentException("Project submission not found: " + projectKey));

        List<StoredProjectFile> files = submissionProjectFileRepository.findByProjectIdOrderByRelativePathAsc(project.getId())
                .stream()
                .map(projectFile -> new StoredProjectFile(
                        projectFile.getRelativePath(),
                        projectFile.getContent(),
                        projectFile.getContentType(),
                        projectFile.getSizeBytes()
                ))
                .toList();

        return new StoredProject(PROJECT_STORAGE_KEY_PREFIX + storageKey, project.getOriginalFilename(), files);
    }

    @Override
    @Transactional
    public boolean deleteProject(String projectKey) {
        UUID storageKey = parseProjectStorageKey(projectKey);
        return submissionProjectRepository.findByStorageKey(storageKey)
                .map(project -> {
                    submissionProjectFileRepository.deleteByProjectId(project.getId());
                    submissionProjectRepository.delete(project);
                    return true;
                })
                .orElse(false);
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
        if (isProjectStorageKey(submissionKey)) {
            deleteProject(submissionKey);
            return;
        }

        delete(submissionKey);
    }

    private List<ProjectFileEntry> readProjectZipEntries(MultipartFile file) throws IOException {
        List<ProjectFileEntry> files = new ArrayList<>();
        Set<String> seenPaths = new LinkedHashSet<>();
        long totalBytes = 0;

        try (ZipInputStream zipInputStream = new ZipInputStream(file.getInputStream())) {
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

                byte[] bytes = readEntryBytes(zipInputStream);
                if (bytes.length > MAX_PROJECT_FILE_BYTES) {
                    throw new IllegalArgumentException("Project archive contains a file larger than 200000 bytes.");
                }

                totalBytes += bytes.length;
                if (totalBytes > MAX_PROJECT_TOTAL_BYTES) {
                    throw new IllegalArgumentException("Project archive is larger than 1000000 bytes.");
                }

                files.add(new ProjectFileEntry(
                        relativePath,
                        new String(bytes, StandardCharsets.UTF_8),
                        bytes.length
                ));
                if (files.size() > MAX_PROJECT_FILES) {
                    throw new IllegalArgumentException("Project archive contains more than 100 files.");
                }

                zipInputStream.closeEntry();
            }
        }

        if (files.isEmpty()) {
            throw new IllegalArgumentException("Project archive does not contain any files.");
        }

        return files;
    }

    private StoredSubmission saveSubmission(
            String originalFileName,
            String contentType,
            byte[] bytes,
            RequestIdentity identity
    ) {
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
                (long) bytes.length,
                identity.institution(),
                identity.user()
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

    private String sanitizeProjectZipEntryPath(String rawEntryName) {
        Path normalized = sanitizeZipEntryName(rawEntryName);
        if (normalized.getNameCount() > MAX_PROJECT_PATH_DEPTH) {
            throw new IllegalArgumentException("Project archive contains a path deeper than 8 levels.");
        }

        return normalized.toString().replace('\\', '/');
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

    private UUID parseProjectStorageKey(String rawStorageKey) {
        String cleaned = stripJsonStringQuotes(rawStorageKey.trim());
        if (cleaned.startsWith(PROJECT_STORAGE_KEY_PREFIX)) {
            cleaned = cleaned.substring(PROJECT_STORAGE_KEY_PREFIX.length());
        }

        try {
            return UUID.fromString(cleaned);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid project submission key.");
        }
    }

    private boolean isProjectStorageKey(String submissionKey) {
        if (submissionKey == null) {
            return false;
        }

        return stripJsonStringQuotes(submissionKey.trim()).startsWith(PROJECT_STORAGE_KEY_PREFIX);
    }

    private String stripJsonStringQuotes(String cleaned) {
        if (cleaned.startsWith("\"") && cleaned.endsWith("\"") && cleaned.length() >= 2) {
            return cleaned.substring(1, cleaned.length() - 1).trim();
        }

        return cleaned;
    }

    private record ProjectFileEntry(String relativePath, String content, long sizeBytes) {
    }
}
