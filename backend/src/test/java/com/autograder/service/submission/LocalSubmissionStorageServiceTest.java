package com.autograder.service.submission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import com.autograder.service.identity.RequestIdentity;

class LocalSubmissionStorageServiceTest {

    private final LocalSubmissionStorageService storageService = new LocalSubmissionStorageService();
    private final Path uploadDir = Path.of("grading/uploads");

    @BeforeEach
    void cleanUploads() throws Exception {
        if (Files.exists(uploadDir)) {
            try (var walk = Files.walk(uploadDir)) {
                walk.sorted(Comparator.reverseOrder())
                        .forEach(path -> {
                            try {
                                Files.delete(path);
                            } catch (Exception ignored) {
                            }
                        });
            }
        }
    }

    @Test
    void storeSingle_validFile_storesSubmission() throws Exception {
        StoredSubmission submission = storageService.storeSingle(file("submission.py", "print('hello')"), identity());

        assertEquals("submission.py", submission.key());
        assertEquals("submission.py", submission.originalFileName());
        assertTrue(Files.exists(uploadDir.resolve("submission.py")));
    }

    @Test
    void storeSingle_duplicateFile_rejectsUpload() throws Exception {
        storageService.storeSingle(file("duplicate.py", "print('hello')"), identity());

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> storageService.storeSingle(file("duplicate.py", "print('again')"), identity())
        );

        assertEquals("File with this name already exists.", exception.getMessage());
    }

    @Test
    void storeSingle_pathTraversal_rejectsUpload() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> storageService.storeSingle(file("../secret.py", "print('bad')"), identity())
        );

        assertEquals("Invalid file name.", exception.getMessage());
    }

    @Test
    void storeZip_validArchive_extractsSubmissions() throws Exception {
        MockMultipartFile zip = zipFile("batch.zip", Map.of(
                "alpha.py", "print('alpha')",
                "nested/beta.py", "print('beta')"
        ));

        List<StoredSubmission> submissions = storageService.storeZip(zip, identity());

        List<String> keys = submissions.stream().map(StoredSubmission::key).sorted().toList();
        assertEquals(List.of("batch.zip/alpha.py", "batch.zip/nested/beta.py"), keys);
        assertTrue(Files.exists(uploadDir.resolve("batch.zip/alpha.py")));
        assertTrue(Files.exists(uploadDir.resolve("batch.zip/nested/beta.py")));
    }

    @Test
    void storeZip_duplicateBasenames_rejectsArchiveAndCleansDirectory() throws Exception {
        MockMultipartFile zip = zipFile("duplicates.zip", Map.of(
                "first/duplicate.py", "print('a')",
                "second/duplicate.py", "print('b')"
        ));

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> storageService.storeZip(zip, identity())
        );

        assertEquals("Zip archive contains duplicate file names: duplicate.py", exception.getMessage());
        assertFalse(Files.exists(uploadDir.resolve("duplicates.zip")));
    }

    @Test
    void storeZip_emptyArchive_rejectsUpload() throws Exception {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> storageService.storeZip(zipFile("empty.zip", Map.of()), identity())
        );

        assertEquals("Zip archive does not contain any files.", exception.getMessage());
    }

    @Test
    void storeZip_pathTraversal_rejectsArchive() throws Exception {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> storageService.storeZip(zipFile("unsafe.zip", Map.of("../secret.py", "print('bad')")), identity())
        );

        assertEquals("Zip archive contains an invalid file path.", exception.getMessage());
    }

    @Test
    void delete_existingNestedFile_prunesEmptyParents() throws Exception {
        Files.createDirectories(uploadDir.resolve("batch.zip/nested"));
        Files.writeString(uploadDir.resolve("batch.zip/nested/submission.py"), "print('hello')");

        assertTrue(storageService.delete("batch.zip/nested/submission.py"));

        assertFalse(Files.exists(uploadDir.resolve("batch.zip/nested/submission.py")));
        assertFalse(Files.exists(uploadDir.resolve("batch.zip/nested")));
        assertFalse(Files.exists(uploadDir.resolve("batch.zip")));
    }

    private MockMultipartFile file(String name, String contents) {
        return new MockMultipartFile("file", name, "text/plain", contents.getBytes());
    }

    private RequestIdentity identity() {
        return RequestIdentity.localAnonymous();
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
}
