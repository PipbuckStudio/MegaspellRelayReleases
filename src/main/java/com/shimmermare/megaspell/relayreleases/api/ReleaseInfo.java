package com.shimmermare.megaspell.relayreleases.api;

import jakarta.annotation.Nullable;

/**
 * Shaped to match the launcher's existing AppRelease (src/common/ReleaseService.ts) for a near
 * pass-through mapping on the client side.
 */
public record ReleaseInfo(
        String version,
        @Nullable String changelog,
        @Nullable String publishedAt,
        long downloadSize
) {
}
