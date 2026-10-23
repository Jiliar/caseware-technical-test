# Synchronous API contracts

Request and response contracts of the synchronous APIs of *Pending Template Updates*, written as JSON Schema draft 2020-12. The Spanish documents are the source of truth: `docs/es/DESIGN_DOC.md` > `docs/es/DISENO.md` (§3.1–§3.7 (e), §3.8.5 API conventions, §3.8.6 view models, §4.8) > `docs/es/PRD.md`, with ADR-020. Names and types match the sibling contracts in `../dynamo/`, `../postgresql/` and `../events/`.

Communication between services is asynchronous by default (ADR-005). The only synchronous traffic is:

- the **public** API that `ms-experience-gateway` serves to the UI;
- the **operator** APIs: jobs (S4), campaigns and reconciliation runs (S5), evidence (S6, compliance role), publish progress (S3) and reviewers' reports (S2);
- the **internal** reads catalogued as exceptions SX-01a, SX-01b, SX-02 and SX-06 (DISENO §4.8, ADR-020).

The exposed APIs are reads plus a closed set of writes (DEC-32): the operator writes of S4 and S5, the reviewers' reports of S2 and the reverification of S7, which only enqueues commands. No write is a call between services.

## Layout

```text
apis/
├── _shared/                     problem-details, pagination, common-headers + view models (DISENO 3.8.6)
├── _consumed/                   CONSUMED CONTRACTS: external systems we call (client-side expectation only)
│   ├── engagements-system/      SX-03 inspectEngagement, SX-04 listCatalogEngagements
│   └── template-comparator/     SX-05 compareTemplateVersions
├── ms-experience-gateway/       S7, public
├── ms-engagement-index/         S3, internal (same cell) + operator progress
├── ms-change-summary/           S2, internal (global) + reviewers' reports
├── ms-template-catalog/         S1, internal (global)
├── ms-publish-fanout/           S4, operator
├── ms-backfill-reconciler/      S5, operator
└── ms-audit-ledger/             S6, operator (compliance role)
```

Each operation has `<operationId>.request.schema.json` and `<operationId>.response.schema.json`.

- **Request** schemas describe the whole request as `{method (const), path (const template), headers, pathParams?, queryParams?, body?}`.
- **Response** schemas use a `oneOf` with one subschema per status code, under `$defs/status<code>`. Each subschema has `{status (const), headers, body?}`.
- **Error bodies** are `allOf` [`_shared/problem-details.schema.json`, `status` const, allowed problem `type` values].

Every schema has `$id`, `title`, `description`, `$comment` (source, audience, residency, idempotency, pagination), `type`, `properties`, `required`, `additionalProperties: false` and exactly one example (requests: full request; responses: success case). Field names are camelCase; header names are lowercase (HTTP/2 wire form). Ids use `format: uuid`, instants `format: date-time`; version labels (`v7`) and other natural keys keep their type (DISENO §3.8.1).

## Index (30 operations)

Audience: **public** = served to the UI by S7; **operator** = operator console, compliance role or content reviewers; **internal** = service-to-service (SX-xx).

