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
import com.autograder.repository.SubmissionRepository;

class DatabaseSubmissionStorageServiceTest {

    private SubmissionRepository submissionRepository;
    private DatabaseSubmissionStorageService storageService;

    @BeforeEach
    void setUp() {
        submissionRepository = Mockito.mock(SubmissionRepository.class);
        storageService = new DatabaseSubmissionStorageService(submissionRepository);

        when(submissionRepository.save(any(Submission.class))).thenAnswer(invocation -> {
            Submission submission = invocation.getArgument(0);
            setSubmissionId(submission, 11L);
            return submission;
        });
    }

    @Test
    void storeSingle_validFile_storesSubmissionRow() throws Exception {
        StoredSubmission storedSubmission = storageService.storeSingle(file("submission.py", "print('hello')"));

        ArgumentCaptor<Submission> savedSubmission = ArgumentCaptor.forClass(Submission.class);
        verify(submissionRepository).save(savedSubmission.capture());
        assertEquals(11L, storedSubmission.submissionId());
        assertTrue(storedSubmission.key().startsWith("db:"));
        assertEquals("submission.py", storedSubmission.originalFileName());
        assertEquals("submission.py", savedSubmission.getValue().getOriginalFilename());
        assertEquals("print('hello')", savedSubmission.getValue().getContent());
    }

    @Test
    void storeZip_validArchive_storesEachSubmissionRow() throws Exception {
        MockMultipartFile zip = zipFile("batch.zip", Map.of(
                "alpha.py", "print('alpha')",
                "nested/beta.py", "print('beta')"
        ));

        List<StoredSubmission> submissions = storageService.storeZip(zip);

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
                () -> storageService.storeZip(zip)
        );

        assertEquals("Zip archive contains duplicate file names: duplicate.py", exception.getMessage());
    }

    @Test
    void storeZip_emptyArchive_rejectsUpload() throws Exception {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> storageService.storeZip(zipFile("empty.zip", Map.of()))
        );

        assertEquals("Zip archive does not contain any files.", exception.getMessage());
    }

    @Test
    void storeSingle_pathTraversal_rejectsUpload() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> storageService.storeSingle(file("../secret.py", "print('bad')"))
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
}
