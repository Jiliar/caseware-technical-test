# Data contracts

This folder holds the data contracts of the solution: the relational data models, the DynamoDB models, the message contracts (events and commands) and the synchronous API contracts, organised per microservice (`ms-*`).
Data models are drawn as PDF diagrams (`postgresql/`, `dynamo/diagrams/`, `events/topology.pdf`).
Every other contract is a JSON Schema (Draft 2020-12), one file per entity, item type, message or operation message.
Service aliases used in the diagrams and schemas: S1 `ms-template-catalog`, S2 `ms-change-summary`, S3 `ms-engagement-index`, S4 `ms-publish-fanout`, S5 `ms-backfill-reconciler`, S6 `ms-audit-ledger`, S7 `ms-experience-gateway`.

## What is where

```text
data/
├── README.md
├── postgresql/                          4 PDF ER diagrams (S1, S2, S5, S6)
├── dynamo/
│   ├── diagrams/                        3 PDF table diagrams (S3, S4, S7)
│   ├── schemas/<service>/               14 JSON Schemas, one per item type (S3: 8, S4: 3, S7: 3)
│   └── examples/<service>/              13 example items (S3: 7, S4: 3, S7: 3)
├── events/                              40 files
│   ├── README.md                        catalog, topology, ordering, versioning
│   ├── topology.pdf                     buses, rules, topics, queues, DLQs
│   ├── envelope.schema.json
│   ├── transport-attributes.schema.json
│   └── <Name>.v1.schema.json            (36 files: 29 events + 7 commands)
└── apis/                                80 files
    ├── README.md                        index of operations, conventions, view models
    ├── _shared/                         13 shared schemas
    ├── _consumed/                       6 files: contracts of external systems we call
    │   ├── engagements-system/          4 (2 operations)
    │   └── template-comparator/         2 (1 operation)
    └── ms-*/                            60 files: <operation>.request/response.schema.json (30 operations)
        ├── ms-experience-gateway/       16 (8 operations)
        ├── ms-engagement-index/         8 (4)
        ├── ms-change-summary/           8 (4)
        ├── ms-template-catalog/         2 (1)
        ├── ms-publish-fanout/           10 (5)
        ├── ms-backfill-reconciler/      12 (6)
        └── ms-audit-ledger/             4 (2)
```

| Folder | Description |
| --- | --- |
| `postgresql/` | ER diagrams of the services that store data in PostgreSQL (Aurora) |
| `dynamo/diagrams/` | Table and index diagrams of the services that store data in DynamoDB |
| `dynamo/schemas/` | JSON Schema of each DynamoDB item type, grouped by service |
| `dynamo/examples/` | One valid example item per item type, grouped by service |
| `events/` | Contracts of every event and command, plus the common envelope, transport attributes and broker topology |
| `apis/` | Request and response contracts of the synchronous APIs, shared schemas and consumed external contracts |

## `postgresql/`

**Purpose.** ER diagrams (PDF) of the four services with relational storage: S1 `ms-template-catalog` (version DAG with ancestry queries), S2 `ms-change-summary` (append-only summaries, prompts, evaluations), S5 `ms-backfill-reconciler` (campaign and reconciliation state machines updated transactionally, `FOR UPDATE SKIP LOCKED` batches) and S6 `ms-audit-ledger` (append-only, partitioned evidence with constraints and ranges). They need joins, `CHECK` constraints and transactional state transitions. Each diagram shows columns, PK/FK/CHECK marks, the `state` values and lifecycles, and an index list mapped to access patterns. Every table has the audit fields and `state`; every service also has an `outbox`.

