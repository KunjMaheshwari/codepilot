package com.codepilot.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public class GeminiApiException extends IllegalStateException {

    private final HttpStatus status;
    private final String errorCategory;
    private final boolean retryable;
    private final Long retryAfterSeconds;

    public GeminiApiException(
            String message,
            HttpStatus status,
            String errorCategory,
            boolean retryable,
            Long retryAfterSeconds,
            Throwable cause) {
        super(message, cause);
        this.status = status;
        this.errorCategory = errorCategory;
        this.retryable = retryable;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public GeminiApiException(
            String message,
            HttpStatus status,
            String errorCategory,
            boolean retryable) {
        this(message, status, errorCategory, retryable, null, null);
    }
}