| Service | operationId | Method + path | Audience | Request schema | Response schema | Statuses | Idempotency | Pagination |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `ms-experience-gateway` | `listEngagements` | `GET /v1/engagements` | public | [`listEngagements.request.schema.json`](ms-experience-gateway/listEngagements.request.schema.json) | [`listEngagements.response.schema.json`](ms-experience-gateway/listEngagements.response.schema.json) | 200, 400, 503 | safe read (GET) | cursor + limit <= N_pagemax; opaque cursor encoding the position per shard (RF-034) |
| `ms-experience-gateway` | `getPendingUpdateView` | `GET /v1/engagements/{engagementId}/pending-update` | public | [`getPendingUpdateView.request.schema.json`](ms-experience-gateway/getPendingUpdateView.request.schema.json) | [`getPendingUpdateView.response.schema.json`](ms-experience-gateway/getPendingUpdateView.response.schema.json) | 200, 404, 503 | read with an audit side effect: each served view emits one SummaryViewed.v1 with a fresh eventId (RF-048: every view counts) | none (single resource) |
| `ms-experience-gateway` | `getPendingUpdateDiff` | `GET /v1/engagements/{engagementId}/pending-update/diff` | public | [`getPendingUpdateDiff.request.schema.json`](ms-experience-gateway/getPendingUpdateDiff.request.schema.json) | [`getPendingUpdateDiff.response.schema.json`](ms-experience-gateway/getPendingUpdateDiff.response.schema.json) | 200, 400, 404, 503 | safe read (GET) | cursor + limit <= N_pagemax; the cursor encodes the last diff path served |
| `ms-experience-gateway` | `getPendingUpdateBreakdown` | `GET /v1/engagements/{engagementId}/pending-update/breakdown` | public | [`getPendingUpdateBreakdown.request.schema.json`](ms-experience-gateway/getPendingUpdateBreakdown.request.schema.json) | [`getPendingUpdateBreakdown.response.schema.json`](ms-experience-gateway/getPendingUpdateBreakdown.response.schema.json) | 200, 404, 503 | safe read (GET); a missing step summary triggers GenerateChangeSummary.v1 | none (bounded by the intermediate versions) |
| `ms-experience-gateway` | `getCounters` | `GET /v1/counters` | public | [`getCounters.request.schema.json`](ms-experience-gateway/getCounters.request.schema.json) | [`getCounters.response.schema.json`](ms-experience-gateway/getCounters.response.schema.json) | 200, 503 | safe read (GET) | none (bounded per product) |
| `ms-experience-gateway` | `listNotifications` | `GET /v1/notifications` | public | [`listNotifications.request.schema.json`](ms-experience-gateway/listNotifications.request.schema.json) | [`listNotifications.response.schema.json`](ms-experience-gateway/listNotifications.response.schema.json) | 200, 400 | safe read (GET) | cursor + limit <= N_pagemax (DISENO 3.8.5) |
| `ms-experience-gateway` | `streamNotifications` | `GET /v1/notifications/stream` | public | [`streamNotifications.request.schema.json`](ms-experience-gateway/streamNotifications.request.schema.json) | [`streamNotifications.response.schema.json`](ms-experience-gateway/streamNotifications.response.schema.json) | 200, 503 | safe read (GET); reconnection with Last-Event-ID does not duplicate (notification id = eventId) | n/a (stream; resume with Last-Event-ID) |
| `ms-experience-gateway` | `reverifyEngagement` | `POST /v1/engagements/{engagementId}:reverify` | public | [`reverifyEngagement.request.schema.json`](ms-experience-gateway/reverifyEngagement.request.schema.json) | [`reverifyEngagement.response.schema.json`](ms-experience-gateway/reverifyEngagement.response.schema.json) | 202, 400, 404, 409, 503 | Idempotency-Key required: jobId = UUID v5 of the key, so a repeated request returns the same job (DEC-35) | n/a |
| `ms-engagement-index` | `listFirmEngagements` | `GET /v1/firms/{firmId}/engagements` | internal | [`listFirmEngagements.request.schema.json`](ms-engagement-index/listFirmEngagements.request.schema.json) | [`listFirmEngagements.response.schema.json`](ms-engagement-index/listFirmEngagements.response.schema.json) | 200, 400, 503 | safe read (GET) | cursor + limit <= N_pagemax; opaque cursor encoding the position per shard (RF-034) |
| `ms-engagement-index` | `getFirmCounters` | `GET /v1/firms/{firmId}/counters` | internal | [`getFirmCounters.request.schema.json`](ms-engagement-index/getFirmCounters.request.schema.json) | [`getFirmCounters.response.schema.json`](ms-engagement-index/getFirmCounters.response.schema.json) | 200, 503 | safe read (GET) | none (bounded: one entry per template/product) |
| `ms-engagement-index` | `getEngagementPendingUpdate` | `GET /v1/engagements/{engagementId}/pending-update` | internal | [`getEngagementPendingUpdate.request.schema.json`](ms-engagement-index/getEngagementPendingUpdate.request.schema.json) | [`getEngagementPendingUpdate.response.schema.json`](ms-engagement-index/getEngagementPendingUpdate.response.schema.json) | 200, 404, 503 | safe read (GET) | none (single resource) |
| `ms-engagement-index` | `getPublishProgress` | `GET /v1/publishes/{publishId}/progress` | operator | [`getPublishProgress.request.schema.json`](ms-engagement-index/getPublishProgress.request.schema.json) | [`getPublishProgress.response.schema.json`](ms-engagement-index/getPublishProgress.response.schema.json) | 200, 404, 503 | safe read (GET) | none (bounded by source versions x N_shards) |
| `ms-change-summary` | `findSummaries` | `GET /v1/summaries` | internal | [`findSummaries.request.schema.json`](ms-change-summary/findSummaries.request.schema.json) | [`findSummaries.response.schema.json`](ms-change-summary/findSummaries.response.schema.json) | 200, 400, 404 | safe read (GET) | none (bounded: one item per SummaryKind) |
| `ms-change-summary` | `getSummary` | `GET /v1/summaries/{summaryId}` | internal | [`getSummary.request.schema.json`](ms-change-summary/getSummary.request.schema.json) | [`getSummary.response.schema.json`](ms-change-summary/getSummary.response.schema.json) | 200, 404 | safe read (GET) | none (single resource) |
| `ms-change-summary` | `getSummaryDiff` | `GET /v1/summaries/{summaryId}/diff` | internal | [`getSummaryDiff.request.schema.json`](ms-change-summary/getSummaryDiff.request.schema.json) | [`getSummaryDiff.response.schema.json`](ms-change-summary/getSummaryDiff.response.schema.json) | 200, 400, 404 | safe read (GET) | cursor + limit <= N_pagemax; the cursor encodes the last diff path served (DISENO 3.8.5) |
| `ms-change-summary` | `reportSummary` | `POST /v1/summaries/{summaryId}/reports` | operator (content reviewers) | [`reportSummary.request.schema.json`](ms-change-summary/reportSummary.request.schema.json) | [`reportSummary.response.schema.json`](ms-change-summary/reportSummary.response.schema.json) | 201, 400, 404, 409 | Idempotency-Key required: reportId = UUID v5 of the key, so a repeated request returns the same report (DEC-35) | n/a |
| `ms-template-catalog` | `getTemplateGraph` | `GET /v1/templates/{templateId}/graph` | internal | [`getTemplateGraph.request.schema.json`](ms-template-catalog/getTemplateGraph.request.schema.json) | [`getTemplateGraph.response.schema.json`](ms-template-catalog/getTemplateGraph.response.schema.json) | 200, 400, 404 | safe read (GET) | sinceSeq + cursor + limit <= N_pagemax (DISENO 3.1 (e), 3.8.5) |
| `ms-publish-fanout` | `getJob` | `GET /v1/jobs/{jobId}` | operator | [`getJob.request.schema.json`](ms-publish-fanout/getJob.request.schema.json) | [`getJob.response.schema.json`](ms-publish-fanout/getJob.response.schema.json) | 200, 404 | safe read (GET) | none (single resource) |
| `ms-publish-fanout` | `pauseJob` | `POST /v1/jobs/{jobId}:pause` | operator | [`pauseJob.request.schema.json`](ms-publish-fanout/pauseJob.request.schema.json) | [`pauseJob.response.schema.json`](ms-publish-fanout/pauseJob.response.schema.json) | 200, 400, 404, 409 | Idempotency-Key required; idempotent transition (already PAUSED -> same 200) | n/a |
| `ms-publish-fanout` | `resumeJob` | `POST /v1/jobs/{jobId}:resume` | operator | [`resumeJob.request.schema.json`](ms-publish-fanout/resumeJob.request.schema.json) | [`resumeJob.response.schema.json`](ms-publish-fanout/resumeJob.response.schema.json) | 200, 400, 404, 409 | Idempotency-Key required; idempotent transition (already RUNNING -> same 200) | n/a |
| `ms-publish-fanout` | `cancelJob` | `POST /v1/jobs/{jobId}:cancel` | operator | [`cancelJob.request.schema.json`](ms-publish-fanout/cancelJob.request.schema.json) | [`cancelJob.response.schema.json`](ms-publish-fanout/cancelJob.response.schema.json) | 202, 400, 404, 409 | Idempotency-Key required; idempotent by jobId (already cancelled -> same 202); runs the same use case as CancelFanOutJob.v1 and sends no message (DEC-24) | n/a |
| `ms-publish-fanout` | `retryDeadTasks` | `POST /v1/jobs/{jobId}/dead-tasks:retry` | operator | [`retryDeadTasks.request.schema.json`](ms-publish-fanout/retryDeadTasks.request.schema.json) | [`retryDeadTasks.response.schema.json`](ms-publish-fanout/retryDeadTasks.response.schema.json) | 202, 400, 404, 409 | Idempotency-Key required and used as requestId (fanout_job.lastRetryRequestId); runs the same use case as RetryFanOutTasks.v1 and sends no message (DEC-24) | n/a |
| `ms-backfill-reconciler` | `startCampaign` | `POST /v1/campaigns` | operator | [`startCampaign.request.schema.json`](ms-backfill-reconciler/startCampaign.request.schema.json) | [`startCampaign.response.schema.json`](ms-backfill-reconciler/startCampaign.response.schema.json) | 201, 400, 409 | Idempotency-Key required: campaignId = UUID v5 of the key, so a repeated request returns the same campaign (DEC-35) | n/a |
| `ms-backfill-reconciler` | `pauseCampaign` | `POST /v1/campaigns/{campaignId}:pause` | operator | [`pauseCampaign.request.schema.json`](ms-backfill-reconciler/pauseCampaign.request.schema.json) | [`pauseCampaign.response.schema.json`](ms-backfill-reconciler/pauseCampaign.response.schema.json) | 200, 400, 404, 409 | Idempotency-Key required; idempotent transition (already PAUSED -> same 200); pausing cancels the in-flight fan-out jobs of the campaign (CancelFanOutJob.v1, DEC-24) | n/a |
| `ms-backfill-reconciler` | `resumeCampaign` | `POST /v1/campaigns/{campaignId}:resume` | operator | [`resumeCampaign.request.schema.json`](ms-backfill-reconciler/resumeCampaign.request.schema.json) | [`resumeCampaign.response.schema.json`](ms-backfill-reconciler/resumeCampaign.response.schema.json) | 200, 400, 404, 409 | Idempotency-Key required; idempotent transition (already RUNNING -> same 200); resuming retries FAILED tasks (RetryFanOutTasks.v1, DEC-24) | n/a |
| `ms-backfill-reconciler` | `getCampaign` | `GET /v1/campaigns/{campaignId}` | operator | [`getCampaign.request.schema.json`](ms-backfill-reconciler/getCampaign.request.schema.json) | [`getCampaign.response.schema.json`](ms-backfill-reconciler/getCampaign.response.schema.json) | 200, 404 | safe read (GET) | none (aggregates only) |
| `ms-backfill-reconciler` | `startReconciliationRun` | `POST /v1/reconciliation-runs` | operator | [`startReconciliationRun.request.schema.json`](ms-backfill-reconciler/startReconciliationRun.request.schema.json) | [`startReconciliationRun.response.schema.json`](ms-backfill-reconciler/startReconciliationRun.response.schema.json) | 201, 400, 409 | Idempotency-Key required: runId = UUID v5 of the key, so a repeated request returns the same run (DEC-35) | n/a |
| `ms-backfill-reconciler` | `getReconciliationRun` | `GET /v1/reconciliation-runs/{runId}` | operator | [`getReconciliationRun.request.schema.json`](ms-backfill-reconciler/getReconciliationRun.request.schema.json) | [`getReconciliationRun.response.schema.json`](ms-backfill-reconciler/getReconciliationRun.response.schema.json) | 200, 404 | safe read (GET) | none (single resource) |
| `ms-audit-ledger` | `getSummaryEvidence` | `GET /v1/evidence/summaries/{summaryId}` | operator (compliance) | [`getSummaryEvidence.request.schema.json`](ms-audit-ledger/getSummaryEvidence.request.schema.json) | [`getSummaryEvidence.response.schema.json`](ms-audit-ledger/getSummaryEvidence.response.schema.json) | 200, 400, 404 | safe read (GET); the read itself is audited | cursor + limit <= N_pagemax (DISENO 3.6 (e), 3.8.5) |
| `ms-audit-ledger` | `listEngagementEvidence` | `GET /v1/evidence/engagements/{engagementId}` | operator (compliance) | [`listEngagementEvidence.request.schema.json`](ms-audit-ledger/listEngagementEvidence.request.schema.json) | [`listEngagementEvidence.response.schema.json`](ms-audit-ledger/listEngagementEvidence.response.schema.json) | 200, 400 | safe read (GET); the read itself is audited | cursor + limit <= N_pagemax (DISENO 3.6 (e), 3.8.5) |

