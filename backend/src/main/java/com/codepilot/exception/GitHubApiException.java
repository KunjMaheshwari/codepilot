package com.codepilot.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

import java.time.Instant;

@Getter
public class GitHubApiException extends RuntimeException {

    private final HttpStatus status;
    private final Instant retryAt;

    public GitHubApiException(String message, HttpStatus status) {
        this(message, status, null);
    }

    public GitHubApiException(String message, HttpStatus status, Instant retryAt) {
        super(message);
        this.status = status;
        this.retryAt = retryAt;
    }
}
