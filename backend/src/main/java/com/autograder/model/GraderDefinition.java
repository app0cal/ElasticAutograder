package com.autograder.model;

import java.util.List;

public class GraderDefinition {
    private String institutionId; // mock institution that owns this grader definition
    private String key; // main key for identifying the grader, e.g. "fib" or "twosum"
    private String label; // human readable label for the grader, e.g. "Fibonacci" or "Two Sum"
    private String imageName; // the name of the docker image for this grader, e.g. "ea-grader-fibbonaci:v1" or "ea-grader-twosum:v1"
    private String graderFolder; // folder under backend/grading/image-build; defaults to key
    private String manifestPath; // the path to the manifest file for this grader, e.g. "/app/grader/manifest.json"
    private String summary; // short overview shown in the submit page
    private List<String> details; // longer description points rendered as a list

    private Integer timeoutSeconds; // default timeout for all graders

    // requests vs limits: IMPORTANT 
    // requests are the min resources guaranteed to be allocated
    // limits are the absolute max resources that can be used, if the grader exceeds then MAY fail 
    
    // Milli refers to Millicores
    private Integer cpuRequestMilli; // default CPU request for all graders
    private Integer cpuLimitMilli; // default CPU limit for all graders

    private Integer memoryRequestMb; // default memory request for all graders
    private Integer memoryLimitMb; // default memory limit for all graders

    public GraderDefinition() {
        // default constructor for Jackson (unit testing and config loading)
    }

    // constructor with all fields filled (ALL must be filled)
    public GraderDefinition(
            String key,
            String label,
            String imageName,
            String manifestPath,
            String summary,
            List<String> details,
            Integer timeoutSeconds,
            Integer cpuRequestMilli,
            Integer cpuLimitMilli,
            Integer memoryRequestMb,
            Integer memoryLimitMb
    ) {
        this.institutionId = "local";
        this.key = key;
        this.label = label;
        this.imageName = imageName;
        this.graderFolder = key;
        this.manifestPath = manifestPath;
        this.summary = summary;
        this.details = details;

        this.timeoutSeconds = timeoutSeconds != null ? timeoutSeconds : 10;
        this.cpuRequestMilli = cpuRequestMilli != null ? cpuRequestMilli : 100;
        this.cpuLimitMilli = cpuLimitMilli != null ? cpuLimitMilli : 500;
        this.memoryRequestMb = memoryRequestMb != null ? memoryRequestMb : 128;
        this.memoryLimitMb = memoryLimitMb != null ? memoryLimitMb : 512;
    }

    // constructor calls the other one with default values for the optional fields
    public GraderDefinition(String key, String label, String imageName, String manifestPath) {
        this(key, label, imageName, manifestPath, null, null,
            null, null, null, null, null);
    }

    // getters for all fields (no setters since this is immutable)
    public String getInstitutionId() {
        return institutionId;
    }

    public void setInstitutionId(String institutionId) {
        this.institutionId = institutionId;
    }

    public String getKey() {
        return key;
    }

    public void setKey(String key){
        this.key = key;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label){
        this.label = label;
    }

    public String getImageName() {
        return imageName;
    }

    public void setImageName(String imageName){
        this.imageName = imageName;
    }

    public String getGraderFolder() {
        return graderFolder;
    }

    public void setGraderFolder(String graderFolder) {
        this.graderFolder = graderFolder;
    }

    public String getManifestPath() {
        return manifestPath;
    }

    public void setManifestPath(String manifestPath){
        this.manifestPath = manifestPath;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public List<String> getDetails() {
        return details;
    }

    public void setDetails(List<String> details) {
        this.details = details;
    }

    public Integer getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(Integer timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    public Integer getCpuRequestMilli() {
        return cpuRequestMilli;
    }

    public void setCpuRequestMilli(Integer cpuRequestMilli) {
        this.cpuRequestMilli = cpuRequestMilli;
    }
    
    public Integer getCpuLimitMilli(){
        return cpuLimitMilli;
    }
    
    public void setCpuLimitMilli(Integer cpuLimitMilli) {
        this.cpuLimitMilli = cpuLimitMilli;
    }

    public Integer getMemoryRequestMb() {
        return memoryRequestMb;
    }

    public void setMemoryRequestMb(Integer memoryRequestMb) {
        this.memoryRequestMb = memoryRequestMb;
    }

    public Integer getMemoryLimitMb(){
        return memoryLimitMb;
    }

    public void setMemoryLimitMb(Integer memoryLimitMb) {
        this.memoryLimitMb = memoryLimitMb;
    }
}
