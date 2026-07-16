package com.shimmermare.megaspell.relayreleases.release;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.regex.Pattern;

/**
 * Pure filesystem I/O for cached artifacts - no business rules, mirrors the reference project's
 * repository layer but backed by the filesystem instead of a database.
 */
@Component
public class ArtifactStorage {
    // Conservative allow-list: alphanumeric, dot, dash, underscore. Rejects path separators and
    // ".." so route parameters can never escape the storage directory.
    private static final Pattern SAFE_SEGMENT = Pattern.compile("^[A-Za-z0-9._-]+$");

    private final Path storageRoot;

    public ArtifactStorage(@Value("${megaspell.relay.storage-dir}") String storageDir) {
        this.storageRoot = Path.of(storageDir).toAbsolutePath().normalize();
    }

    public Path resolveFinalPath(ReleaseNamespace namespace, String version, String platform) {
        String safeVersion = requireSafeSegment(version, "version");
        String safePlatform = requireSafeSegment(platform, "platform");

        Path path = storageRoot
                .resolve(namespace.getPathSegment())
                .resolve(safeVersion)
                .resolve(safePlatform + ".zip")
                .normalize();

        if (!path.startsWith(storageRoot)) {
            throw new IllegalArgumentException("Resolved path escapes storage root");
        }

        return path;
    }

    private String requireSafeSegment(String value, String fieldName) {
        if (value == null || value.isBlank() || !SAFE_SEGMENT.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid " + fieldName + ": " + value);
        }
        return value;
    }

    public boolean exists(Path finalPath) {
        return Files.isRegularFile(finalPath);
    }

    public long sizeOf(Path finalPath) {
        try {
            return Files.size(finalPath);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Opens a staging file (a sibling "*.part" of the final path) for writing. Cache-hit checks
     * only ever look at the final filename, never "*.part", so a leftover partial file from a
     * crashed/interrupted download is automatically invisible and safely overwritten on retry -
     * no startup cleanup is required.
     */
    public OutputStream openStagingWrite(Path finalPath) {
        Path stagingPath = stagingPathFor(finalPath);
        try {
            Files.createDirectories(finalPath.getParent());
            return Files.newOutputStream(stagingPath);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void commit(Path finalPath) {
        Path stagingPath = stagingPathFor(finalPath);
        try {
            Files.move(stagingPath, finalPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void discardStaging(Path finalPath) {
        Path stagingPath = stagingPathFor(finalPath);
        try {
            Files.deleteIfExists(stagingPath);
        } catch (IOException ignored) {
            // best-effort cleanup
        }
    }

    public InputStream openRead(Path finalPath) {
        try {
            return Files.newInputStream(finalPath);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Path stagingPathFor(Path finalPath) {
        return finalPath.resolveSibling(finalPath.getFileName().toString() + ".part");
    }
}
