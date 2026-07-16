package com.shimmermare.megaspell.relayreleases.release.github;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GithubReleaseAsset(
        long id,
        String url,
        String name,
        long size
) {
}
