package com.shimmermare.megaspell.relayreleases.release.exception;

import jakarta.annotation.Nullable;

/**
 * Thrown when upstream GitHub responds with a rate-limit (403/429) status.
 * Maps to HTTP 503 with a Retry-After header.
 */
public class UpstreamRateLimitedException extends RuntimeException {
    @Nullable
    private final Long retryAfterSeconds;

    public UpstreamRateLimitedException(String message, @Nullable Long retryAfterSeconds) {
        super(message);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    @Nullable
    public Long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