| File | What it contains |
| --- | --- |
| `ms-template-catalog.pdf` | S1, global deployment (template metadata only). Tables: `template` (with `last_seq`), `template_version` (`PUBLISHED -> WITHDRAWN`, `publish_id`, `seq`), `version_edge` (parent/child), `version_ancestry` (ancestor/descendant/depth, O(1) `contains`), `quarantine` (versions waiting for a missing parent: `QUARANTINED -> RELEASED \| DEAD_LETTERED`), `outbox`. Access patterns AP-R1..R5 (idempotent insert, ancestry check, heads per branch, graph since `seq`, release from quarantine) |
| `ms-change-summary.pdf` | S2, global deployment, blobs in S3 Object Lock. Tables: `diff_snapshot` (PK `diff_hash`), `prompt_version` (`CANDIDATE -> APPROVED -> RETIRED`), `evaluation_run`, `generation_job` (single-flight by `summary_key_hash`), `summary_record` (append-only), `summary_current` (pointer per summary key and kind), `summary_invalidation` (append-only), `llm_spend_daily`, `summary_report` (reviewer reports, reason `INCORRECT \| UNCLEAR`), `golden_case`, `outbox`. Access patterns AP-S1..S5 |
| `ms-backfill-reconciler.pdf` | S5, per cell. Tables: `campaign` (`CATALOG \| EXPORT`, `RUNNING <-> PAUSED`, `COMPLETED`, with `version`), `firm_progress` (per-firm saga: `PENDING -> IN_PROGRESS -> COMPLETED`, `current_job_id`), `reconciliation_run` (`EXPORT \| SAMPLE \| TARGETED`), `drift_finding` (`VERSION_MISMATCH \| MISSING_IN_INDEX \| ARCHIVED_IN_SOURCE`), `inbox` (processed events for dedupe), `outbox`. Access patterns AP-D1..D4 |
| `ms-audit-ledger.pdf` | S6, per cell, plus S3 Object Lock. Tables (monthly partitions by `occurred_at`): `summary_view`, `decision` (`APPLIED \| DECLINED`, discrepancy `NONE \| STALE_OFFER \| APPLIED_WITHDRAWN`), `offer_lifecycle` (`OFFERED \| SUPERSEDED \| WITHDRAWN \| APPLIED_WITHDRAWN`), `control_evidence` (`RECONCILIATION_RUN`), `outbox`. Non-partitioned: `summary_provenance` (`DETERMINISTIC \| NARRATIVE`, `ACTIVE -> INVALIDATED`), `sealed_batch` (daily Merkle root and S3 key). Access patterns AP-L1..L4 |

**How the files relate.** One diagram per service; its tables map to the `outbox` events in `events/` and to the reads in `apis/<service>/`. Each service diagram is independent: there are no cross-service foreign keys.

## `dynamo/`

**Purpose.** Models of the three services stored in DynamoDB on-demand, multi-table, per cell: S3 `ms-engagement-index`, S4 `ms-publish-fanout` and S7 `ms-experience-gateway`. They are key-value or short-range access patterns with very high write rates, TTL and conditional writes (projection of engagements, work queues with leases, notifications and connections), where PostgreSQL joins are not needed. The folder has three parts: diagrams (tables, keys, indexes, access patterns), schemas (item attributes) and examples.

### `dynamo/diagrams/`

| File | What it contains |
| --- | --- |
| `ms-engagement-index.pdf` | S3 tables: `engagement_state` (PK `ENG#<engagementId>`, SK `STATE`, GSI1 by template and version, GSI2 by firm and offer status, both sharded), `template_graph_replica` (`TemplateGraphNode` and `TemplateGraphMeta`), `firm_counters` (sharded counters), `publish_progress`, `processed_events` (dedupe, TTL), `outbox` (TTL). Access patterns AP-I1..I10 (get state, affected engagements by template version, materialise offer, paginated firm listing, counters, apply graph event, publish progress, dedupe, `APPLIED_WITHDRAWN`, up to date) |
| `ms-publish-fanout.pdf` | S4 tables: `fanout_job` (PK `JOB#<jobId>`, SK `META`, counters, `cancelRequested`), `fanout_firm_queue` (PK `FQ#<priority>`, SK `FIRM#<firmId>`, deficit round-robin), `fanout_task` (PK `TASK#<jobId>#<engagementId>`, three sparse GSIs: ready, leases, failed). Access patterns AP-F1..F8 (idempotent task create, next firm, ready tasks, claim with lease, recover leases, cancel, progress, retry failed) |
| `ms-experience-gateway.pdf` | S7 tables: `connections` (PK `CONN#<connectionId>`, GSI1 by firm, TTL = session), `notifications` (PK `FIRM#<firmId>`, SK `<createdAt>#<id>`), `view_outbox` (PK `VIEW#<eventId>`, Stream relay of `SummaryViewed.v1`). Access patterns AP-G1..G3 (connections of a firm, paginated inbox, record a view) |

