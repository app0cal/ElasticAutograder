package com.autograder.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.autograder.dto.GraderOptionResponse;
import com.autograder.service.grader.GraderCatalogService;

/**
 * REST controller for listing grader options available to the frontend.
 */
@CrossOrigin(origins = "http://localhost:5173/")
@RestController
@RequestMapping("/api")
public class GraderController {

    private final GraderCatalogService graderCatalogService;

    public GraderController(GraderCatalogService graderCatalogService) {
        this.graderCatalogService = graderCatalogService;
    }

    @GetMapping("/graders")
    public ResponseEntity<List<GraderOptionResponse>> getGraders() {
        return ResponseEntity.ok(graderCatalogService.getGraders());
    }
}
