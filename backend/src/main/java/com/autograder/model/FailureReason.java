package com.autograder.model;

public enum FailureReason {
  NONE,
  INVALID_UPLOAD,
  WRONG_ANSWER,
  TIMEOUT,
  RESOURCE_LIMIT,
  KUBERNETES_ERROR,
  RESULT_PARSE_ERROR,
  CONFIG_ERROR,
  UNKNOWN
}