### `dynamo/schemas/<service>/`

One JSON Schema per item type: it defines the key attributes (`PK`, `SK`, GSI keys), the `entityType` discriminator constant, the item attributes, the audit fields, `version` and `ttl` where applicable. The exception is `template_graph_replica`, where one table-level schema is a `oneOf` over the two item-type schemas.

| File | What it contains |
| --- | --- |
| `ms-engagement-index/engagement_state.schema.json` | `EngagementState`: real base version, single offer (`offerTarget`, `offerSince`, `offerStatus`), decline history, sequence guards (`lastSeq`, `lastHookAt`). Keys: PK `ENG#<engagementId>`, SK `STATE`, GSI1 `TV#<templateId>#<currentVersion>#S<shard>`, GSI2 `FIRM#<firmId>#S<shard>`. `state`: `UNCLASSIFIED \| CLASSIFIED \| ARCHIVED` |
| `ms-engagement-index/firm_counters.schema.json` | `FirmCounterShard`: sharded counters `pending`, `declined`, `unclassified` per firm and template. Keys: PK `CNT#<firmId>#<templateId>`, SK `S<n>`. No TTL |
| `ms-engagement-index/outbox.schema.json` | `OutboxEvent`: events that are not an item transition (`FirmPendingUpdatesChanged`, `PublishProjectionCompleted`), left through the table Stream. Key: PK `OUTBOX#<eventId>` only; TTL |
| `ms-engagement-index/processed_events.schema.json` | `ProcessedEvent`: dedupe by `eventId`. Key: PK `EVT#<eventId>` only; TTL |
| `ms-engagement-index/publish_progress.schema.json` | `PublishProgressUnit`: `expected` vs `processed` per `(publishId, sourceVersion, shard)`; `IN_PROGRESS \| COMPLETED`. Keys: PK `PUB#<publishId>`, SK `UNIT#<sourceVersion>#S<shard>`; TTL |
| `ms-engagement-index/template_graph_replica.schema.json` | Table-level schema: `oneOf` (`TemplateGraphNode`, `TemplateGraphMeta`) discriminated by `entityType` |
| `ms-engagement-index/template_graph_replica.TemplateGraphNode.schema.json` | `TemplateGraphNode`: one version node (`parents`, `branch`, `seq`, `state` `PUBLISHED \| WITHDRAWN`). Keys: PK `TPL#<templateId>`, SK `V#<templateVersion>` |
| `ms-engagement-index/template_graph_replica.TemplateGraphMeta.schema.json` | `TemplateGraphMeta`: per-template `appliedSeq` and staleness (`state`). Keys: PK `TPL#<templateId>`, SK `META` |
| `ms-publish-fanout/fanout_job.schema.json` | `FanOutJob`: job with counters (`expected`, `done`, `failed`, `obsolete`), `priority`, `cancelRequested`, `lastRetryRequestId`; `state` `RUNNING \| PAUSED \| COMPLETED \| PARTIAL \| CANCELLED`. Keys: PK `JOB#<jobId>`, SK `META`; TTL after the job ends |
| `ms-publish-fanout/fanout_firm_queue.schema.json` | `FanOutFirmQueue`: firms with ready work per priority (`readyCount`, `deficit`). Keys: PK `FQ#<priority>`, SK `FIRM#<firmId>` |
| `ms-publish-fanout/fanout_task.schema.json` | `FanOutTask`: one task per `(jobId, engagementId)` with lease, attempts, failure/obsolete reason; `state` `PENDING \| IN_PROGRESS \| DONE \| FAILED \| OBSOLETE`. Keys: PK `TASK#<jobId>#<engagementId>`, SK `TASK`; sparse GSI1 (ready), GSI2 (leases), GSI3 (failed) |
| `ms-experience-gateway/connections.schema.json` | `Connection`: SSE connection (`connectionId`, `firmId`, `userId`); `ttl` = expiry. Keys: PK `CONN#<connectionId>`, GSI1PK `FIRM#<firmId>` |
| `ms-experience-gateway/notifications.schema.json` | `NotificationItem`: inbox entry (`kind`, `refs`); `id` = source `eventId`. Keys: PK `FIRM#<firmId>`, SK `<createdAt>#<id>`; TTL |
| `ms-experience-gateway/view_outbox.schema.json` | `SummaryViewOutbox`: record of a summary view (`summaryId`, `engagementId`, `userId`, `viewedAt`, `kind`, `renderedHash`). Key: PK `VIEW#<eventId>` only; TTL |

