package com.autograder.service.submission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.mock.web.MockMultipartFile;

import com.autograder.model.Submission;
import com.autograder.model.SubmissionProject;
import com.autograder.model.SubmissionProjectFile;
import com.autograder.repository.SubmissionProjectFileRepository;
import com.autograder.repository.SubmissionProjectRepository;
import com.autograder.repository.SubmissionRepository;
import com.autograder.service.identity.RequestIdentity;

class DatabaseSubmissionStorageServiceTest {

    private SubmissionRepository submissionRepository;
    private SubmissionProjectRepository submissionProjectRepository;
    private SubmissionProjectFileRepository submissionProjectFileRepository;
    private DatabaseSubmissionStorageService storageService;

    @BeforeEach
    void setUp() {
        submissionRepository = Mockito.mock(SubmissionRepository.class);
        submissionProjectRepository = Mockito.mock(SubmissionProjectRepository.class);
        submissionProjectFileRepository = Mockito.mock(SubmissionProjectFileRepository.class);
        storageService = new DatabaseSubmissionStorageService(
                submissionRepository,
                submissionProjectRepository,
                submissionProjectFileRepository
        );

        when(submissionRepository.save(any(Submission.class))).thenAnswer(invocation -> {
            Submission submission = invocation.getArgument(0);
            setSubmissionId(submission, 11L);
            return submission;
        });
        when(submissionProjectRepository.save(any(SubmissionProject.class))).thenAnswer(invocation -> {
            SubmissionProject project = invocation.getArgument(0);
            setSubmissionProjectId(project, 21L);
            return project;
        });
    }

    @Test
    void storeSingle_validFile_storesSubmissionRow() throws Exception {
        StoredSubmission storedSubmission = storageService.storeSingle(
                file("submission.py", "print('hello')"),
                new RequestIdentity("university-a", "student-1")
        );

        ArgumentCaptor<Submission> savedSubmission = ArgumentCaptor.forClass(Submission.class);
        verify(submissionRepository).save(savedSubmission.capture());
        assertEquals(11L, storedSubmission.submissionId());
        assertTrue(storedSubmission.key().startsWith("db:"));
        assertEquals("submission.py", storedSubmission.originalFileName());
        assertEquals("submission.py", savedSubmission.getValue().getOriginalFilename());
        assertEquals("print('hello')", savedSubmission.getValue().getContent());
        assertEquals("university-a", savedSubmission.getValue().getInstitutionId());
        assertEquals("student-1", savedSubmission.getValue().getSubmittedBy());
    }

    @Test
    void storeZip_validArchive_storesEachSubmissionRow() throws Exception {
        MockMultipartFile zip = zipFile("batch.zip", Map.of(
                "alpha.py", "print('alpha')",
                "nested/beta.py", "print('beta')"
        ));

        List<StoredSubmission> submissions = storageService.storeZip(zip, RequestIdentity.localAnonymous());

        assertEquals(2, submissions.size());
        verify(submissionRepository, Mockito.times(2)).save(any(Submission.class));
    }

    @Test
    void storeZip_duplicateBasenames_rejectsArchive() throws Exception {
        MockMultipartFile zip = zipFile("duplicates.zip", Map.of(
                "first/duplicate.py", "print('a')",
                "second/duplicate.py", "print('b')"
        ));

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> storageService.storeZip(zip, RequestIdentity.localAnonymous())
        );

