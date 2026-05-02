package com.autograder.config;

import com.autograder.model.GraderDefinition;

import java.util.List;

// class exists to read it form the main config graders.json file :P
/**
 * Class exists to read it from the main config graders.json file :P
 * Jackson maps the "graders" array from the
 * config file into a Java object before the backend validates
 * and registers each grader definition.
 */
public class GraderConfig {
    private List<GraderDefinition> graders;

    @Override
    public String toString() {
        return "GraderConfig{graders=" + graders + "}";
    }
    //default getter and setter for graders
    public List<GraderDefinition> getGraders() {
        return graders;
    }

    public void setGraders(List<GraderDefinition> graders) {
        this.graders = graders;
    }
}