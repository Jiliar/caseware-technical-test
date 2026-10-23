# Message contracts (EventBridge, SNS, SQS)

JSON Schema (Draft 2020-12) contracts for every event and command in the asynchronous catalog of *Pending Template Updates*. The Spanish documents are the source of truth: `docs/es/DESIGN_DOC.md` > `docs/es/DISENO.md` (§3.8, §4.1–§4.8, §5, §7.2–§7.4, DEC-22…DEC-38) > `docs/es/PRD.md`, with ADR-018 (sequencer), ADR-019 (outbox), ADR-020 and ADR-021 (`event-contracts`). Field names, types and value sets match the sibling data models in `../dynamo/` and `../postgresql/` and the APIs in `../apis/`.

| File | Content |
| --- | --- |
| `envelope.schema.json` | Common envelope (metadata) plus shared `$defs`: `uuid`, `instant`, `versionLabel`, `dataClass`, `priorityClass`, `summaryKind` |
| `transport-attributes.schema.json` | SNS/SQS message attributes, the envelope → attribute mapping, FIFO send parameters, FIFO vs standard queues and the ordering key of each stream |
| `<EventName>.v1.schema.json` (29) | One file per event EV-01…EV-29 (DISENO §4.3) |
| `<CommandName>.v1.schema.json` (7) | One file per command CMD-01…CMD-07 (DISENO §4.4) |
| [`topology.pdf`](topology.pdf) | Topology diagram (PDF rendered from PlantUML): global bus, cell bus, rules, SNS topics, SQS queues, DLQs and consumers |

Service aliases: S1 `ms-template-catalog`, S2 `ms-change-summary`, S3 `ms-engagement-index`, S4 `ms-publish-fanout`, S5 `ms-backfill-reconciler`, S6 `ms-audit-ledger`, S7 `ms-experience-gateway`. External producers: `templates-service` (Templates Service) and `engagements-system` (Engagement System). Operators never send messages: they call the S4/S5 APIs (DISENO §4.4, DEC-24).

## (a) Topology

DISENO §4.1 and DEC-22: everything that enters an EventBridge bus (hooks, replicated global events, `AuditBatchSealed.v1`) reaches each consumer's SQS queue through **one EventBridge rule per consumer**. SNS topics carry **only** the own domain events of S3, S4, S5 and S7.

| Element | Scope | Type | Use (DISENO §4.1) |
| --- | --- | --- | --- |
| EventBridge **global bus** | Central (home region) | Bus, with archive/replay (RF-087) | Ingests the Templates Service hooks (EV-01, EV-02). Carries S1 and S2 events. Forwards **only** the whitelisted types to each cell bus. Receives `GenerateChangeSummary.v1` from the cells. One rule per consumer queue |
| EventBridge **cell bus** (one per cell) | Regional | Bus, archive `TTL_arch` (basis of the index *replay*, DEC-23) | Ingests the Engagement System hooks (EV-08…EV-12) and `AuditBatchSealed.v1` (EV-29, archived, no internal consumer). Receives the whitelisted global events. One rule per consumer queue. Forwards only `GenerateChangeSummary.v1` to the global bus |
| SNS `{cell}-{producer}-events` | Regional | **Standard** topic | Own events of S3, S4, S5 and S7 only. Each subscription has an `eventType` filter policy |
| SQS `{cell}-{consumer}-{producer}` | Regional (`global-` for S1/S2) | **Standard** queue | One queue per consumer and source, each with its own DLQ |
| SQS `global-ms-template-catalog-templates-service.fifo` | Central | **FIFO** queue (the only one) | S1 ingest. `MessageGroupId = templateId`, deduplication by `eventId` |
| SQS `{cell}-{receiver}-commands` | Regional / central | **Standard** queue | Point-to-point commands to S4, S3 and S2. Commands are never published to topics |
| SQS `<queue>-dlq` | Same as the source queue | Standard (FIFO for the FIFO queue) | `maxReceiveCount = N_reint`. DLQ retention ≥ source queue retention. Native redrive to the source queue (runbook §7.4) |
| SQS `ms-engagement-index-publish-units` | Regional | Internal work queue | `(publishId, sourceVersion, shard)` units inside S3. It is not a catalog contract, so it has no schema here |