### Consumed contracts (external systems, not our APIs)

| SX | Caller | Operation | Request (client expectation) | Response (client expectation) | Idempotency |
| --- | --- | --- | --- | --- | --- |
| SX-03 (S4 → external downstream) | `ms-publish-fanout` | `inspectEngagement` | [`inspectEngagement.request.schema.json`](_consumed/engagements-system/inspectEngagement.request.schema.json) | [`inspectEngagement.response.schema.json`](_consumed/engagements-system/inspectEngagement.response.schema.json) | `idempotencyKey = jobId:engagementId:targetVersion` (DEC-30); declared assumption that the downstream honours it (SUP-08 b) |
| SX-04 (S5 → external catalog) | `ms-backfill-reconciler` | `listCatalogEngagements` | [`listCatalogEngagements.request.schema.json`](_consumed/engagements-system/listCatalogEngagements.request.schema.json) | [`listCatalogEngagements.response.schema.json`](_consumed/engagements-system/listCatalogEngagements.response.schema.json) | safe read |
| SX-05 (S2 → existing comparator) | `ms-change-summary` | `compareTemplateVersions` | [`compareTemplateVersions.request.schema.json`](_consumed/template-comparator/compareTemplateVersions.request.schema.json) | [`compareTemplateVersions.response.schema.json`](_consumed/template-comparator/compareTemplateVersions.response.schema.json) | safe read |

