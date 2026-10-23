package com.caseware.fanout.model;

/**
 * Counts for one run.
 *
 * @param done         tasks this run completed
 * @param deadLettered tasks this run sent to the dead-letter queue
 * @param skipped      targets this run did not call: already {@code DONE}/{@code FAILED}, a repeated id, or
 *                     leased by another live worker (a later run reclaims it once that lease expires)
 */
public record RunSummary(int done, int deadLettered, int skipped) {
}
