package com.autograder.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.autograder.dto.GraderOptionResponse;
import com.autograder.service.identity.RequestIdentity;
import com.autograder.service.identity.RequestIdentityProvider;
import com.autograder.service.grader.GraderCatalogService;

/**
 * REST controller for listing grader options available to the frontend.
 */
@CrossOrigin(origins = "http://localhost:5173/")
@RestController
@RequestMapping("/api")
public class GraderController {

    private final GraderCatalogService graderCatalogService;
    private final RequestIdentityProvider requestIdentityProvider;

    public GraderController(
            GraderCatalogService graderCatalogService,
            RequestIdentityProvider requestIdentityProvider
    ) {
        this.graderCatalogService = graderCatalogService;
        this.requestIdentityProvider = requestIdentityProvider;
    }

    @GetMapping("/graders")
    public ResponseEntity<List<GraderOptionResponse>> getGraders(
            @RequestHeader(value = "X-Mock-Institution", required = false) String institutionHeader,
            @RequestHeader(value = "X-Mock-User", required = false) String userHeader
    ) {
        RequestIdentity identity = requestIdentityProvider.resolve(institutionHeader, userHeader);
        return ResponseEntity.ok(graderCatalogService.getGraders(identity.institution()));
    }
}