        assertEquals("Zip archive contains duplicate file names: duplicate.py", exception.getMessage());
    }

    @Test
    void storeZip_emptyArchive_rejectsUpload() throws Exception {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> storageService.storeZip(zipFile("empty.zip", Map.of()), RequestIdentity.localAnonymous())
        );

        assertEquals("Zip archive does not contain any files.", exception.getMessage());
    }

    @Test
    void storeProjectZip_validArchive_storesProjectAndPreservesRelativePaths() throws Exception {
        MockMultipartFile zip = zipFile("project.zip", Map.of(
                "src/Main.java", "class Main {}",
                "src/Fib.java", "class Fib {}",
                "README.md", "notes"
        ));

        StoredProjectSubmission project = storageService.storeProjectZip(
                zip,
                new RequestIdentity("university-a", "student-1")
        );

        ArgumentCaptor<SubmissionProject> savedProject = ArgumentCaptor.forClass(SubmissionProject.class);
        verify(submissionProjectRepository).save(savedProject.capture());
        assertEquals(21L, project.projectId());
        assertTrue(project.key().startsWith("project:"));
        assertEquals("project.zip", project.originalFileName());
        assertEquals(3, project.fileCount());
        assertEquals("university-a", savedProject.getValue().getInstitutionId());
        assertEquals("student-1", savedProject.getValue().getSubmittedBy());

        ArgumentCaptor<List<SubmissionProjectFile>> savedFiles = ArgumentCaptor.forClass(List.class);
        verify(submissionProjectFileRepository).saveAll(savedFiles.capture());
        List<String> paths = savedFiles.getValue().stream()
                .map(SubmissionProjectFile::getRelativePath)
                .sorted()
                .toList();
        assertEquals(List.of("README.md", "src/Fib.java", "src/Main.java"), paths);
    }

    @Test
    void storeProjectZip_duplicateNormalizedPaths_rejectsArchive() throws Exception {
        MockMultipartFile zip = zipFile("project.zip", Map.of(
                "src/Main.java", "class Main {}",
                "src/./Main.java", "class Duplicate {}"
        ));

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> storageService.storeProjectZip(zip, RequestIdentity.localAnonymous())
        );

        assertEquals("Project archive contains duplicate file path: src/Main.java", exception.getMessage());
    }

    @Test
    void storeProjectZip_pathTraversal_rejectsArchive() throws Exception {
        MockMultipartFile zip = zipFile("project.zip", Map.of("../secret.java", "class Secret {}"));

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> storageService.storeProjectZip(zip, RequestIdentity.localAnonymous())
        );

        assertEquals("Zip archive contains an invalid file path.", exception.getMessage());
    }

    @Test
    void storeProjectZip_emptyArchive_rejectsUpload() throws Exception {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> storageService.storeProjectZip(zipFile("project.zip", Map.of()), RequestIdentity.localAnonymous())
        );

        assertEquals("Project archive does not contain any files.", exception.getMessage());
    }

    @Test
    void storeProjectZip_oversizedSingleFile_rejectsUpload() throws Exception {
        String largeContent = "x".repeat(200_001);
        MockMultipartFile zip = zipFile("project.zip", Map.of("src/Main.java", largeContent));

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> storageService.storeProjectZip(zip, RequestIdentity.localAnonymous())
        );

        assertEquals("Project archive contains a file larger than 200000 bytes.", exception.getMessage());
    }

    @Test
    void storeSingle_pathTraversal_rejectsUpload() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> storageService.storeSingle(file("../secret.py", "print('bad')"), RequestIdentity.localAnonymous())
        );

        assertEquals("Invalid file name.", exception.getMessage());
    }

    @Test
    void readSubmission_existingKey_returnsContent() {
        UUID storageKey = UUID.fromString("2ee63863-c9ec-4a1f-8ce9-d4db05cc7a5c");
        Submission submission = new Submission(storageKey, "submission.py", "print('hello')", "text/plain", 14L);
        when(submissionRepository.findByStorageKey(storageKey)).thenReturn(Optional.of(submission));

        assertEquals("print('hello')", storageService.readSubmission("db:" + storageKey));
    }

    @Test
    void exists_missingKey_returnsFalse() {
        UUID storageKey = UUID.fromString("2ee63863-c9ec-4a1f-8ce9-d4db05cc7a5c");
        when(submissionRepository.findByStorageKey(storageKey)).thenReturn(Optional.empty());

        assertFalse(storageService.exists("db:" + storageKey));
    }

    @Test
    void deleteIfExists_existingKey_deletesSubmission() {
        UUID storageKey = UUID.fromString("2ee63863-c9ec-4a1f-8ce9-d4db05cc7a5c");
        Submission submission = new Submission(storageKey, "submission.py", "print('hello')", "text/plain", 14L);
        when(submissionRepository.findByStorageKey(storageKey)).thenReturn(Optional.of(submission));

        storageService.deleteIfExists("db:" + storageKey);

        verify(submissionRepository).delete(submission);
    }

    @Test
    void readProject_existingKey_returnsProjectFiles() {
        UUID storageKey = UUID.fromString("2ee63863-c9ec-4a1f-8ce9-d4db05cc7a5c");
        SubmissionProject project = new SubmissionProject(
                storageKey,
                "project.zip",
                "local",
                "anonymous",
                20L,
                2
        );
        setIdUnchecked(project, 21L);
        when(submissionProjectRepository.findByStorageKey(storageKey)).thenReturn(Optional.of(project));
        when(submissionProjectFileRepository.findByProjectIdOrderByRelativePathAsc(21L)).thenReturn(List.of(
                new SubmissionProjectFile(project, "README.md", "notes", "text/plain", 5L),
                new SubmissionProjectFile(project, "src/Main.java", "class Main {}", "text/plain", 13L)
        ));

        StoredProject storedProject = storageService.readProject("project:" + storageKey);

        assertEquals("project:" + storageKey, storedProject.key());
        assertEquals("project.zip", storedProject.originalFileName());
        assertEquals(2, storedProject.files().size());
        assertEquals("README.md", storedProject.files().get(0).relativePath());
        assertEquals("src/Main.java", storedProject.files().get(1).relativePath());
    }

    private MockMultipartFile file(String name, String contents) {
        return new MockMultipartFile("file", name, "text/plain", contents.getBytes());
    }

    private MockMultipartFile zipFile(String name, Map<String, String> entries) throws Exception {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        try (ZipOutputStream zipOutputStream = new ZipOutputStream(outputStream)) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                zipOutputStream.putNextEntry(new ZipEntry(entry.getKey()));
                zipOutputStream.write(entry.getValue().getBytes());
                zipOutputStream.closeEntry();
            }
        }
        return new MockMultipartFile("file", name, "application/zip", outputStream.toByteArray());
    }

    private void setSubmissionId(Submission submission, Long id) throws Exception {
        Field idField = Submission.class.getDeclaredField("id");
        idField.setAccessible(true);
        idField.set(submission, id);
    }

    private void setSubmissionProjectId(SubmissionProject project, Long id) throws Exception {
        Field idField = SubmissionProject.class.getDeclaredField("id");
        idField.setAccessible(true);
        idField.set(project, id);
    }

    private void setIdUnchecked(SubmissionProject project, Long id) {
        try {
            setSubmissionProjectId(project, id);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