**SNS topics (4):** `{cell}-ms-engagement-index-events`, `{cell}-ms-publish-fanout-events`, `{cell}-ms-backfill-reconciler-events`, `{cell}-ms-experience-gateway-events`.

**SQS queues (18, each with a `-dlq`)**, as listed in DISENO §4.1:

- **Global:** `global-ms-template-catalog-templates-service.fifo`, `global-ms-change-summary-ms-template-catalog`, `global-ms-change-summary-commands`.
- **Per cell:**
  - `{cell}-ms-engagement-index-` + `ms-template-catalog` | `engagements-system` | `ms-backfill-reconciler` | `ms-publish-fanout` | `commands`
  - `{cell}-ms-publish-fanout-` + `ms-template-catalog` | `commands`
  - `{cell}-ms-backfill-reconciler-` + `ms-engagement-index` | `ms-publish-fanout`
  - `{cell}-ms-audit-ledger-` + `ms-change-summary` | `ms-engagement-index` | `ms-backfill-reconciler` | `ms-experience-gateway`
  - `{cell}-ms-experience-gateway-` + `ms-change-summary` | `ms-engagement-index`

**Ordering and guarantees.** Delivery is at-least-once everywhere, so every consumer deduplicates by `eventId` in its own database (INV-07, DISENO §3.8.3). Only the S1 ingest is FIFO. In the cells, order is enforced by the consumer:

- `templateSeq`, applied strictly in order (ADR-018);
- `engagementSeq`, with the condition `lastSeq < :seq` (DEC-15); when a hook has no `engagementSeq` (optional in v1, DEC-37), `eventId` + base version and the logical order `(occurredAt, eventId)`;
- conditional writes on `lastHookAt`/`observedAt`.

### Catalog → transport → schema

All values come from DISENO §4.1, §4.3 and §4.4 (the commands table gives the ordering key through the idempotency key).

