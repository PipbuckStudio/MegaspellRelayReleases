package com.shimmermare.megaspell.relayreleases.release.github;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.annotation.Nullable;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GithubRelease(
        String tag_name,
        @Nullable String body,
        boolean draft,
        boolean prerelease,
        @Nullable String published_at,
        List<GithubReleaseAsset> assets
) {
}
