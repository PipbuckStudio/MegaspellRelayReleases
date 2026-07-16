package com.shimmermare.megaspell.relayreleases.release;

import com.shimmermare.megaspell.relayreleases.api.ReleaseInfo;
import com.shimmermare.megaspell.relayreleases.release.exception.ArtifactNotFoundException;
import com.shimmermare.megaspell.relayreleases.release.exception.UpstreamRateLimitedException;
import com.shimmermare.megaspell.relayreleases.release.exception.UpstreamUnavailableException;
import com.shimmermare.megaspell.relayreleases.release.github.GithubRelease;
import com.shimmermare.megaspell.relayreleases.release.github.GithubReleaseAsset;
import com.shimmermare.megaspell.relayreleases.release.github.GithubReleaseClient;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ReleaseService {
    private static final Logger LOGGER = LoggerFactory.getLogger(ReleaseService.class);
    private static final String LATEST_VERSION = "latest";

    private final Map<ReleaseNamespace, NamespaceConfig> namespaceConfigs = new EnumMap<>(ReleaseNamespace.class);
    private final Map<String, CompletableFuture<ArtifactResult>> inFlightDownloads = new ConcurrentHashMap<>();

    private final GithubReleaseClient githubClient;
    private final ArtifactStorage storage;
    private final ReleaseMetadataCache metadataCache;
    private final Counter artifactsMirroredCounter;
    private final Counter artifactsCacheHitCounter;

    public ReleaseService(
            @Value("${megaspell.relay.game.repository}") String gameRepository,
            @Value("${megaspell.relay.game.token:}") String gameToken,
            @Value("${megaspell.relay.launcher.repository}") String launcherRepository,
            @Value("${megaspell.relay.launcher.token:}") String launcherToken,
            GithubReleaseClient githubClient,
            ArtifactStorage storage,
            ReleaseMetadataCache metadataCache,
            MeterRegistry meterRegistry
    ) {
        this.namespaceConfigs.put(ReleaseNamespace.GAME, new NamespaceConfig(gameRepository, blankToNull(gameToken)));
        this.namespaceConfigs.put(ReleaseNamespace.LAUNCHER, new NamespaceConfig(launcherRepository, blankToNull(launcherToken)));
        this.githubClient = githubClient;
        this.storage = storage;
        this.metadataCache = metadataCache;
        this.artifactsMirroredCounter = meterRegistry.counter("relay_artifacts_mirrored_total");
        this.artifactsCacheHitCounter = meterRegistry.counter("relay_artifacts_cache_hit_total");
    }

    public List<ReleaseInfo> getLastReleases(ReleaseNamespace namespace, Optional<String> platform, int limit) {
        List<GithubRelease> releases = getReleases(namespace);
        return releases.stream()
                .sorted(Comparator.comparing(GithubRelease::tag_name, Comparator.reverseOrder()))
                .map(release -> toReleaseInfo(release, platform))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .limit(limit)
                .toList();
    }

    public Optional<ReleaseInfo> getLatest(ReleaseNamespace namespace, Optional<String> platform) {
        return getLastReleases(namespace, platform, 1).stream().findFirst();
    }

    public ArtifactResult getArtifact(ReleaseNamespace namespace, String version, String platform) {
        // "latest" must always be resolved against fresh(-ish) metadata before touching the
        // cache - otherwise the first resolution would be cached under the literal name
        // "latest" and keep being served forever, even after a new version is published.
        // Concrete versions skip this and go straight to the cache path below (zero network
        // calls on a cache hit).
        String resolvedVersion = LATEST_VERSION.equals(version)
                ? resolveVersion(getReleases(namespace), version)
                        .orElseThrow(() -> new ArtifactNotFoundException(
                                "No releases found in " + namespace.getPathSegment()))
                        .tag_name()
                : version;

        Path finalPath = storage.resolveFinalPath(namespace, resolvedVersion, platform);

        if (storage.exists(finalPath)) {
            artifactsCacheHitCounter.increment();
            return new ArtifactResult(finalPath, storage.sizeOf(finalPath));
        }

        String dedupeKey = namespace.name() + "/" + resolvedVersion + "/" + platform;
        CompletableFuture<ArtifactResult> future = inFlightDownloads.computeIfAbsent(
                dedupeKey,
                key -> CompletableFuture.supplyAsync(() -> mirrorArtifact(namespace, resolvedVersion, platform, finalPath))
        );

        try {
            return future.join();
        } catch (java.util.concurrent.CompletionException e) {
            if (e.getCause() instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new UpstreamUnavailableException("Failed to mirror artifact", e.getCause());
        } finally {
            inFlightDownloads.remove(dedupeKey, future);
        }
    }

    private ArtifactResult mirrorArtifact(ReleaseNamespace namespace, String version, String platform, Path finalPath) {
        // Re-check under the dedupe lock in case another request just finished mirroring it.
        if (storage.exists(finalPath)) {
            return new ArtifactResult(finalPath, storage.sizeOf(finalPath));
        }

        NamespaceConfig config = namespaceConfigs.get(namespace);
        List<GithubRelease> releases = getReleases(namespace);

        // version is already a concrete, resolved tag at this point (see getArtifact above).
        GithubRelease release = resolveVersion(releases, version)
                .orElseThrow(() -> new ArtifactNotFoundException(
                        "Version " + version + " not found in " + namespace.getPathSegment()));

        List<GithubReleaseAsset> assets = githubClient.findAssetsForPlatform(release, platform);
        if (assets.isEmpty()) {
            throw new ArtifactNotFoundException(
                    "No asset for platform " + platform + " in release " + release.tag_name());
        }

        LOGGER.info("Mirroring {} {} ({}) from GitHub, {} volume(s)",
                namespace.getPathSegment(), release.tag_name(), platform, assets.size());

        try (OutputStream out = storage.openStagingWrite(finalPath)) {
            for (GithubReleaseAsset asset : assets) {
                githubClient.downloadAsset(asset, config.token(), out, bytes -> { });
            }
        } catch (java.io.IOException e) {
            storage.discardStaging(finalPath);
            throw new UpstreamUnavailableException("Failed to write mirrored artifact to disk", e);
        } catch (RuntimeException e) {
            storage.discardStaging(finalPath);
            throw e;
        }

        storage.commit(finalPath);
        artifactsMirroredCounter.increment();

        return new ArtifactResult(finalPath, storage.sizeOf(finalPath));
    }

    public InputStream openArtifactStream(Path path) {
        return storage.openRead(path);
    }

    private List<GithubRelease> getReleases(ReleaseNamespace namespace) {
        Optional<List<GithubRelease>> fresh = metadataCache.getIfFresh(namespace);
        if (fresh.isPresent()) {
            return fresh.get();
        }

        try {
            List<GithubRelease> releases = githubClient.listReleases(namespaceConfigs.get(namespace));
            metadataCache.put(namespace, releases);
            return releases;
        } catch (UpstreamRateLimitedException | UpstreamUnavailableException e) {
            Optional<List<GithubRelease>> stale = metadataCache.getStale(namespace);
            if (stale.isPresent()) {
                LOGGER.warn("Upstream GitHub failed for {}, serving stale metadata cache", namespace, e);
                return stale.get();
            }
            throw e;
        }
    }

    private Optional<GithubRelease> resolveVersion(List<GithubRelease> releases, String version) {
        if (LATEST_VERSION.equals(version)) {
            return releases.stream()
                    .max(Comparator.comparing(GithubRelease::tag_name));
        }
        return releases.stream()
                .filter(r -> r.tag_name().equals(version))
                .findFirst();
    }

    private Optional<ReleaseInfo> toReleaseInfo(GithubRelease release, Optional<String> platform) {
        long downloadSize = 0;
        if (platform.isPresent()) {
            List<GithubReleaseAsset> assets = githubClient.findAssetsForPlatform(release, platform.get());
            if (assets.isEmpty()) {
                // Release has no build for the requested platform - exclude it, mirroring the
                // launcher's own GithubReleaseProvider.ts behavior of dropping such releases.
                return Optional.empty();
            }
            downloadSize = assets.stream().mapToLong(GithubReleaseAsset::size).sum();
        }

        return Optional.of(new ReleaseInfo(
                release.tag_name(),
                release.body(),
                release.published_at(),
                downloadSize
        ));
    }

    @Nullable
    private static String blankToNull(@Nullable String value) {
        return (value == null || value.isBlank()) ? null : value;
    }
}