### `dynamo/examples/<service>/`

Named `<table>.<ItemType>.example.json`: a sample item that conforms to the schema of the same table and item type.

| File | Item type it illustrates |
| --- | --- |
| `ms-engagement-index/engagement_state.EngagementState.example.json` | `EngagementState` |
| `ms-engagement-index/firm_counters.FirmCounterShard.example.json` | `FirmCounterShard` (shard `S3`) |
| `ms-engagement-index/outbox.OutboxEvent.example.json` | `OutboxEvent` |
| `ms-engagement-index/processed_events.ProcessedEvent.example.json` | `ProcessedEvent` |
| `ms-engagement-index/publish_progress.PublishProgressUnit.example.json` | `PublishProgressUnit` (`UNIT#v3#S7`) |
| `ms-engagement-index/template_graph_replica.TemplateGraphMeta.example.json` | `TemplateGraphMeta` (SK `META`) |
| `ms-engagement-index/template_graph_replica.TemplateGraphNode.example.json` | `TemplateGraphNode` (SK `V#v7`) |
| `ms-experience-gateway/connections.Connection.example.json` | `Connection` |
| `ms-experience-gateway/notifications.NotificationItem.example.json` | `NotificationItem` |
| `ms-experience-gateway/view_outbox.SummaryViewOutbox.example.json` | `SummaryViewOutbox` |
| `ms-publish-fanout/fanout_firm_queue.FanOutFirmQueue.example.json` | `FanOutFirmQueue` (`FQ#PUBLISH`) |
| `ms-publish-fanout/fanout_job.FanOutJob.example.json` | `FanOutJob` |
| `ms-publish-fanout/fanout_task.FanOutTask.example.json` | `FanOutTask` |

**How the files relate.** For each service, the diagram gives the tables, keys and access patterns; each item type then has one schema (keys + item attributes) and one example. A DynamoDB schema = key attributes (`PK`, `SK`, GSI keys, built from ids as in the diagram) + `entityType` + business attributes + audit fields + `version` (+ `ttl`). The 14 schemas cover 14 item types; the 13 examples cover all item types except the table-level union `template_graph_replica.schema.json`.

## `events/`

**Purpose.** Contracts of every asynchronous message exchanged over EventBridge, SNS and SQS: 29 events (EV-01..EV-29) and 7 commands (CMD-01..CMD-07). Events state a fact in the past tense; commands are point-to-point requests to one receiver. `events/README.md` holds the full catalog with queues, topics, ordering key, idempotency key and delivery guarantee per message.

### Shared files

| File | What it contains |
| --- | --- |
| `README.md` | Catalog of the 36 messages (producer, consumers, data class, topic, queue, ordering and idempotency keys), the topology, the envelope fields and the versioning rules |
| `envelope.schema.json` | `EventEnvelope`: common metadata of every message (`eventId`, `eventType`, `schemaVersion`, `occurredAt`, `producer`, `correlationId`, `causationId`, `cellId`, `traceparent`, `kind`, `payload`) and shared `$defs`: `uuid`, `instant`, `versionLabel`, `dataClass`, `priorityClass`, `summaryKind` |
| `transport-attributes.schema.json` | `TransportAttributes`: SNS/SQS message attributes that accompany the envelope, its mapping to the envelope, FIFO send parameters and ordering key per stream |
| `topology.pdf` | Broker topology: global bus and cell bus (EventBridge), rules, SNS topics of S3/S4/S5/S7, SQS queues and their DLQs, per producer and consumer |

### Message schemas `<Name>.v1.schema.json` (36 files)

Each one is the envelope plus a constrained `payload`, and carries `x-producers` and `x-consumers`.

**Hooks from external systems (producer: `templates-service` or `engagements-system`)**