These schemas have no `method` or `path`: the transport, authentication and error bodies of the external systems belong to their owners. They state what the caller sends and needs back. Status 400 stands for "4xx, not retried" and 503 for "5xx/timeout/throttling, retried up to `N_reint`" (FW-019).

### Synchronous exceptions coverage (DISENO §4.8, ADR-020)

| SX | Caller → callee | Contract(s) | Fallback (in the caller) |
| --- | --- | --- | --- |
| SX-01a | S7 → S3 (same cell) | S3 `listFirmEngagements`, `getFirmCounters`, `getEngagementPendingUpdate` (S7 `EngagementIndexClient`) | 503 `dependency-unavailable` with `retry-after`; the UI never shows "up to date" |
| SX-01b | S7 → S2 (cross-region, non-confidential) | S2 `findSummaries`, `getSummary`, `getSummaryDiff` (behind S7 `getPendingUpdateView`, `getPendingUpdateDiff`, `getPendingUpdateBreakdown`) | Cell Redis cache (immutable content, stale-while-error), otherwise `summaryStatus = UNAVAILABLE` ("summary not available, see diff") |
| SX-02 | S3 → S1 | S1 `getTemplateGraph` | Template marked `GRAPH_STALE`; no new offers for that template |
| SX-03 | S4 → Engagements System downstream | consumed `inspectEngagement` | In-thread retry with backoff (permit released); circuit open → no claims (tasks stay `PENDING`); exhausted → `FAILED` + logical DLQ |
| SX-04 | S5 → Engagements System catalog (SUP-08) | consumed `listCatalogEngagements` | Pause the saga |
| SX-05 | S2 → template comparator | consumed `compareTemplateVersions` | `ChangeSummaryFailed.v1` + deferred retry |
| SX-06 | S6 → S2 (regulatory reconstruction only) | S2 `getSummary` (with `CircuitBreaker(ms-change-summary-api)`) | Local `summary_provenance` + content from S2's WORM store |

