package com.shimmermare.megaspell.relayreleases.api;

import com.shimmermare.megaspell.relayreleases.release.exception.ArtifactNotFoundException;
import com.shimmermare.megaspell.relayreleases.release.exception.UpstreamRateLimitedException;
import com.shimmermare.megaspell.relayreleases.release.exception.UpstreamUnavailableException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
class RestExceptionHandlers {
    @ExceptionHandler(ArtifactNotFoundException.class)
    ResponseEntity<ErrorResponse> handleNotFound(ArtifactNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse("NOT_FOUND", e.getMessage(), null));
    }

    @ExceptionHandler(UpstreamRateLimitedException.class)
    ResponseEntity<ErrorResponse> handleRateLimited(UpstreamRateLimitedException e) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE);
        if (e.getRetryAfterSeconds() != null) {
            builder.header(HttpHeaders.RETRY_AFTER, String.valueOf(e.getRetryAfterSeconds()));
        }
        return builder.body(new ErrorResponse("UPSTREAM_RATE_LIMITED", e.getMessage(), e.getRetryAfterSeconds()));
    }

    @ExceptionHandler(UpstreamUnavailableException.class)
    ResponseEntity<ErrorResponse> handleUnavailable(UpstreamUnavailableException e) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(new ErrorResponse("UPSTREAM_UNAVAILABLE", e.getMessage(), null));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ErrorResponse> handleBadRequest(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("BAD_REQUEST", e.getMessage(), null));
    }
}
