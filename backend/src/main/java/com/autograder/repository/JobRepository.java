package com.autograder.repository;

import com.autograder.model.Job;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.NativeQuery;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

/* 
Chose to use Jpa for simplistic function calls that reduce SQL queries, but we can still introduce more complex queries where needed later :D
*/
public interface JobRepository extends JpaRepository<Job, Long> {

    @NativeQuery("SELECT * FROM jobs ORDER BY created_at DESC")
    List<Job> findAllOrderByCreatedAtDesc();
}