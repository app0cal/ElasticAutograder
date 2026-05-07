package com.autograder.dto;

import java.util.List;

/**
 * DTO used to send grader options from the backend to the frontend.
 *
 * Each object represents one selectable grader in the submission form.
 * Only the fields needed by the UI are exposed here instead of sending
 * the full internal GraderDefinition object.
 */
public class GraderOptionResponse {
    private final String key;
    private final String label;
    private final String language;
    private final String summary;
    private final List<String> details;

    // Constructor for reading a grader with only a key and label available
    // Automatically sets the description to no details provided
    public GraderOptionResponse(String key, String label) {
        this.key = key;
        this.label = label;
        this.language = "python";
        this.summary = "No details provided.";
        this.details = List.of();
    }

    // Constructor for reading a grader with the content needed by the submit page
    public GraderOptionResponse(String key, String label, String summary, List<String> details){
        this(key, label, "python", summary, details);
    }

    public GraderOptionResponse(String key, String label, String language, String summary, List<String> details){
        this.key = key;
        this.label = label;
        this.language = language == null || language.isBlank() ? "python" : language.trim().toLowerCase();
        this.summary = summary;
        this.details = details == null ? List.of() : List.copyOf(details);
    }

    // basic setter and getters below
    public String getKey() {
        return key;
    }

    public String getLabel() {
        return label;
    }

    public String getLanguage() {
        return language;
    }

    public String getSummary(){
        return summary;
    }

    public List<String> getDetails() {
        return details;
    }
}