| File | Producer -> consumers | Meaning |
| --- | --- | --- |
| `TemplateVersionPublished.v1.schema.json` | templates-service -> S1 | EV-01. A template version was published; raw input of the version DAG |
| `TemplateVersionWithdrawn.v1.schema.json` | templates-service -> S1 | EV-02. A published version was withdrawn (permanent) |
| `EngagementCreated.v1.schema.json` | engagements-system -> S3 | EV-08. An engagement was created from a template version (its base version) |
| `EngagementUpdateApplied.v1.schema.json` | engagements-system -> S3 | EV-09. A user applied a pending update; the base version moves |
| `EngagementUpdateDeclined.v1.schema.json` | engagements-system -> S3 | EV-10. A user declined a pending update; the base version does not change |
| `EngagementTemplateObserved.v1.schema.json` | engagements-system -> S3 | EV-11. An engagement was opened and reports its template and version (classifies it) |
| `EngagementArchived.v1.schema.json` | engagements-system -> S3 | EV-12. An engagement was archived; S3 writes a tombstone |

**S1 `ms-template-catalog`**

| File | Producer -> consumers | Meaning |
| --- | --- | --- |
| `VersionGraphNodeAdded.v1.schema.json` | S1 -> S3, S2 | EV-03. A validated, sequenced node was added to the version DAG |
| `VersionGraphNodeWithdrawn.v1.schema.json` | S1 -> S3, S2, S4 | EV-04. A version node was withdrawn; S2 invalidates summaries, S3 retargets offers, S4 cancels in-flight work |

**S2 `ms-change-summary`**

| File | Producer -> consumers | Meaning |
| --- | --- | --- |
| `ChangeSummaryReady.v1.schema.json` | S2 -> S7, S6 | EV-05. A summary was generated; carries provenance only (hashes, prompt version, model id) |
| `ChangeSummaryFailed.v1.schema.json` | S2 -> S7 | EV-06. Generation failed for a summary key; a retry is scheduled (`retryAt`) |
| `ChangeSummaryInvalidated.v1.schema.json` | S2 -> S7, S6 | EV-07. A summary was invalidated because a covered version was withdrawn |
| `RegenerateChangeSummary.v1.schema.json` | S2 -> S2 | CMD-02. Regenerate a summary with a new prompt or model (creates a new record) |

**S3 `ms-engagement-index`**

| File | Producer -> consumers | Meaning |
| --- | --- | --- |
| `EngagementClassificationRequired.v1.schema.json` | S3 -> S5 | EV-14. Batch of discovered engagements still `UNCLASSIFIED` that need inspection |
| `EngagementClassified.v1.schema.json` | S3 -> S5 | EV-16. An engagement became `CLASSIFIED`; measures backfill coverage |
| `PendingUpdateOffered.v1.schema.json` | S3 -> S6 | EV-17. A pending offer was materialised for an engagement |
| `PendingUpdateSuperseded.v1.schema.json` | S3 -> S6 | EV-18. An offer was replaced by a newer reachable target |
| `PendingUpdateWithdrawn.v1.schema.json` | S3 -> S6, S7 | EV-19. The offer target was withdrawn; offer removed or retargeted (`newTarget`) |
| `DecisionRecorded.v1.schema.json` | S3 -> S6, S7 | EV-20. An apply or decline decision was captured, with any discrepancy against the offer |
| `AppliedWithdrawnFlagged.v1.schema.json` | S3 -> S7, S6 | EV-21. An engagement had applied a version that is now withdrawn (flag, nothing reverted) |
| `FirmPendingUpdatesChanged.v1.schema.json` | S3 -> S7 | EV-22. Per-firm counter deltas that drive the UI indicator; no engagement list |
| `PublishProjectionCompleted.v1.schema.json` | S3 -> S5 | EV-23. All projection units of a publish closed in this cell (may be `INCOMPLETE`) |
| `ProjectionDriftDetected.v1.schema.json` | S3 -> S5 | EV-24. Projected version differs from the observed one; records whether repaired |

**S4 `ms-publish-fanout`**

| File | Producer -> consumers | Meaning |
| --- | --- | --- |
| `EngagementVersionInspected.v1.schema.json` | S4 -> S3 | EV-15. Result of the downstream inspection of one engagement (template and version in use) |
| `FanOutTaskDeadLettered.v1.schema.json` | S4 -> S5 | EV-25. A task exhausted its attempts |
| `FanOutCompleted.v1.schema.json` | S4 -> S3, S5 | EV-26. A job finished (`COMPLETED`, `PARTIAL` or `CANCELLED`) with final counters |

