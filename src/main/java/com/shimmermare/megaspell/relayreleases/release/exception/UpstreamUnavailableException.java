package com.shimmermare.megaspell.relayreleases.release.exception;

/**
 * Thrown when upstream GitHub is unreachable, times out, or returns a server error.
 * Maps to HTTP 502.
 */
public class UpstreamUnavailableException extends RuntimeException {
    public UpstreamUnavailableException(String message) {
        super(message);
    }

    public UpstreamUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
