package com.shimmermare.megaspell.relayreleases.release;

import com.shimmermare.megaspell.relayreleases.release.github.GithubRelease;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory TTL cache of raw GitHub release lists, keyed by namespace. A single instance, lazy
 * TTL check on read - no scheduled eviction job needed for a single-instance service.
 */
@Component
public class ReleaseMetadataCache {
    private record Entry(List<GithubRelease> releases, Instant fetchedAt) {
    }

    private final Map<ReleaseNamespace, Entry> cache = new ConcurrentHashMap<>();
    private final Duration ttl;

    public ReleaseMetadataCache(
            @Value("${megaspell.relay.metadata-cache-ttl-seconds:180}") int ttlSeconds
    ) {
        this.ttl = Duration.ofSeconds(ttlSeconds);
    }

    public Optional<List<GithubRelease>> getIfFresh(ReleaseNamespace namespace) {
        Entry entry = cache.get(namespace);
        if (entry == null) {
            return Optional.empty();
        }
        if (Duration.between(entry.fetchedAt(), Instant.now()).compareTo(ttl) > 0) {
            return Optional.empty();
        }
        return Optional.of(entry.releases());
    }

    /**
     * Returns cached data regardless of age - used as a last resort when upstream GitHub is
     * currently failing but we have *something* to serve.
     */
    public Optional<List<GithubRelease>> getStale(ReleaseNamespace namespace) {
        Entry entry = cache.get(namespace);
        return entry == null ? Optional.empty() : Optional.of(entry.releases());
    }

    public void put(ReleaseNamespace namespace, List<GithubRelease> releases) {
        cache.put(namespace, new Entry(releases, Instant.now()));
    }
}
