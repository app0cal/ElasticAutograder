package com.autograder.controller;

import java.io.IOException;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.autograder.service.submission.SubmissionStorageService;

/**
 * REST controller for manual cleanup of staged upload files.
 */
@CrossOrigin(origins = "http://localhost:5173/")
@RestController
@RequestMapping("/api")
public class UploadFileController {

    private final SubmissionStorageService submissionStorageService;

    public UploadFileController(SubmissionStorageService submissionStorageService) {
        this.submissionStorageService = submissionStorageService;
    }

    /**
     * Deletes a staged upload by logical submission key.
     *
     * @param fileName raw request body containing the file to remove
     * @return OK/Error response depending on whether deletion succeeded
     */
    @DeleteMapping("/files/remove")
    public ResponseEntity<String> removeFile(@RequestBody String fileName) {
        try {
            if (!submissionStorageService.exists(fileName)) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body("File not found.");
            }

            submissionStorageService.delete(fileName);
            return ResponseEntity.ok("Successfully deleted file.");
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(e.getMessage());
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Unable to delete file.");
        }
    }
}
