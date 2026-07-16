package com.shimmermare.megaspell.relayreleases.release.github;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shimmermare.megaspell.relayreleases.release.NamespaceConfig;
import com.shimmermare.megaspell.relayreleases.release.exception.UpstreamRateLimitedException;
import com.shimmermare.megaspell.relayreleases.release.exception.UpstreamUnavailableException;
import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Pure I/O client for the GitHub REST API - no caching, no business rules. Classifies failures
 * into typed exceptions so callers can decide how to react (404 vs rate-limit vs outage).
 */
@Component
public class GithubReleaseClient {
    private static final Logger LOGGER = LoggerFactory.getLogger(GithubReleaseClient.class);

    private static final int PER_PAGE = 100;
    private static final int MAX_DOWNLOAD_RETRIES = 10;
    private static final long BASE_RETRY_DELAY_MILLIS = 1000;

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Duration requestTimeout;

    public GithubReleaseClient(
            @Value("${megaspell.relay.github-request-timeout-seconds:15}") int requestTimeoutSeconds
    ) {
        this.requestTimeout = Duration.ofSeconds(requestTimeoutSeconds);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(requestTimeout)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * Fetches all non-draft, non-prerelease releases, newest first as returned by GitHub.
     */
    public List<GithubRelease> listReleases(NamespaceConfig config) {
        List<GithubRelease> allReleases = new ArrayList<>();
        int page = 1;
        boolean isLastPage = false;

        while (!isLastPage) {
            String url = "https://api.github.com/repos/%s/releases?per_page=%d&page=%d"
                    .formatted(config.repository(), PER_PAGE, page);
            List<GithubRelease> pageReleases = requestJson(url, config.token(), GithubRelease[].class);
            allReleases.addAll(pageReleases);
            isLastPage = pageReleases.size() < PER_PAGE;
            page++;
        }

        return allReleases.stream()
                .filter(r -> !r.draft() && !r.prerelease())
                .collect(Collectors.toList());
    }

    /**
     * Finds the asset(s) for the given platform. A single exact match ("{platform}.zip") is
     * preferred; otherwise falls back to sorted multi-volume parts ("{platform}.zip.001" etc).
     * Returns an empty list if nothing matches.
     */
    public List<GithubReleaseAsset> findAssetsForPlatform(GithubRelease release, String platform) {
        Optional<GithubReleaseAsset> single = release.assets().stream()
                .filter(a -> a.name().equals(platform + ".zip"))
                .findFirst();
        if (single.isPresent()) {
            return List.of(single.get());
        }

        Pattern volumePattern = Pattern.compile(Pattern.quote(platform + ".zip.") + "\\d+");
        List<GithubReleaseAsset> volumes = release.assets().stream()
                .filter(a -> volumePattern.matcher(a.name()).matches())
                .sorted((a, b) -> a.name().compareTo(b.name()))
                .toList();

        return volumes;
    }

    /**
     * Downloads a single asset into {@code sink}, retrying with a Range offset on transient
     * failures instead of restarting from scratch. Throws on non-2xx status BEFORE writing
     * anything from the response body, so a rate-limit/error body is never mistaken for content.
     */
    public void downloadAsset(
            GithubReleaseAsset asset,
            @Nullable String token,
            OutputStream sink,
            java.util.function.LongConsumer onBytesWritten
    ) {
        long[] totalWritten = {0};

        for (int attempt = 1; attempt <= MAX_DOWNLOAD_RETRIES; attempt++) {
            try {
                downloadAssetAttempt(asset, token, sink, bytes -> {
                    totalWritten[0] += bytes;
                    onBytesWritten.accept(totalWritten[0]);
                });
                return;
            } catch (UpstreamRateLimitedException e) {
                // Not worth retrying immediately - propagate so caller can surface 503 now.
                throw e;
            } catch (Exception e) {
                if (attempt == MAX_DOWNLOAD_RETRIES) {
                    throw new UpstreamUnavailableException(
                            "Failed to download asset " + asset.name() + " after " + MAX_DOWNLOAD_RETRIES + " attempts",
                            e
                    );
                }
                long delay = BASE_RETRY_DELAY_MILLIS * attempt;
                LOGGER.warn("Download of {} failed (attempt {}/{}), retrying in {}ms",
                        asset.name(), attempt, MAX_DOWNLOAD_RETRIES, delay, e);
                sleep(delay);
            }
        }
    }

    private void downloadAssetAttempt(
            GithubReleaseAsset asset,
            @Nullable String token,
            OutputStream sink,
            java.util.function.LongConsumer onChunkWritten
    ) throws IOException, InterruptedException {
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(asset.url()))
                .header("Accept", "application/octet-stream")
                .timeout(requestTimeout);
        if (token != null && !token.isBlank()) {
            requestBuilder.header("Authorization", "Bearer " + token);
        }

        HttpResponse<java.io.InputStream> response = httpClient.send(
                requestBuilder.build(),
                HttpResponse.BodyHandlers.ofInputStream()
        );

        checkStatus(response.statusCode(), response.headers().map(), asset.url());

        try (java.io.InputStream in = response.body()) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                sink.write(buffer, 0, read);
                onChunkWritten.accept(read);
            }
        }
    }

    private <T> List<T> requestJson(String url, @Nullable String token, Class<T[]> arrayType) {
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Accept", "application/json")
                .timeout(requestTimeout);
        if (token != null && !token.isBlank()) {
            requestBuilder.header("Authorization", "Bearer " + token);
        }

        HttpResponse<String> response;
        try {
            response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException | InterruptedException e) {
            throw new UpstreamUnavailableException("Request to GitHub failed: " + url, e);
        }

        checkStatus(response.statusCode(), response.headers().map(), url);

        try {
            T[] parsed = objectMapper.readValue(response.body(), arrayType);
            return List.of(parsed);
        } catch (IOException e) {
            throw new UpstreamUnavailableException("Failed to parse GitHub response from " + url, e);
        }
    }

    private void checkStatus(int status, java.util.Map<String, List<String>> headers, String url) {
        if (status >= 200 && status < 300) {
            return;
        }

        if (status == 403 || status == 429) {
            Long retryAfter = extractRetryAfterSeconds(headers);
            throw new UpstreamRateLimitedException(
                    "GitHub rate limit exceeded for " + url, retryAfter
            );
        }

        LOGGER.error("Unexpected GitHub response {} for {}", status, url);
        throw new UpstreamUnavailableException("GitHub returned status " + status + " for " + url);
    }

    @Nullable
    private Long extractRetryAfterSeconds(java.util.Map<String, List<String>> headers) {
        List<String> retryAfter = headers.get("retry-after");
        if (retryAfter != null && !retryAfter.isEmpty()) {
            try {
                return Long.parseLong(retryAfter.get(0));
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }

        List<String> resetHeader = headers.get("x-ratelimit-reset");
        if (resetHeader != null && !resetHeader.isEmpty()) {
            try {
                long resetEpochSeconds = Long.parseLong(resetHeader.get(0));
                long nowEpochSeconds = System.currentTimeMillis() / 1000;
                return Math.max(0, resetEpochSeconds - nowEpochSeconds);
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }

        return null;
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