## Conventions (DISENO §3.8.5, DEC-35)

- **Versioning:** major version in the path (`/v1`, RF-120, RNF-042). Additive changes only within `v1`; a breaking change means a `/v2` served in parallel.
- **Pagination:** every collection without a natural bound takes `cursor` (opaque) and `limit` (1 … `N_pagemax`, a named parameter without value) and returns `{items, nextCursor}` (`nextCursor = null` on the last page): S1 `getTemplateGraph`, S2 `getSummaryDiff`, S3 `listFirmEngagements`, S6 evidence, S7 `listEngagements`, `listNotifications`, `getPendingUpdateDiff`. Naturally bounded lists (counters per product, summaries per `SummaryKind`, publish units, breakdown steps, decline history) are not paged. A cursor is only valid in the cell that issued it; S3's cursor encodes the position per shard (RF-034).
- **Errors:** RFC 9457 (`application/problem+json`) with the extension `correlationId`. Problem types: `400 invalid-request`; `404 not-found` (S2 and the S7 diff also `summary-pending` while the summary is generated); `409 invalid-state` or `idempotency-key-reuse`; `503 dependency-unavailable` with `retry-after`. Each response schema pins the allowed `type` values per status. A fallback never invents state.
- **Idempotency-Key:** required on every write (`POST`): same key + same body → same effect and same response; same key + another body → `409 idempotency-key-reuse`. Creating operations derive the resource id from the key (UUID v5: `jobId` of `reverifyEngagement`, `campaignId`, `runId`, `reportId`), so no idempotency table is needed; `retryDeadTasks` uses the key as `requestId` (`fanout_job.lastRetryRequestId`, same field as CMD-06); pause, resume and cancel are idempotent transitions. Operator writes run the same use cases as the catalog commands and send no message (DEC-24); `startReconciliationRun` creates the run, which emits CMD-07.
- **Correlation:** `correlation-id` is echoed in every response and in Problem Details and becomes `EventEnvelope.correlationId` of any message the call causes; S7 generates it when the UI omits it. `traceparent` (W3C) is optional.
- **Residency:** S3, S4, S5, S6 and S7 are per cell; `ENGAGEMENT_REGIONAL` data never leaves the cell (RF-092, RF-093). S7 takes `firmId`/`userId` from the authenticated context, never from the URL. S1 and S2 are global and serve only `TEMPLATE_PUBLIC` content; their requests and responses carry no `engagementId`, `firmId` or `userId` (including `reportSummary`, whose body is a reason code without free text).
- **Authorization:** provided by the platform; authentication and authorization of end users are out of scope (DESIGN_DOC "deliberately left out"), so no authorization header is modelled.

