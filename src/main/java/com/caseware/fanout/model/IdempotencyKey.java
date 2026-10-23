package com.caseware.fanout.model;

import java.util.Objects;

/**
 * Identity of one unit of fan-out work: {@code publishId:engagementId:targetVersion}.
 *
 * <p>The same key names the task in the {@code ProgressStore} and is sent to the downstream, so a retry, a
 * reclaimed lease or a republish of the same publication never produces a second effect.
 */
public record IdempotencyKey(String publishId, String engagementId, String targetVersion) {

    private static final String SEPARATOR = ":";

    public IdempotencyKey {
        requirePart(publishId, "publishId");
        requirePart(engagementId, "engagementId");
        requirePart(targetVersion, "targetVersion");
    }

    /** The wire form sent to the downstream; unambiguous because no part may contain the separator. */
    public String value() {
        return publishId + SEPARATOR + engagementId + SEPARATOR + targetVersion;
    }

    private static void requirePart(String part, String name) {
        Objects.requireNonNull(part, name);
        if (part.isBlank() || part.contains(SEPARATOR)) {
            throw new IllegalArgumentException(name + " must be non-blank and must not contain '" + SEPARATOR + "'");
        }
    }
}
