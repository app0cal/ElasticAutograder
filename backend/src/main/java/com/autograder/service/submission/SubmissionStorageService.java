package com.autograder.service.submission;

import java.io.IOException;
import java.util.List;

import org.springframework.web.multipart.MultipartFile;

import com.autograder.service.identity.RequestIdentity;

/**
 * Storage boundary for staged submissions.
 *
 * Implementations can use local disk, shared storage, or another backend
 * without changing controllers or job lifecycle services.
 */
public interface SubmissionStorageService {

    /**
     * Stores a single uploaded submission and rejects duplicate staged names.
     *
     * @param file uploaded submission file
     * @return logical reference to the stored submission
     * @throws IOException if bytes cannot be written
     */
    StoredSubmission storeSingle(MultipartFile file, RequestIdentity identity) throws IOException;

    /**
     * Extracts a zip upload into a batch directory and returns one logical
     * submission reference per file in the archive.
     *
     * @param file uploaded zip archive
     * @return stored submissions extracted from the archive
     * @throws IOException if the archive cannot be read or written
     */
    List<StoredSubmission> storeZip(MultipartFile file, RequestIdentity identity) throws IOException;

    boolean isZipUpload(MultipartFile file);

    String resolveSubmissionKey(String storedKey, String fallbackRawRequestBody);

    String sanitizeSubmissionKey(String rawPath);

    String readSubmission(String submissionKey) throws IOException;

    boolean exists(String submissionKey);

    boolean delete(String submissionKey) throws IOException;

    void deleteIfExists(String submissionKey) throws IOException;
}