## View models (DISENO §3.8.6, DEC-36)

| Model | File | Served by |
| --- | --- | --- |
| `EngagementListItem`, `Offer` | `_shared/EngagementListItem.schema.json`, `_shared/Offer.schema.json` | S3 `listFirmEngagements`, S7 `listEngagements` |
| `EngagementPendingUpdate` (with `declineHistory`, `intermediateVersions`) | `_shared/EngagementPendingUpdate.schema.json` | S3 `getEngagementPendingUpdate` |
| `PendingUpdateView`, `FreshnessBadge` | `_shared/PendingUpdateView.schema.json`, `_shared/FreshnessBadge.schema.json` | S7 `getPendingUpdateView` |
| `SummaryRecord` | `_shared/SummaryRecord.schema.json` | S2 `findSummaries`, `getSummary`; embedded in S7 |
| `DiffPage`, `DiffEntry` | `_shared/DiffPage.schema.json` | S2 `getSummaryDiff`, S7 `getPendingUpdateDiff` |
| `BreakdownStep` | `_shared/BreakdownStep.schema.json` | S7 `getPendingUpdateBreakdown` |
| `FirmCounters` | `_shared/FirmCounters.schema.json` | S3 `getFirmCounters`, S7 `getCounters` |
| `NotificationItem` | `_shared/NotificationItem.schema.json` | S7 `listNotifications`, `streamNotifications` |
| `FanOutJob`, `RetryAccepted` | `$defs` of the S4 responses | S4 job operations |
| `BackfillCampaign`, `ReconciliationRun` | `$defs`/bodies of the S5 responses | S5 operations |
| `PublishProgress`, `TemplateGraphPage`, evidence entries | bodies of the S3, S1, S6 responses | S3 `getPublishProgress`, S1 `getTemplateGraph`, S6 evidence |