| # | Message | Producer(s) | Consumer(s) | dataClass | SNS topic | SQS queue(s) | Ordering / partition key | Idempotency key | Guarantee | Schema |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| EV-01 | `TemplateVersionPublished.v1` | templates-service | ms-template-catalog | TEMPLATE_PUBLIC | — | `global-ms-template-catalog-templates-service.fifo` | templateId (FIFO MessageGroupId) | eventId; publishId | at-least-once, ordered per template (FIFO) | `TemplateVersionPublished.v1.schema.json` |
| EV-02 | `TemplateVersionWithdrawn.v1` | templates-service | ms-template-catalog | TEMPLATE_PUBLIC | — | `global-ms-template-catalog-templates-service.fifo` | templateId (FIFO MessageGroupId) | eventId | at-least-once, ordered per template (FIFO) | `TemplateVersionWithdrawn.v1.schema.json` |
| EV-03 | `VersionGraphNodeAdded.v1` | ms-template-catalog | ms-engagement-index, ms-change-summary | TEMPLATE_PUBLIC | — | `{cell}-ms-engagement-index-ms-template-catalog`<br>`global-ms-change-summary-ms-template-catalog` | templateId + templateSeq | eventId; (templateId, templateSeq) | at-least-once; order rebuilt by templateSeq | `VersionGraphNodeAdded.v1.schema.json` |
| EV-04 | `VersionGraphNodeWithdrawn.v1` | ms-template-catalog | ms-engagement-index, ms-change-summary, ms-publish-fanout | TEMPLATE_PUBLIC | — | `{cell}-ms-engagement-index-ms-template-catalog`<br>`global-ms-change-summary-ms-template-catalog`<br>`{cell}-ms-publish-fanout-ms-template-catalog` | templateId + templateSeq | eventId; (templateId, templateSeq) | at-least-once | `VersionGraphNodeWithdrawn.v1.schema.json` |
| EV-05 | `ChangeSummaryReady.v1` | ms-change-summary | ms-experience-gateway, ms-audit-ledger | TEMPLATE_PUBLIC | — | `{cell}-ms-experience-gateway-ms-change-summary`<br>`{cell}-ms-audit-ledger-ms-change-summary` | summaryKey (templateId, from, to, locale) | summaryId | at-least-once | `ChangeSummaryReady.v1.schema.json` |
| EV-06 | `ChangeSummaryFailed.v1` | ms-change-summary | ms-experience-gateway | TEMPLATE_PUBLIC | — | `{cell}-ms-experience-gateway-ms-change-summary` | summaryKey (templateId, from, to, locale) | eventId | at-least-once | `ChangeSummaryFailed.v1.schema.json` |
| EV-07 | `ChangeSummaryInvalidated.v1` | ms-change-summary | ms-experience-gateway, ms-audit-ledger | TEMPLATE_PUBLIC | — | `{cell}-ms-experience-gateway-ms-change-summary`<br>`{cell}-ms-audit-ledger-ms-change-summary` | summaryId | summaryId + withdrawnVersion | at-least-once | `ChangeSummaryInvalidated.v1.schema.json` |
| EV-08 | `EngagementCreated.v1` | engagements-system | ms-engagement-index | ENGAGEMENT_REGIONAL | — | `{cell}-ms-engagement-index-engagements-system` | engagementId + engagementSeq | eventId | at-least-once | `EngagementCreated.v1.schema.json` |
| EV-09 | `EngagementUpdateApplied.v1` | engagements-system | ms-engagement-index | ENGAGEMENT_REGIONAL | — | `{cell}-ms-engagement-index-engagements-system` | engagementId + engagementSeq | eventId | at-least-once | `EngagementUpdateApplied.v1.schema.json` |
| EV-10 | `EngagementUpdateDeclined.v1` | engagements-system | ms-engagement-index | ENGAGEMENT_REGIONAL | — | `{cell}-ms-engagement-index-engagements-system` | engagementId + engagementSeq | eventId | at-least-once | `EngagementUpdateDeclined.v1.schema.json` |
| EV-11 | `EngagementTemplateObserved.v1` | engagements-system | ms-engagement-index | ENGAGEMENT_REGIONAL | — | `{cell}-ms-engagement-index-engagements-system` | engagementId | eventId | at-least-once | `EngagementTemplateObserved.v1.schema.json` |
| EV-12 | `EngagementArchived.v1` | engagements-system | ms-engagement-index | ENGAGEMENT_REGIONAL | — | `{cell}-ms-engagement-index-engagements-system` | engagementId + engagementSeq | eventId | at-least-once | `EngagementArchived.v1.schema.json` |
| EV-13 | `EngagementDiscovered.v1` | ms-backfill-reconciler | ms-engagement-index | ENGAGEMENT_REGIONAL | `{cell}-ms-backfill-reconciler-events` | `{cell}-ms-engagement-index-ms-backfill-reconciler` | engagementId | discoveryBatchId + engagementId | at-least-once | `EngagementDiscovered.v1.schema.json` |
| EV-14 | `EngagementClassificationRequired.v1` | ms-engagement-index | ms-backfill-reconciler | ENGAGEMENT_REGIONAL | `{cell}-ms-engagement-index-events` | `{cell}-ms-backfill-reconciler-ms-engagement-index` | engagementId | eventId | at-least-once | `EngagementClassificationRequired.v1.schema.json` |
| EV-15 | `EngagementVersionInspected.v1` | ms-publish-fanout | ms-engagement-index | ENGAGEMENT_REGIONAL | `{cell}-ms-publish-fanout-events` | `{cell}-ms-engagement-index-ms-publish-fanout` | engagementId | jobId + engagementId | at-least-once | `EngagementVersionInspected.v1.schema.json` |
| EV-16 | `EngagementClassified.v1` | ms-engagement-index | ms-backfill-reconciler | ENGAGEMENT_REGIONAL | `{cell}-ms-engagement-index-events` | `{cell}-ms-backfill-reconciler-ms-engagement-index` | engagementId | eventId | at-least-once | `EngagementClassified.v1.schema.json` |
| EV-17 | `PendingUpdateOffered.v1` | ms-engagement-index | ms-audit-ledger | ENGAGEMENT_REGIONAL | `{cell}-ms-engagement-index-events` | `{cell}-ms-audit-ledger-ms-engagement-index` | engagementId | eventId (deterministic, derived from the DynamoDB Streams record) | at-least-once | `PendingUpdateOffered.v1.schema.json` |
| EV-18 | `PendingUpdateSuperseded.v1` | ms-engagement-index | ms-audit-ledger | ENGAGEMENT_REGIONAL | `{cell}-ms-engagement-index-events` | `{cell}-ms-audit-ledger-ms-engagement-index` | engagementId | eventId | at-least-once | `PendingUpdateSuperseded.v1.schema.json` |
| EV-19 | `PendingUpdateWithdrawn.v1` | ms-engagement-index | ms-audit-ledger, ms-experience-gateway | ENGAGEMENT_REGIONAL | `{cell}-ms-engagement-index-events` | `{cell}-ms-audit-ledger-ms-engagement-index`<br>`{cell}-ms-experience-gateway-ms-engagement-index` | engagementId | eventId | at-least-once | `PendingUpdateWithdrawn.v1.schema.json` |
| EV-20 | `DecisionRecorded.v1` | ms-engagement-index | ms-audit-ledger, ms-experience-gateway | ENGAGEMENT_REGIONAL | `{cell}-ms-engagement-index-events` | `{cell}-ms-audit-ledger-ms-engagement-index`<br>`{cell}-ms-experience-gateway-ms-engagement-index` | engagementId | eventId of the originating hook | at-least-once | `DecisionRecorded.v1.schema.json` |
| EV-21 | `AppliedWithdrawnFlagged.v1` | ms-engagement-index | ms-experience-gateway, ms-audit-ledger | ENGAGEMENT_REGIONAL | `{cell}-ms-engagement-index-events` | `{cell}-ms-experience-gateway-ms-engagement-index`<br>`{cell}-ms-audit-ledger-ms-engagement-index` | engagementId | eventId | at-least-once | `AppliedWithdrawnFlagged.v1.schema.json` |
| EV-22 | `FirmPendingUpdatesChanged.v1` | ms-engagement-index | ms-experience-gateway | ENGAGEMENT_REGIONAL | `{cell}-ms-engagement-index-events` | `{cell}-ms-experience-gateway-ms-engagement-index` | firmId | eventId | at-least-once, coalescible | `FirmPendingUpdatesChanged.v1.schema.json` |
| EV-23 | `PublishProjectionCompleted.v1` | ms-engagement-index | ms-backfill-reconciler | OPERATIONAL | `{cell}-ms-engagement-index-events` | `{cell}-ms-backfill-reconciler-ms-engagement-index` | publishId | publishId + cellId (envelope) | at-least-once | `PublishProjectionCompleted.v1.schema.json` |
| EV-24 | `ProjectionDriftDetected.v1` | ms-engagement-index | ms-backfill-reconciler | ENGAGEMENT_REGIONAL | `{cell}-ms-engagement-index-events` | `{cell}-ms-backfill-reconciler-ms-engagement-index` | engagementId | eventId | at-least-once | `ProjectionDriftDetected.v1.schema.json` |
| EV-25 | `FanOutTaskDeadLettered.v1` | ms-publish-fanout | ms-backfill-reconciler | ENGAGEMENT_REGIONAL | `{cell}-ms-publish-fanout-events` | `{cell}-ms-backfill-reconciler-ms-publish-fanout` | jobId | jobId + engagementId | at-least-once | `FanOutTaskDeadLettered.v1.schema.json` |
| EV-26 | `FanOutCompleted.v1` | ms-publish-fanout | ms-engagement-index, ms-backfill-reconciler | OPERATIONAL | `{cell}-ms-publish-fanout-events` | `{cell}-ms-engagement-index-ms-publish-fanout`<br>`{cell}-ms-backfill-reconciler-ms-publish-fanout` | jobId | jobId | at-least-once | `FanOutCompleted.v1.schema.json` |
| EV-27 | `ReconciliationRunCompleted.v1` | ms-backfill-reconciler | ms-audit-ledger | OPERATIONAL | `{cell}-ms-backfill-reconciler-events` | `{cell}-ms-audit-ledger-ms-backfill-reconciler` | runId | runId | at-least-once | `ReconciliationRunCompleted.v1.schema.json` |
| EV-28 | `SummaryViewed.v1` | ms-experience-gateway | ms-audit-ledger | ENGAGEMENT_REGIONAL | `{cell}-ms-experience-gateway-events` | `{cell}-ms-audit-ledger-ms-experience-gateway` | engagementId | eventId (generated when serving the detail) | at-least-once | `SummaryViewed.v1.schema.json` |
| EV-29 | `AuditBatchSealed.v1` | ms-audit-ledger | — (none internal) | OPERATIONAL | — | — (cell bus archive) | day | day + cellId | at-least-once | `AuditBatchSealed.v1.schema.json` |
| CMD-01 | `GenerateChangeSummary.v1` | ms-engagement-index, ms-experience-gateway | ms-change-summary | TEMPLATE_PUBLIC | — | `global-ms-change-summary-commands` | summaryKey (templateId, from, to) | summaryKey (single-flight) | at-least-once | `GenerateChangeSummary.v1.schema.json` |
| CMD-02 | `RegenerateChangeSummary.v1` | ms-change-summary | ms-change-summary | TEMPLATE_PUBLIC | — | `global-ms-change-summary-commands` | summaryKey | summaryKey + promptVersion + modelId | at-least-once | `RegenerateChangeSummary.v1.schema.json` |
| CMD-03 | `StartFanOutJob.v1` | ms-engagement-index, ms-backfill-reconciler, ms-experience-gateway | ms-publish-fanout | OPERATIONAL | — | `{cell}-ms-publish-fanout-commands` | jobId | jobId | at-least-once | `StartFanOutJob.v1.schema.json` |
| CMD-04 | `EnqueueFanOutTasks.v1` | ms-engagement-index, ms-backfill-reconciler, ms-experience-gateway | ms-publish-fanout | ENGAGEMENT_REGIONAL | — | `{cell}-ms-publish-fanout-commands` | jobId | jobId + chunkNo (and per task (jobId, engagementId)) | at-least-once | `EnqueueFanOutTasks.v1.schema.json` |
| CMD-05 | `CancelFanOutJob.v1` | ms-backfill-reconciler | ms-publish-fanout | OPERATIONAL | — | `{cell}-ms-publish-fanout-commands` | jobId | jobId | at-least-once | `CancelFanOutJob.v1.schema.json` |
| CMD-06 | `RetryFanOutTasks.v1` | ms-backfill-reconciler | ms-publish-fanout | OPERATIONAL | — | `{cell}-ms-publish-fanout-commands` | jobId | jobId + requestId | at-least-once | `RetryFanOutTasks.v1.schema.json` |
| CMD-07 | `RecomputeEngagementOffers.v1` | ms-backfill-reconciler | ms-engagement-index | ENGAGEMENT_REGIONAL | — | `{cell}-ms-engagement-index-commands` | requestId | requestId | at-least-once | `RecomputeEngagementOffers.v1.schema.json` |

