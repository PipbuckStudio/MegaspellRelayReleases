package com.shimmermare.megaspell.relayreleases.api;

import com.shimmermare.megaspell.relayreleases.release.ArtifactResult;
import com.shimmermare.megaspell.relayreleases.release.ReleaseNamespace;
import com.shimmermare.megaspell.relayreleases.release.ReleaseService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.InputStream;
import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("releases")
class ReleasesController {
    private static final int DEFAULT_LIST_LIMIT = 20;
    private static final int MAX_LIST_LIMIT = 100;

    private final ReleaseService releaseService;

    ReleasesController(ReleaseService releaseService) {
        this.releaseService = releaseService;
    }

    /**
     * List last releases, newest first.
     */
    @GetMapping("/{namespace}")
    public List<ReleaseInfo> getLastReleases(
            @PathVariable("namespace") String namespaceStr,
            @RequestParam(value = "limit", defaultValue = "" + DEFAULT_LIST_LIMIT) int limit,
            @RequestParam(value = "platform", required = false) String platform
    ) {
        ReleaseNamespace namespace = parseNamespace(namespaceStr);
        int boundedLimit = Math.min(Math.max(limit, 1), MAX_LIST_LIMIT);
        return releaseService.getLastReleases(namespace, Optional.ofNullable(platform), boundedLimit);
    }

    /**
     * Resolve the latest release's metadata.
     */
    @GetMapping("/{namespace}/latest")
    public ReleaseInfo getLatest(
            @PathVariable("namespace") String namespaceStr,
            @RequestParam(value = "platform", required = false) String platform
    ) {
        ReleaseNamespace namespace = parseNamespace(namespaceStr);
        return releaseService.getLatest(namespace, Optional.ofNullable(platform))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No releases found"));
    }

    /**
     * Serve (mirroring from GitHub on first request) a single artifact file.
     */
    @GetMapping("/{namespace}/{version}/download")
    public ResponseEntity<StreamingResponseBody> download(
            @PathVariable("namespace") String namespaceStr,
            @PathVariable("version") String version,
            @RequestParam("platform") String platform
    ) {
        ReleaseNamespace namespace = parseNamespace(namespaceStr);
        ArtifactResult artifact = releaseService.getArtifact(namespace, version, platform);

        StreamingResponseBody body = outputStream -> {
            try (InputStream in = releaseService.openArtifactStream(artifact.path())) {
                in.transferTo(outputStream);
            }
        };

        String filename = artifact.path().getFileName().toString();

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(artifact.size())
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .body(body);
    }

    private ReleaseNamespace parseNamespace(String value) {
        return ReleaseNamespace.fromPathSegment(value)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown namespace: " + value));
    }
}
