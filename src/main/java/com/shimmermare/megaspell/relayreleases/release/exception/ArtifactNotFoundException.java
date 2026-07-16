package com.shimmermare.megaspell.relayreleases.release.exception;

/**
 * Thrown when a requested version/platform genuinely doesn't exist - neither cached locally nor
 * available on the upstream GitHub repository. Maps to HTTP 404.
 */
public class ArtifactNotFoundException extends RuntimeException {
    public ArtifactNotFoundException(String message) {
        super(message);
    }
}
