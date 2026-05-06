package com.autograder.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.autograder.service.system.QueueHealthResponse;
import com.autograder.service.system.QueueHealthService;

@CrossOrigin(origins = "http://localhost:5173/")
@RestController
@RequestMapping("/api/system")
public class SystemHealthController {

    private final QueueHealthService queueHealthService;

    public SystemHealthController(QueueHealthService queueHealthService) {
        this.queueHealthService = queueHealthService;
    }

    @GetMapping("/queue-health")
    public ResponseEntity<QueueHealthResponse> getQueueHealth() {
        return ResponseEntity.ok(queueHealthService.getQueueHealth());
    }
}