Channels by group (DISENO §4.3/§4.4):

| Messages | Channel |
| --- | --- |
| EV-01, EV-02 | Global bus → rule → SQS FIFO |
| EV-03, EV-04 | Global bus → rule → SQS of S2; global bus → cell buses → rule per consumer → SQS |
| EV-05…EV-07 | Global bus → cell buses → rule per consumer → SQS |
| EV-08…EV-12 | Cell bus → rule → SQS of S3 |
| EV-13…EV-28 | Producer SNS topic → consumer SQS |
| EV-29 | S6 outbox → cell bus (archive only) |
| CMD-01 | Cell bus → global bus → rule → `global-ms-change-summary-commands` |
| CMD-02 | `global-ms-change-summary-commands` (sent by S2's evaluation pipeline) |
| CMD-03…CMD-06 | `{cell}-ms-publish-fanout-commands` |
| CMD-07 | `{cell}-ms-engagement-index-commands` |

Every schema also carries these values as annotations: `x-producers`, `x-consumers`, `x-channel`, `x-snsTopic`, `x-sqsQueues`, `x-orderingKey`, `x-idempotencyKey`, `x-deliveryGuarantee`, `x-dataClass`, `x-crossRegion`, and `x-responseEvents` for commands.

## (b) Message structure

Every message body is a single JSON object: the **envelope** (metadata) plus `payload` (business data). It travels as the SQS/SNS body or as the EventBridge `detail`.

| Envelope field | Type | Source |
| --- | --- | --- |
| `eventId` | uuid | DISENO §4.2 record. Universal idempotency key (INV-07) |
| `eventType` | string, name without the `.vN` suffix | DISENO §4.2, e.g. `"VersionGraphNodeAdded"` |
| `schemaVersion` | integer ≥ 1 | DISENO §4.2. Major version (`1` = `v1`). The record rejects values < 1 |
| `occurredAt` | date-time | DISENO §4.2. Instant of the fact at the producer |
| `producer` | string | DISENO §4.2. Producing service |
| `correlationId` | string | DISENO §4.2 and §7.2. The root of a publish is its `publishId` |
| `causationId` | uuid or `null` | DISENO §4.2. `null` only in root hooks |
| `cellId` | string | DISENO §4.2. `global`, `eu`, `ca` (example values, configurable) |
| `traceparent` | string (W3C) | DISENO §4.2 and §7.2 |
| `kind` | `EVENT` \| `COMMAND` | DISENO §4.2 (`MessageKind`) |
| `payload` | object | DISENO §4.2. Each message schema constrains it |

Each message schema is `allOf: [{"$ref": "envelope.schema.json"}]`. It then pins these fields:

- `eventType` to `const`;
- `schemaVersion` to `const 1`;
- `kind`;
- `producer` (`const` or `enum`);
- `payload` (`additionalProperties: false`).

Shared types come from `envelope.schema.json#/$defs/...`. The SNS/SQS message attributes are described in `transport-attributes.schema.json`. They are `eventType`, `schemaVersion`, `traceparent`, `correlationId` and `cellId`. For EventBridge, `detail-type = eventType`, and the cross-region whitelist rules match on it.

Conventions:

- Ids are `format: uuid`. Template version labels are strings, e.g. `"v7"`, as in `TemplateGraphNode`. Instants are `format: date-time` (UTC).
- Enumerations reuse the data-model value sets:
  - `priorityClass` = `FanOutJob.priority`;
  - `summaryKind` = `summary_record.kind`;
  - `decision`/`discrepancy` = `ms-audit-ledger.decision`;
  - `FanOutCompleted.outcome` ⊂ `FanOutJob.state`;
  - `ReconciliationRunCompleted.kind` = `reconciliation_run.kind`.
- Events do **not** carry the DB audit columns (`createdBy`, …). Fields such as `createdAt` in `EngagementCreated.v1` are business facts listed by the catalog.
- No numeric limit is invented. Batch sizes are bounded by the named parameter `L_lote` and payload size by `S_payload`, and neither has a value. `minimum: 0` is used only on counters and durations.

## (c) Versioning

- **Naming:** `{PastTenseFact}.v{n}` for events and `{ImperativeVerb}.v{n}` for commands. The file is `<Name>.v<n>.schema.json`, and in `event-contracts` it is `schemas/{eventType}/v{n}.json` (ADR-021).
- **Within `v1`, changes are additive only:** a new **optional** field. Removing a field, renaming it, changing its type, adding a new required field, or narrowing an enum is breaking.
- **Breaking change:** create `<Name>.v2.schema.json` with `schemaVersion: const 2`. The producer publishes `v1` and `v2` in parallel (expand/contract). Consumers migrate using the `schemaVersion` attribute or filter. `v1` is retired when no consumer uses it.
- **Strictness:** payloads use `additionalProperties: false`, so the producer relay validates strictly before publishing (ADR-019/ADR-021). Consumers must be tolerant readers that ignore unknown fields (DISENO §4.2), so an additive `v1` change never breaks them.
- **Hook contracts** (EV-01, EV-02, EV-08…EV-12) are agreed with the owning teams (ADR-015).

## (d) Residency

- **Only these messages cross regions** (DISENO §4.1, RF-091/RF-092):
  - global → cells: `VersionGraphNodeAdded.v1`, `VersionGraphNodeWithdrawn.v1`, `ChangeSummaryReady.v1`, `ChangeSummaryFailed.v1`, `ChangeSummaryInvalidated.v1`;
  - cells → global: only `GenerateChangeSummary.v1`.
- Nothing else crosses. These schemas are marked `x-crossRegion: true` and declare `dataClass` `TEMPLATE_PUBLIC`. They contain no `engagementId`, `firmId`, `userId` or free text, and the validation script checks this.
- `TemplateVersionPublished.v1`, `TemplateVersionWithdrawn.v1` and `RegenerateChangeSummary.v1` are `TEMPLATE_PUBLIC` but stay in the home region.
- `ENGAGEMENT_REGIONAL` messages stay inside the firm's cell. They may carry `engagementId`/`firmId`/`userId` but **never workpaper content**.
- `OPERATIONAL` messages carry operational metadata only: counters, job/run ids and batch hashes.
- Controls: EventBridge rules use a `detail-type` whitelist, a contract test checks the cross-region schemas, and cell IAM cannot publish to other buses (RNF-020).

## (e) Validation

Requires `jsonschema` 4.x with `referencing`. Run it from this folder:

```bash
python3 -I - <<'EOF'
import json, pathlib
from jsonschema import Draft202012Validator, FormatChecker
from referencing import Registry, Resource
schemas = {p.name: json.loads(p.read_text()) for p in pathlib.Path('.').glob('*.json')}
registry = Registry().with_resources((s['$id'], Resource.from_contents(s)) for s in schemas.values())
for name, schema in sorted(schemas.items()):
    Draft202012Validator.check_schema(schema)                      # meta-validation
    v = Draft202012Validator(schema, registry=registry, format_checker=FormatChecker())
    for ex in schema.get('examples', []):                          # examples incl. $ref to the envelope
        errs = [f"{list(e.absolute_path)}: {e.message}" for e in v.iter_errors(ex)]
        print('OK ' if not errs else 'ERR', name, *errs)
EOF
```

The relative `$ref` `envelope.schema.json` resolves against each schema's `$id` (`https://caseware.invalid/docs/en/data/events/...`). That is why the registry is keyed by `$id`. With the legacy API, use `RefResolver(base_uri=schema['$id'], referrer=schema, store={s['$id']: s for s in schemas.values()})`.

**Results (DISENO 0.2.2):**

- **Files:** all 38 JSON files parse and pass `Draft202012Validator.check_schema`.
- **Examples:** all 38 `examples[0]` validate with `FormatChecker`, including the envelope `$ref`.
- **Negative tests:** for each message schema, the validator rejects an extra envelope field, an extra payload field, a wrong `eventType` and an invalid uuid.
- **Catalog:** all 36 catalog entries (29 EV + 7 CMD) have a schema and every schema is in the catalog. Name, producers, consumers and dataClass match DISENO §4.3/§4.4.
- **Diagrams:**
  - The event names used in the other `docs/en/diagrams/` files all exist in the catalog.
  - The [`sequence/`](../../diagrams/sequence/) and [`activity/`](../../diagrams/activity/) diagrams use descriptive labels such as "creation event" and "decision event" rather than catalog names.
  - The DISENO §4.5 sequences use only catalog names.
- **Diagram:** [`topology.pdf`](topology.pdf) is rendered from a PlantUML source that passes `-checkonly`.

## (f) Decisions applied and declared assumptions

Every point that earlier versions of this README listed as an ambiguity is now decided in DISENO 0.2.2 and reproduced by the schemas:

| Topic | Decision | DEC |
| --- | --- | --- |
| Cell topology | Bus → one rule per consumer → SQS for everything that enters a bus; SNS only for the own events of S3, S4, S5, S7; no hooks topic | DEC-22 |
| `AuditBatchSealed.v1` | Published by S6 to the cell bus, kept in its archive; no SNS topic, no internal consumer | DEC-22 |
| Names | Queue, topic and rule naming convention and external producers (`templates-service`, `engagements-system`) defined in DISENO §4.1; `producer` is an enum in the envelope | DEC-22 |
| CMD-05/CMD-06 producers | Only `ms-backfill-reconciler` (pause/resume of a campaign); operators use the S4 API, which runs the same use case without a message | DEC-24 |
| CMD-02/CMD-07 triggers | CMD-02 by the evaluation pipeline of `ms-change-summary`; CMD-07 by `ms-backfill-reconciler` runs (scheduled or `POST /v1/reconciliation-runs`) | DEC-25 |
| Discovery in reconciliation | EV-13 carries exactly one of `campaignId` or `runId` (EV-14, EV-16 echo them) | DEC-26 |
| `dataClass` | Stated for every event and command in the catalog; the schemas match it | DEC-37 |
| `requestId` | In the payload of CMD-06 (= API `Idempotency-Key`) and CMD-07 (= `runId`) | DEC-37 |
| `engagementSeq` | Optional in EV-08/09/10/12 v1: the fallback of the declared assumption needs no `v2` | DEC-37 |
| Hook schemas | Live in `event-contracts`; Git is the schema registry | DEC-37 |
| Fan-out states | `FanOutCompleted.v1` counts `done`, `failed`, `obsolete`; `reasonCode` of EV-25 = `RETRIES_EXHAUSTED` \| `NON_RETRYABLE`; CMD-06 filters by `failureReasons` | DEC-30 |
| Code sets | EV-06 `failureCode`, EV-16/EV-24 `source`, CMD-02/05/07 `reason` are enums (DISENO §4.3/§4.4); EV-27 `accuracyEstimate` = 1 − discrepancies / checked | DEC-37 |
| Envelope | All 11 fields required; `causationId` null only in root hooks and in messages caused by an API call or a scheduler; deterministic CDC `eventId` = UUID v5 of `PK + version` | DEC-28 |
| Batches | `minItems: 1` on `items`, `refs`, `locales`, `engagementIds`: an empty batch is never sent | DEC-37 |

Conventions kept from the catalog: payload `occurredAt` in EV-18, EV-19, EV-21, EV-22 and `cellId` in EV-29 repeat envelope fields on purpose (the catalog lists them); `promptVersion` and `modelId` in EV-05 are required but nullable (DETERMINISTIC summaries); EV-02 `reasonCode` is a code defined by the Templates Service in the hook contract.

**Declared assumptions** (depend on other teams' systems, declared in DESIGN_DOC §5, with what changes if not met):

- **Sequence in hooks** (SUP-21): if the Engagement System cannot add `engagementSeq`, the field stays absent and consumers use `eventId` + base version and `(occurredAt, eventId)`; reconciliation weighs more.
- **Active = non-archived** (SUP-20): if the Engagement System does not emit `EngagementArchived.v1`, reconciliation detects archived engagements.
- **Firm region** (SUP-22): hooks are published on the bus of their own region; otherwise a `firmId → region` map is needed.
- **Enumerable catalog** (SUP-08 a): if it does not exist, EV-13 is only produced from metadata exports or not at all, and backfill relies on hooks and inspection.
- **Idempotent downstream** (SUP-08 b): `EngagementVersionInspected.v1` may then be produced once per retry; if not idempotent, a timed-out task is not retried and goes to the DLQ (EV-25).
