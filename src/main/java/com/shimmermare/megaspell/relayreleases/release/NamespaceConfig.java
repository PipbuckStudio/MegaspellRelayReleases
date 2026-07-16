package com.shimmermare.megaspell.relayreleases.release;

import jakarta.annotation.Nullable;

/**
 * Per-namespace upstream GitHub configuration.
 */
public record NamespaceConfig(
        String repository,
        @Nullable String token
) {
}