## How requests and responses relate to events and data

| API | Reads / writes | Related messages |
| --- | --- | --- |
| S3 `listFirmEngagements`, `getEngagementPendingUpdate` | `engagement_state` (GSI2 AP-I4/AP-I10, GetItem AP-I1); `offerTarget/offerSince/offerStatus/previouslyDeclined` exposed as `Offer`, item `state` as `classification` (`ARCHIVED` is not listed) | Projection fed by EV-03/04, EV-08…EV-15 |
| S3 `getFirmCounters`, S7 `getCounters` | `firm_counters` shards summed (AP-I5) | EV-22 `FirmPendingUpdatesChanged.v1` deltas → S7 notification |
| S3 `getPublishProgress` | `publish_progress` units (AP-I7) | EV-23 `PublishProjectionCompleted.v1` |
| S7 `getPendingUpdateView` | S3 state + S2 `SummaryRecord` (cell cache) + `FreshnessBadge` | Emits EV-28 `SummaryViewed.v1` (`view_outbox`); cache invalidated by EV-07; CMD-01 when no summary exists |
| S7 `getPendingUpdateDiff`, `getPendingUpdateBreakdown` | S3 `intermediateVersions` + S2 `/diff` and `/summaries` per pair | CMD-01 for a missing step summary |
| S7 `listNotifications`, `streamNotifications` | `notifications` (AP-G2), `connections` | `NotificationItem.id` = consumed `eventId` (EV-19…EV-22, EV-05…EV-07); SSE `id` = `Last-Event-ID` |
| S7 `reverifyEngagement` | — | CMD-03 `StartFanOutJob.v1` + CMD-04 `EnqueueFanOutTasks.v1` (priority `INTERACTIVE`) → EV-15 |
| S2 `findSummaries`, `getSummary`, `getSummaryDiff`, `reportSummary` | `summary_current` (AP-S1), `summary_record` (AP-S2), `summary_invalidation`, `diffs/{diffHash}` (AP-S3), `summary_report` | Same provenance fields as EV-05 |
| S1 `getTemplateGraph` | `template_version` from `seq > sinceSeq` (AP-R4) | Same node fields as EV-03/EV-04 |
| S4 job operations | `fanout_job` (AP-F6, AP-F7), `fanout_task` (AP-F8); `status` = `fanout_job.state` | Same use cases as CMD-05/CMD-06 (no message); EV-26 `FanOutCompleted.v1` |
| S5 campaigns and runs | `campaign`, `firm_progress` (AP-D3), `reconciliation_run` | EV-13, EV-27; pause → CMD-05, resume → CMD-06, runs → CMD-07 |
| S6 evidence | `summary_view`, `decision`, `offer_lifecycle` (AP-L1, AP-L2), `summary_provenance` | Entries from EV-28, EV-20, EV-17…EV-19/EV-21; provenance from EV-05 |

