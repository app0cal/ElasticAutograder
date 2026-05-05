package com.autograder.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

import java.io.IOException;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.ResponseEntity;

import com.autograder.service.submission.SubmissionStorageService;

class UploadFileControllerTest {

    private final SubmissionStorageService submissionStorageService = Mockito.mock(SubmissionStorageService.class);
    private final UploadFileController controller = new UploadFileController(submissionStorageService);

    @Test
    void removeFile_existingFile_deletesFile() throws Exception {
        when(submissionStorageService.exists("remove-me.py")).thenReturn(true);
        when(submissionStorageService.delete("remove-me.py")).thenReturn(true);

        ResponseEntity<String> response = controller.removeFile("remove-me.py");

        assertEquals(200, response.getStatusCode().value());
        assertEquals("Successfully deleted file.", response.getBody());
    }

    @Test
    void removeFile_missingFile_returns404() {
        when(submissionStorageService.exists("missing.py")).thenReturn(false);

        ResponseEntity<String> response = controller.removeFile("missing.py");

        assertEquals(404, response.getStatusCode().value());
        assertEquals("File not found.", response.getBody());
    }

    @Test
    void removeFile_invalidPath_returns400() {
        when(submissionStorageService.exists("../secret.py"))
                .thenThrow(new IllegalArgumentException("Invalid file name."));

        ResponseEntity<String> response = controller.removeFile("../secret.py");

        assertEquals(400, response.getStatusCode().value());
        assertEquals("Invalid file name.", response.getBody());
    }

    @Test
    void removeFile_deleteFailure_returns500() throws Exception {
        when(submissionStorageService.exists("remove-me.py")).thenReturn(true);
        when(submissionStorageService.delete("remove-me.py")).thenThrow(new IOException("locked"));

        ResponseEntity<String> response = controller.removeFile("remove-me.py");

        assertEquals(500, response.getStatusCode().value());
        assertEquals("Unable to delete file.", response.getBody());
    }
}
