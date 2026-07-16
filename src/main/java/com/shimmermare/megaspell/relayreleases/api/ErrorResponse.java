package com.shimmermare.megaspell.relayreleases.api;

import jakarta.annotation.Nullable;

public record ErrorResponse(
        String error,
        String message,
        @Nullable Long retryAfterSeconds
) {
}