Where DISENO's domain language says `status` (FanOutJob, BackfillCampaign, ReconciliationRun, Offer), the API uses `status` with the values of the persisted `state` / `offerStatus` (DISENO §3.8.2).

## Decisions applied (DISENO 0.2.2)

| Topic | Decision | DEC |
| --- | --- | --- |
| List filters | `offerStatus` (one or more, `NONE` = no offer), `classification`, `templateId`, `branch`, `currentVersion`, `targetVersion`, `offerSinceFrom`/`offerSinceTo` | DEC-31 |
| Writes vs. read-only rule | Reads plus operator writes (S4, S5, S2 reviewers' reports) and S7 reverification; `reportSummary` is called by content reviewers, not by S7 | DEC-32 |
| Publish progress (RF-022) | Projection part in S3 `getPublishProgress` (operator), fan-out part in S4 `getJob` (`jobId = publishId`) | DEC-33 |
| Diff and breakdown | S7 `getPendingUpdateDiff` and `getPendingUpdateBreakdown`, with `intermediateVersions` from S3 | DEC-34 |
| Commands without HTTP | CMD-02 is sent by S2's evaluation pipeline; CMD-07 by S5 runs (`startReconciliationRun` for operators); SQS DLQ redrive is a runbook procedure (DISENO §4.4) | DEC-25 |
| Pagination, errors, Idempotency-Key, authorization | DISENO §3.8.5 | DEC-35 |
| View-model names and fields | DISENO §3.8.6 | DEC-36 |

**Declared assumptions** (other teams' systems, DESIGN_DOC §5): the enumerable catalog behind SX-04 (SUP-08 a; if absent, SX-04 disappears and backfill relies on hooks and inspection); an idempotent downstream behind SX-03 (SUP-08 b; if not, a timed-out task is not retried and goes to the DLQ); the comparator's output shape is owned by its team (SX-05).

## How to validate

```bash
python3 validate_apis.py docs/en/data/apis   # jsonschema 4.x + referencing
```

The validator (kept outside the repo, in the session scratchpad) checks: every file parses; every schema passes `Draft202012Validator.check_schema`; `examples[0]` validates with `FormatChecker` and cross-file `$ref`s resolved through a `referencing.Registry` keyed by `$id`; every `$ref` resolves; every object schema has `additionalProperties: false`; `$id` = path; `$comment` with Source/Audience/Residency; negative cases (unknown member, wrong `status`, wrong `method`, non-UUID path param) are rejected; every request has its response; no duplicate operationId; all method+path pairs of the DISENO (e) sections are covered; each of SX-01a…SX-06 is referenced.

Result (DISENO 0.2.2): 79 schema files, 33 operations (30 HTTP + 3 consumed), 29 distinct method+path pairs (the `pending-update` path exists in both S3 and S7), 0 errors.

The equivalent minimal check in Python:

```python
from jsonschema import Draft202012Validator, FormatChecker
from referencing import Registry, Resource
reg = Registry().with_resources((s["$id"], Resource.from_contents(s)) for s in all_schemas)
Draft202012Validator.check_schema(schema)
Draft202012Validator(schema, registry=reg, format_checker=FormatChecker()).validate(schema["examples"][0])
```
