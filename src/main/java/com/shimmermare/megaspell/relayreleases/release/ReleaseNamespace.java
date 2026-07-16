package com.shimmermare.megaspell.relayreleases.release;

import java.util.Optional;

public enum ReleaseNamespace {
    GAME("game"),
    LAUNCHER("launcher");

    private final String pathSegment;

    ReleaseNamespace(String pathSegment) {
        this.pathSegment = pathSegment;
    }

    public String getPathSegment() {
        return pathSegment;
    }

    public static Optional<ReleaseNamespace> fromPathSegment(String value) {
        for (ReleaseNamespace namespace : values()) {
            if (namespace.pathSegment.equals(value)) {
                return Optional.of(namespace);
            }
        }
        return Optional.empty();
    }
}
