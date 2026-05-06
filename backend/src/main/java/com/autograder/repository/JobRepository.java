package com.autograder.repository;

import com.autograder.model.Job;
import com.autograder.model.JobStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.NativeQuery;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.time.OffsetDateTime;

/* 
Chose to use Jpa for simplistic function calls that reduce SQL queries, but we can still introduce more complex queries where needed later :D
*/
public interface JobRepository extends JpaRepository<Job, Long> {

    interface JobStatusCount {
        JobStatus getStatus();

        long getCount();
    }

    @NativeQuery("SELECT * FROM jobs ORDER BY created_at DESC")
    List<Job> findAllOrderByCreatedAtDesc();

    List<Job> findAllByInstitutionIdOrderByCreatedAtDesc(String institutionId);

    Optional<Job> findByIdAndInstitutionId(Long id, String institutionId);

    @Query("""
            SELECT j.status AS status, COUNT(j) AS count
            FROM Job j
            GROUP BY j.status
            """)
    List<JobStatusCount> countJobsByStatus();

    List<Job> findTop10ByStatusOrderByUpdatedAtDesc(JobStatus status);

    List<Job> findTop10ByStatusOrderByStartedAtDesc(JobStatus status);

    @Query("""
            SELECT j
            FROM Job j
            WHERE j.status = com.autograder.model.JobStatus.RUNNING
              AND (
                (j.lastAttemptAt IS NOT NULL AND j.lastAttemptAt < :cutoff)
                OR (j.startedAt IS NOT NULL AND j.startedAt < :cutoff)
              )
            ORDER BY j.startedAt ASC
            """)
    List<Job> findStaleRunningJobs(@Param("cutoff") OffsetDateTime cutoff);

    @Modifying
    @Transactional
    @Query(value = """
            UPDATE jobs
            SET status = 'RUNNING',
                worker_id = :workerId,
                attempt_count = COALESCE(attempt_count, 0) + 1,
                last_attempt_at = NOW(),
                started_at = NOW(),
                finished_at = NULL,
                failure_reason = 'NONE',
                failure_message = NULL,
                updated_at = NOW()
            WHERE id = :id
              AND status = 'QUEUED'
            """, nativeQuery = true)
    int claimQueuedJob(@Param("id") Long id, @Param("workerId") String workerId);
}