**S5 `ms-backfill-reconciler`**

| File | Producer -> consumers | Meaning |
| --- | --- | --- |
| `EngagementDiscovered.v1.schema.json` | S5 -> S3 | EV-13. Batch of existing engagements found by a campaign or reconciliation |
| `ReconciliationRunCompleted.v1.schema.json` | S5 -> S6 | EV-27. A reconciliation run finished; stored as control evidence |
| `CancelFanOutJob.v1.schema.json` | S5 -> S4 | CMD-05. Cancel a fan-out job (sent when a campaign is paused) |
| `RetryFanOutTasks.v1.schema.json` | S5 -> S4 | CMD-06. Retry `FAILED` tasks of a job (sent when a campaign is resumed) |
| `RecomputeEngagementOffers.v1.schema.json` | S5 -> S3 | CMD-07. Recompute offers for a template, a firm or a list of engagements |

**S6 `ms-audit-ledger` and S7 `ms-experience-gateway`**

| File | Producer -> consumers | Meaning |
| --- | --- | --- |
| `AuditBatchSealed.v1.schema.json` | S6 -> none (archive only) | EV-29. The daily batch of audit entries was sealed (Merkle root); batch metadata only |
| `SummaryViewed.v1.schema.json` | S7 -> S6 | EV-28. A user was shown a change summary (regulatory evidence) |

**Commands sent by more than one producer**

| File | Producer -> consumers | Meaning |
| --- | --- | --- |
| `GenerateChangeSummary.v1.schema.json` | S3, S7 -> S2 | CMD-01. Generate (single-flight) the summaries of a version pair; crosses to the global region, so it carries no engagement, firm or user data |
| `StartFanOutJob.v1.schema.json` | S3, S5, S7 -> S4 | CMD-03. Create a fan-out job (idempotent by `jobId`) |
| `EnqueueFanOutTasks.v1.schema.json` | S3, S5, S7 -> S4 | CMD-04. Add a chunk of engagement references as tasks of a job |

Count check: 7 (hooks) + 2 + 4 + 10 + 3 + 5 + 2 + 3 = 36.

**How the files relate.** A message schema = `envelope.schema.json` (via `allOf`) + pinned `eventType`, `schemaVersion`, `kind` and `producer` + a closed `payload`. The routing metadata in `x-*` annotations matches the catalog in `README.md` and the diagram in `topology.pdf`. `transport-attributes.schema.json` describes what travels beside the body as SNS/SQS attributes.

## `apis/`

**Purpose.** Contracts of the synchronous APIs (30 operations) and of the external operations our services call (3). Communication between services is asynchronous by default; synchronous traffic is limited to the public API of S7 for the UI, operator APIs (S2 reports, S3 progress, S4 jobs, S5 campaigns and runs, S6 evidence) and a few internal reads (S3, S2, S1). Each operation has a request schema and a response schema. `apis/README.md` has the full index with statuses, idempotency and pagination per operation.

Audience: **public** = served to the UI; **operator** = operator console, compliance or content reviewers; **internal** = service-to-service.

### `_shared/` (13 files)

| File | What it contains |
| --- | --- |
| `problem-details.schema.json` | RFC 9457 error body (`application/problem+json`) used by every error response |
| `pagination.schema.json` | Cursor page envelope: `items` + `nextCursor` (`null` on the last page); requests use `cursor` and `limit` |
| `common-headers.schema.json` | Request headers shared by all APIs: `correlation-id`, `idempotency-key`, `traceparent` |
| `EngagementListItem.schema.json` | One row of the per-firm engagement listing |
| `Offer.schema.json` | The single pending-update offer of an engagement (target, since, status, previously declined) |
| `EngagementPendingUpdate.schema.json` | Offer state of one engagement as served by S3 |
| `PendingUpdateView.schema.json` | Offer state plus change summary, served to the UI detail |
| `FreshnessBadge.schema.json` | Freshness indicator attached to UI data |
| `SummaryRecord.schema.json` | Immutable change summary with provenance (template content only) |
| `DiffPage.schema.json` | Page of the canonical diff of a summary pair, ordered by diff path |
| `BreakdownStep.schema.json` | One step of the per-intermediate-version breakdown of a cumulative offer |
| `FirmCounters.schema.json` | Counters per firm and product: pending, declined, verifying (approximate) |
| `NotificationItem.schema.json` | Notification of the inbox and the SSE stream |

