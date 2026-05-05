package com.autograder.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.ResponseEntity;

import com.autograder.dto.GraderOptionResponse;
import com.autograder.service.identity.RequestIdentityProvider;
import com.autograder.service.grader.GraderCatalogService;

class GraderControllerTest {

    private final GraderCatalogService graderCatalogService = Mockito.mock(GraderCatalogService.class);
    private final GraderController controller = new GraderController(graderCatalogService, new RequestIdentityProvider());

    @Test
    void getGraders_returnsFrontendOptionDtos() {
        when(graderCatalogService.getGraders("university-a")).thenReturn(List.of(
                new GraderOptionResponse("fib", "Fibonacci", "Summary", List.of("Detail"))
        ));

        ResponseEntity<List<GraderOptionResponse>> response = controller.getGraders("university-a", "student-1");

        assertEquals(200, response.getStatusCode().value());
        assertEquals(1, response.getBody().size());
        assertEquals("fib", response.getBody().get(0).getKey());
    }
}