### `_consumed/` (6 files, 3 operations of external systems)

Client-side expectations only (no `method` or `path`): they state what the caller sends and needs back.

| File | What it contains |
| --- | --- |
| `engagements-system/inspectEngagement.request.schema.json` / `.response.schema.json` | Per-engagement downstream inspection (about 1 minute), called by S4 |
| `engagements-system/listCatalogEngagements.request.schema.json` / `.response.schema.json` | Paginated listing of a firm's engagements from the Engagements System catalog, called by S5 |
| `template-comparator/compareTemplateVersions.request.schema.json` / `.response.schema.json` | Diff between two template versions from the existing comparator, called by S2 |

### Operations per service

Each row is a pair `<operation>.request.schema.json` + `<operation>.response.schema.json` in the service folder. Requests describe `{method, path, headers, pathParams?, queryParams?, body?}`; responses are a `oneOf` of one subschema per status code.

**`ms-experience-gateway/` (S7, 8 operations, 16 files)**

| Operation | Method + path | Audience | What it does |
| --- | --- | --- | --- |
| `listEngagements` | `GET /v1/engagements` | public | Paged engagements of the user's firm, with filters |
| `getPendingUpdateView` | `GET /v1/engagements/{engagementId}/pending-update` | public | Offer state plus summary; records a view |
| `getPendingUpdateDiff` | `GET /v1/engagements/{engagementId}/pending-update/diff` | public | Paged diff of the pending update |
| `getPendingUpdateBreakdown` | `GET /v1/engagements/{engagementId}/pending-update/breakdown` | public | Per-intermediate-version steps of a cumulative offer |
| `getCounters` | `GET /v1/counters` | public | Counters per product for the firm |
| `listNotifications` | `GET /v1/notifications` | public | Paged notification inbox |
| `streamNotifications` | `GET /v1/notifications/stream` | public | SSE stream of notifications |
| `reverifyEngagement` | `POST /v1/engagements/{engagementId}:reverify` | public | Enqueues a verification of one engagement (`202`) |

**`ms-engagement-index/` (S3, 4 operations, 8 files)**

| Operation | Method + path | Audience | What it does |
| --- | --- | --- | --- |
| `listFirmEngagements` | `GET /v1/firms/{firmId}/engagements` | internal | Paged engagements of a firm |
| `getFirmCounters` | `GET /v1/firms/{firmId}/counters` | internal | Counters per template/product |
| `getEngagementPendingUpdate` | `GET /v1/engagements/{engagementId}/pending-update` | internal | Offer state of one engagement |
| `getPublishProgress` | `GET /v1/publishes/{publishId}/progress` | operator | Projection progress of a publish |

**`ms-change-summary/` (S2, 4 operations, 8 files)**

| Operation | Method + path | Audience | What it does |
| --- | --- | --- | --- |
| `findSummaries` | `GET /v1/summaries` | internal | Current summaries of a version pair and locale |
| `getSummary` | `GET /v1/summaries/{summaryId}` | internal | One summary record |
| `getSummaryDiff` | `GET /v1/summaries/{summaryId}/diff` | internal | Paged diff of a summary |
| `reportSummary` | `POST /v1/summaries/{summaryId}/reports` | operator (content reviewers) | Report a summary with a reason code (`201`) |

**`ms-template-catalog/` (S1, 1 operation, 2 files)**

| Operation | Method + path | Audience | What it does |
| --- | --- | --- | --- |
| `getTemplateGraph` | `GET /v1/templates/{templateId}/graph` | internal | Snapshot of a template's version DAG since a `templateSeq` |

**`ms-publish-fanout/` (S4, 5 operations, 10 files)**

| Operation | Method + path | Audience | What it does |
| --- | --- | --- | --- |
| `getJob` | `GET /v1/jobs/{jobId}` | operator | State and counters of a fan-out job |
| `pauseJob` | `POST /v1/jobs/{jobId}:pause` | operator | Pause a job |
| `resumeJob` | `POST /v1/jobs/{jobId}:resume` | operator | Resume a job |
| `cancelJob` | `POST /v1/jobs/{jobId}:cancel` | operator | Cancel a job (`202`) |
| `retryDeadTasks` | `POST /v1/jobs/{jobId}/dead-tasks:retry` | operator | Retry failed tasks (`202`) |

**`ms-backfill-reconciler/` (S5, 6 operations, 12 files)**

| Operation | Method + path | Audience | What it does |
| --- | --- | --- | --- |
| `startCampaign` | `POST /v1/campaigns` | operator | Start a backfill campaign (`201`) |
| `pauseCampaign` | `POST /v1/campaigns/{campaignId}:pause` | operator | Pause a campaign (cancels the in-flight job) |
| `resumeCampaign` | `POST /v1/campaigns/{campaignId}:resume` | operator | Resume a campaign (retries failed tasks) |
| `getCampaign` | `GET /v1/campaigns/{campaignId}` | operator | Campaign aggregates and coverage |
| `startReconciliationRun` | `POST /v1/reconciliation-runs` | operator | Start a reconciliation run (`201`) |
| `getReconciliationRun` | `GET /v1/reconciliation-runs/{runId}` | operator | State and results of a run |

**`ms-audit-ledger/` (S6, 2 operations, 4 files)**

| Operation | Method + path | Audience | What it does |
| --- | --- | --- | --- |
| `getSummaryEvidence` | `GET /v1/evidence/summaries/{summaryId}` | operator (compliance) | Paged evidence by summary |
| `listEngagementEvidence` | `GET /v1/evidence/engagements/{engagementId}` | operator (compliance) | Paged evidence of an engagement by date range |

Count check: 16 + 8 + 8 + 2 + 10 + 12 + 4 = 60 service files (8 + 4 + 4 + 1 + 5 + 6 + 2 = 30 operations).

**How the files relate.** An operation = a request schema (method, path, headers, parameters, body) + a response schema (one subschema per status). Responses reuse `_shared/` view models and `problem-details` for errors, and collections use `pagination`. Operations read the stores described in `dynamo/` and `postgresql/` (for example S3 reads `engagement_state` and `firm_counters`) and expose the same fields as the related events (for example `SummaryRecord` provenance matches `ChangeSummaryReady.v1`). The write operations either execute the same use case as a command (`cancelJob`, `retryDeadTasks`) or send commands (`reverifyEngagement` sends `StartFanOutJob.v1` and `EnqueueFanOutTasks.v1`).

## Conventions shared by all contracts

- Entity ids are UUIDs (`format: uuid`); natural keys keep their natural type: template version labels (`v7`), `diff_hash`, `prompt_version`, `day` (date).
- Instants are ISO-8601 UTC (`format: date-time` in JSON; `timestamptz` in PostgreSQL).
- Audit fields on every PostgreSQL table and DynamoDB item: `created_by`, `created_at`, `updated_by`, `updated_at`, `state` (snake_case in PostgreSQL, `createdBy`, `createdAt`, `updatedBy`, `updatedAt`, `state` in JSON).
- Append-only tables keep `updated_*` equal to `created_*` and a constant `state`.
- `version` (integer) is the optimistic-concurrency attribute of every DynamoDB item (every write is conditional on it) and of `campaign` in PostgreSQL.
- DynamoDB items carry `PK` (and `SK` when the table has one), GSI key attributes where indexed, an `entityType` constant and `ttl` (epoch seconds) on ephemeral types.
- JSON field names are camelCase; objects are closed with `additionalProperties: false`.
- Messages do not carry the audit fields; they carry the envelope fields and a closed `payload`.

## Validating the JSON Schemas

```python
from jsonschema import Draft202012Validator, FormatChecker
from referencing import Registry, Resource
reg = Registry().with_resources((s["$id"], Resource.from_contents(s)) for s in all_schemas)
Draft202012Validator.check_schema(schema)
Draft202012Validator(schema, registry=reg, format_checker=FormatChecker()).validate(example)
```

Load every `*.schema.json` into `all_schemas` so cross-file `$ref`s resolve through `$id`.
