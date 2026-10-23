# Pending Template Updates: design document

**What the system achieves.** When a content team publishes a version of a template, each firm sees within seconds which engagements have pending changes. For each engagement it receives a readable summary of what changes and decides to apply or reject; the summary shown must be defensible months later before a regulator.

## 1. Architecture and data ownership

**Core decision: an engagement is never read to answer a query.** Reading one takes ~1 minute, a hard constraint. We keep **our own index**: a projection engagement → template, base version and status, fed by events. The brief allows adding hooks or events to both existing systems:

| Event                             | Emitted by         | The index…                                                                                  |
| --------------------------------- | ------------------ | ------------------------------------------------------------------------------------------- |
| Engagement creation               | Engagements System | Records the template and the creation version (its "base version")                          |
| Apply/reject decision             | Engagements System | If applied, moves the base. If rejected, records it and the base does not change            |
| Version publication (or withdrawal) | Templates Service | Triggers the fan-out: recalculates which engagements have something pending, without reading any |

Data ownership:

| System                          | Owns                                                  | Where it lives                |
| ------------------------------- | ----------------------------------------------------- | ----------------------------- |
| Templates Service (existing)    | Templates, versions and their graph. No firm data     | Central, shared across firms  |
| Engagements System (existing)   | Engagements and apply/reject decisions                | Per firm and region           |
| New services                    | Index, summaries and evidence                         | Next table                    |

**Design decision: services per domain, each with its own database and asynchronous communication:**

| Service                  | Purpose                                                  | Where      |
| ------------------------ | -------------------------------------------------------- | ---------- |
| `ms-template-catalog`    | Validated copy of the version graph                      | Central    |
| `ms-change-summary`      | Summary per version pair, cached and immutable           | Central    |
| `ms-engagement-index`    | The index: base and pending offer per engagement         | Per region |
| `ms-publish-fanout`      | The unavoidable slow queries, limited by `C_max`         | Per region |
| `ms-backfill-reconciler` | Backfill of existing engagements and periodic reconciliation | Per region |
| `ms-audit-ledger`        | Which summary was shown and what was decided             | Per region |
| `ms-experience-gateway`  | Read for the screen and indicator notification           | Per region |

**AWS services:**

| AWS service                                                                                          | What we use it for                                                                                   | Where                                                                                   |
| ---------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------- |
| Amazon SNS + SQS (with DLQ)                                                                          | Distribute events and queue work; set aside failing messages to reprocess them                       | All `ms-*`                                                                              |
| Amazon EventBridge                                                                                   | Receive events from the existing systems and replicate to each region **only template events** (publication, withdrawal and summaries; never firm data) | Central and regional edges |
| DynamoDB                                                                                             | Index per engagement, fan-out tasks and pending screen notifications (key-based access)              | `ms-engagement-index`, `ms-publish-fanout`, `ms-experience-gateway`                     |
| RDS/Aurora PostgreSQL                                                                                | Version graph, summaries, backfill campaigns and queryable audit (relational data)                   | `ms-template-catalog`, `ms-change-summary`, `ms-backfill-reconciler`, `ms-audit-ledger` |
| S3 with Object Lock                                                                                  | Immutable evidence of each summary and decision                                                      | `ms-change-summary`, `ms-audit-ledger`                                                  |
| ElastiCache (Redis)                                                                                  | Speed up reads; if it fails, everything keeps working                                                | `ms-change-summary`, `ms-experience-gateway`                                            |
| ECS/EKS                                                                                              | Run the services                                                                                     | All                                                                                     |
| CloudWatch (+ OpenTelemetry)                                                                         | SLOs, metrics, traces and alerts                                                                     | All                                                                                     |
| KMS, Secrets Manager, IAM                                                                            | Per-region encryption, secrets and least-privilege permissions                                       | All                                                                                     |
| LLM inference: Amazon Bedrock **(to be confirmed)**; alternative: another LLM provider via API      | Write the summary from the diff                                                                      | `ms-change-summary`                                                                     |
| Feature flags: AWS AppConfig **(to be confirmed)**; alternative: custom configuration                | Enable or disable hooks and capabilities without deploying                                           | All                                                                                     |
| UI notification: Server-Sent Events (SSE) served by `ms-experience-gateway` on ECS/EKS; fallback: periodic polling from the UI | Turn on the indicator without reloading | `ms-experience-gateway` |

**Component diagram:** [`diagrams/components.pdf`](diagrams/components.pdf). It shows the existing systems, the central part (EventBridge, `ms-template-catalog`, `ms-change-summary`, LLM inference) and, within each region, regional EventBridge, SNS/SQS with DLQ, `ms-engagement-index`, `ms-publish-fanout`, `ms-backfill-reconciler`, `ms-audit-ledger` and `ms-experience-gateway`, each with its own database.

## 2. Correctness and production evolution

**What happens when a version is published:**

**Diagrams for this process** (`diagrams/` folder):

| View | File |
| --- | --- |
| Sequence: from event to indicator | [`diagrams/sequence/01a-publication-indicator.pdf`](diagrams/sequence/01a-publication-indicator.pdf) |
| Sequence: summary generation | [`diagrams/sequence/01b-publication-summary.pdf`](diagrams/sequence/01b-publication-summary.pdf) |
| Sequence: engagements without a known base | [`diagrams/sequence/01c-publication-engagements-without-base.pdf`](diagrams/sequence/01c-publication-engagements-without-base.pdf) |
| Activity: from event to indicator | [`diagrams/activity/01a-flow-publication-indicator.pdf`](diagrams/activity/01a-flow-publication-indicator.pdf) |
| Activity: summary generation | [`diagrams/activity/01b-flow-publication-summary.pdf`](diagrams/activity/01b-flow-publication-summary.pdf) |
| Activity: engagements without a known base | [`diagrams/activity/01c-flow-publication-engagements-without-base.pdf`](diagrams/activity/01c-flow-publication-engagements-without-base.pdf) |

Versions do not form a line: they form a **graph with parents**, with market/branch and published/withdrawn status. Rules:

| Situation                              | What the user sees                                                                                                                     |
| -------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------- |
| Several accumulated updates            | **A single** offer: the best reachable target, with the accumulated diff from the real base                                            |
| Rejects                                | It is recorded (audit); the engagement stays anchored to its real base and the rejection **does not block** future offers (DEC-21)    |
| Rejected v5 and v7 arrives             | v7 is offered with a diff from the real base; the v5 changes reappear. The notice "includes changes you rejected in v5" is shown       |
| Version withdrawn with a pending offer | The offer is removed and the target is recalculated                                                                                    |
| Withdrawn version already applied      | It is marked `APPLIED_WITHDRAWN` and a notice is sent; nothing is reverted, because applying content is out of scope                   |
| Branches per market                    | Only versions of the same lineage/market                                                                                               |

Diagrams: creation and decision in [`diagrams/sequence/02a-engagement-creation.pdf`](diagrams/sequence/02a-engagement-creation.pdf), [`diagrams/sequence/02b-decision-apply-reject.pdf`](diagrams/sequence/02b-decision-apply-reject.pdf), [`diagrams/activity/02a-flow-engagement-creation.pdf`](diagrams/activity/02a-flow-engagement-creation.pdf) and [`diagrams/activity/02b-flow-decision-apply-reject.pdf`](diagrams/activity/02b-flow-decision-apply-reject.pdf); version withdrawal in [`diagrams/sequence/03-version-withdrawal.pdf`](diagrams/sequence/03-version-withdrawal.pdf) and [`diagrams/activity/03-flow-withdrawal.pdf`](diagrams/activity/03-flow-withdrawal.pdf).

**Unreliable event delivery.** Outbox next to each change; idempotent consumers that discard duplicate and out-of-order events by sequence number; periodic reconciliation of the index against reality, with a frequency to be defined according to `D_stale`.

**Evolution without a maintenance window.** Hooks behind feature flags and phased deployment:

1. **Emit:** the existing systems emit events; can be turned off by flag.
2. **Shadow:** the index is built and compared against reality without being shown.
3. **UI:** the indicator is shown. It is enabled by groups of firms and region.

**Existing engagements (backfill).** `ms-backfill-reconciler` onboards them after the events are enabled, with a rate limit (`C_max`, agreed with the owning team) and prioritizing the **most active** ones. A hook on engagement load also onboards it; while it has no known base, "verifying" is shown, never "up to date".

Diagrams: [`diagrams/sequence/04a-backfill.pdf`](diagrams/sequence/04a-backfill.pdf), [`diagrams/sequence/04b-reconciliation.pdf`](diagrams/sequence/04b-reconciliation.pdf), [`diagrams/activity/04a-flow-backfill.pdf`](diagrams/activity/04a-flow-backfill.pdf) and [`diagrams/activity/04b-flow-reconciliation.pdf`](diagrams/activity/04b-flow-reconciliation.pdf).

**Data residency.** Publication events carry no firm data (template content is not firm-specific) and are replicated to each region. Index, workers and evidence live in the firm's region, encrypted with regional KMS. Only the template diff reaches the inference service, never engagement content.

**Failure recovery.** Bounded retries (`N_reint`) with backoff; circuit breaker per dependency; DLQ with idempotent reprocessing; jobs that resume where they left off; index rebuilt by reprocessing the regional EventBridge event archive (*replay*) and checking it with a targeted reconciliation. `RPO`/`RTO` targets to be defined.

Diagram: [`diagrams/sequence/05-failure-recovery.pdf`](diagrams/sequence/05-failure-recovery.pdf).

## 3. Scale, cost and operations

**The arithmetic on the brief's figures:**

| Calculation                                                                  | Result                                                                                                        | What it means                                                                                             |
| ---------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------- |
| 40 products × 1 publication/week                                             | **≈ 40 publications/week**                                                                                    | Little publication traffic                                                                                |
| 800,000 / 40 products                                                        | **≈ 20,000 engagements per publication** (uniform split across products); up to 40,000 at the largest firm   | Each publication touches many engagements                                                                |
| If the fan-out read engagements: 800,000 × 1 min                             | **13,333 h ≈ ~80 full-time workers**                                                                          | Incompatible with "seconds" and with the other team's limited capacity: **the index is mandatory**        |
| Backfill: 800,000 × 1 min                                                    | **≈ 13,333 h**; with 100 concurrent calls **≈ 5.5 days**                                                      | The real value depends on `C_max`, agreed with the owning team                                            |
| Inference: one summary per (template, source version, target version) pair   | **A few dozen per week**, generated once and shared                                                           | AI cost does not grow with the number of firms                                                            |

**Cost versus "$X"/month.** The formula first; the dollar estimate per environment follows it and the appendix:

`total cost = storage (index + evidence, per region) + compute (central + each region) + messaging + inference (a few dozen summaries/week) < X`

**Estimated AWS cost per environment.** Order-of-magnitude estimates in USD per month, from on-demand list prices retrieved on 2026-10-22, to be validated with the AWS Pricing Calculator:

| Environment | Monthly estimate (USD per month) | What it includes |
| --- | --- | --- |
| dev | ≈ $110 | Central and cell services in one EU deployment, single AZ, one small task per service, running 220 h/month |
| tst | ≈ $210 | Same footprint as dev but always on, automated test data (1% of a prod cell), 7-day logs |
| uat | ≈ $540 | Central + EU cell at prod task sizes, one task per service, single-instance databases, 5% of a prod cell's volume |
| prod | ≈ $1,950 | Central + EU cell + Canada cell, two AZs, Multi-AZ Aurora, full volumes from the brief |
| TOTAL | ≈ $2,810 | Recurring; the one-off backfill adds ≈ $15–$174 |

The design fits if `X` ≥ ≈ $1,950 per month for prod alone, or ≥ ≈ $2,810 per month if `X` also pays for dev, tst and uat; the cost is dominated by fixed capacity (Aurora instances, always-on Fargate tasks, NAT gateways), not by traffic or by the LLM (≈ $35 per month in prod). Detail and assumptions: [Appendix: AWS cost estimate](#appendix-aws-cost-estimate).

What each component consumes, using the brief's figures (unit prices and the estimate are in the appendix):

| Component | Unit consumed | How much |
| --- | --- | --- |
| Index (DynamoDB) | One record per active engagement | 800,000 in total, spread across regions |
| Evidence and audit (S3 Object Lock, PostgreSQL) | One summary per version pair plus one decision per decided engagement | A few dozen summaries per week; decisions grow with usage |
| Messaging (SNS/SQS, EventBridge) | One event per publication, creation and decision, and one message per affected engagement | ~40 publications/week × ~20,000 affected engagements ≈ 800,000 messages/week |
| Compute (ECS/EKS) | Always-on services, one per `ms-*` and region, plus one-off backfill jobs | Fixed per region plus the backfill (13,333 h of slow calls spread according to `C_max`) |
| Inference (LLM) | One summary per version pair, shared by all firms | A few dozen per week, independent of the number of firms |

We validate that `X` suffices by adding up each row with its rate; the uniform split across products is an assumption, so the 800,000 messages/week are an order of magnitude and not a given.

The variable volume is proportional to ~40 publications/week plus creations and decisions; the **fixed compute** per region is expected to weigh more. If `X` is tight, services are merged (DISENO §13.2) and inference has a configurable spending cap.

**Service level objectives (SLOs)** (values to be defined with product/SRE):

| What we measure                      | Target                                                              |
| ------------------------------------ | ------------------------------------------------------------------- |
| Publication → indicator visible      | p95 < `N_ind` seconds                                               |
| Summary available (RNF-002)          | p95 < `N_res`                                                       |
| Indicator matches reality            | Divergence ≤ `P_div` after reconciliation; lag ≤ `D_stale`          |
| Query availability                   | `Disp`                                                              |
| Load on the Engagements System       | Never above `C_max`                                                 |

**Observability and operations.** Everything in CloudWatch with OpenTelemetry, with a correlation identifier that travels from the publication to the indicator:

| What is measured | What for | Alert |
| --- | --- | --- |
| Publication → indicator latency | Meet `N_ind` | p95 exceeds `N_ind` |
| Age of the oldest message and queue depth | Detect blockages | Grows steadily |
| Messages in DLQ | See events that were not processed | Any new message |
| Backfill progress and queries to the Engagements System | Respect `C_max` | Approaches or exceeds `C_max` |
| Reconciliation divergences | Detect a lagging index | Exceeds `P_div` |
| Circuit breaker status | Isolate a failed dependency | Circuit open |
| Inference spend | Respect the configurable cap | Approaches the cap |

Each alert has a response procedure; for the DLQ it is to reprocess idempotently after fixing the cause.

## 4. Generation and evaluation of the summaries

- **Input:** the JSON diff between two versions (the brief provides a fast, reliable comparison). Never engagement data.
- **Structured output** in which **each claim references the diff element** that supports it.
- **Per version pair and cached**: generated once and reused for all firms.
- **Defensible in November.** Stored immutably in S3 with Object Lock: input diff, its hash, model and version, prompt version, output and date; retention `T_ret`. `ms-audit-ledger` records, in the firm's region, which summary was shown and what was decided.
- **Alongside the original diff** on screen.
- **One already shown is never regenerated:** if the prompt or model changes, a new versioned one is created.
- A deterministic non-AI version (list of changes from the diff) exists as a fallback if inference fails or the cap is exhausted; the template text is treated as data, not as instructions, and the output is validated against a fixed format.
- **Evaluation:** reference cases; automatic checks (each claim exists in the diff, no material changes are omitted); sampling with human review of content; regression when the prompt or model changes.

## 5. Trade-offs, assumptions and the requirement I would challenge

| We chose                                           | Instead of                  | What we accept                                                  |
| -------------------------------------------------- | --------------------------- | --------------------------------------------------------------- |
| Own index fed by events                            | Reading engagements         | Lag bounded by `D_stale` and a periodic reconciliation          |
| Precompute the offer at publication                | Compute it at query time    | Work spikes at each publication                                 |
| Rejection that does not block                      | Block the rejected version  | What was rejected reappears in later versions                   |
| `ms-*` services per domain, with a merge plan      | A single application        | More fixed cost and operations                                  |

**Declared assumptions, to be confirmed with product or with the owning team:**

- **"Active"** = non-archived engagement. If it is something else, the size and priority of the backfill change.
- **Firm region:** each Engagements System emits in its own region. If not, a firm→region map is needed.
- **Enumerable catalog:** existing engagements can be listed without loading them. If not, the backfill depends on a list from the owning team or only on the load hooks.
- **Sequence in hooks:** events carry a per-engagement sequence number. If not, idempotency relies on event id + base version and reconciliation weighs more.
- **Idempotent downstream:** repeating the slow query has no double effect. If not, idempotency keys agreed with the owning team are needed.

**Requirement I would challenge: "real time, in seconds".** What is challenged is **not the indicator**, but that the requirement implies reading engagements: at ~1 minute per read, "seconds" is only possible at the index level. Proposal: **indicator in seconds and summary in minutes**. Second objection: that the requirement covers all 800,000 equally, when only part of them is actually active.

## Riskiest part to get right

The **correctness of the index** (duplicate, lost or out-of-order events, and the onboarding of existing engagements) and the **graph semantics** (accumulation, rejection, withdrawal, branches). A wrong indicator leads to applying or discarding by mistake, and nobody notices because checking it costs a minute per engagement. They are mitigated by the sequence number, "verifying", the shadow phase, reconciliation and mandatory graph scenarios before deployment.

## What I deliberately left out

Applying the update content, the actual interface (only the data it receives is defined), authentication, detailed multi-region replication, email or Slack notifications, and the implementation of everything that is not `ms-publish-fanout`.

## Use of AI

- **Where it helped:** structuring this document, tables and diagrams.
- **Where it was corrected:** the rejection rule (changed from blocking to non-blocking), one-minute call timeouts, figures recalculated by hand and values not supported by the brief.
- **Where not to trust it without tests:** retry and concurrency logic, DynamoDB key design, IAM permissions, data migrations.

## Appendix: AWS cost estimate

All figures are **order-of-magnitude estimates** in USD per month, to be validated with the AWS Pricing Calculator. Method: quantity × unit price = monthly cost, 730 hours per month, on-demand list prices. Free tiers are ignored (conservative). Excluded: taxes, AWS Support plan, the cost on the Engagements System side, data transfer to the Internet (indicator payloads are a few bytes) and the EKS control-plane fee (the estimate assumes ECS on Fargate). Assumptions not given by the brief: the central deployment runs in eu-west-1; the 800,000 engagements are split 50/50 between the EU cell (eu-west-1) and the Canada cell (ca-central-1); each environment lives in its own AWS account.

### A.1 Assumptions per environment

| Assumption | dev | tst | uat | prod |
| --- | --- | --- | --- | --- |
| Deployments | One EU deployment with central and cell services | One EU deployment with central and cell services | Central + EU cell (eu-west-1) | Central (eu-west-1) + EU cell (eu-west-1) + Canada cell (ca-central-1) |
| Availability Zones | 1 | 1 | 2 for networking, databases single-instance | 2, Multi-AZ databases |
| Tasks per service (ECS on Fargate) | 1 × 0.25 vCPU / 0.5 GB | 1 × 0.25 vCPU / 0.5 GB | 1 × 0.5 vCPU / 1 GB; `ms-experience-gateway` 1 × 1 vCPU / 2 GB | 2 × 0.5 vCPU / 1 GB; `ms-backfill-reconciler` 1 task; `ms-experience-gateway` 2 × 1 vCPU / 2 GB |
| Running hours | 220 h/month (10 h × 22 working days, scheduled shutdown) | 730 h/month | 730 h/month | 730 h/month |
| PostgreSQL | One shared Aurora Serverless v2 cluster (one database per service), avg 0.5 ACU, auto-pause when idle | One shared Aurora Serverless v2 cluster, avg 0.5 ACU | One Aurora cluster per service, 1 × db.t4g.medium each (4 instances) | One Aurora cluster per service, writer + reader db.t4g.medium in two AZs (central 4, each cell 4 instances) |
| DynamoDB | On-demand, 0.5% of a prod cell's volume | On-demand, 1% of a prod cell's volume | On-demand, 5% of a prod cell's volume | On-demand; per cell 400,000 index items, 6.0 M writes and 10 M reads per month |
| Messaging per cell | 0.5% of prod plus idle polling | 1% of prod plus idle polling | 5% of prod plus idle polling | 800,000 messages/week × 52 / 12 ≈ 3.47 M/month, ≈ 1.73 M per cell |
| ElastiCache (Redis OSS) | 1 × cache.t4g.micro, 220 h | 1 × cache.t4g.micro | 2 × cache.t4g.small (central + cell) | 2 × cache.t4g.small per deployment (primary + replica) |
| Network | 1 ALB, 1 NAT gateway | 1 ALB, 1 NAT gateway | 1 ALB, 1 NAT gateway | 1 ALB and 2 NAT gateways per deployment |
| Log retention | 3 days | 7 days | 14 days | 90 days |
| LLM calls per month | 35 | 35 | 70 (20% of prod) | 350 |

How prod volumes are derived (per cell): ≈ 1.73 M affected-engagement messages per month × ~3 DynamoDB writes each (index, fan-out task, screen notification) ≈ 5.2 M, plus ≈ 0.8 M for creations, decisions and reconciliation ≈ **6.0 M writes**; SQS ≈ 1.73 M × 3 requests (send, receive, delete) ≈ 5.2 M plus ≈ 1.8 M of long polling ≈ **7.0 M requests**; evidence ≈ 200,000 decisions per month, ≈ 25 GB kept in S3. Non-prod volumes are the stated percentages of one prod cell.

### A.2 Unit prices

Source for every verified row: AWS Price List API (`https://pricing.us-east-1.amazonaws.com/offers/v1.0/aws/<service code>/current/<region>/index.json`), retrieved 2026-10-22.

| Service | Unit | eu-west-1 | ca-central-1 | Source / date |
| --- | --- | --- | --- | --- |
| Fargate vCPU (Linux/x86) | vCPU-hour | $0.04048 | $0.04456 | `AmazonECS`, 2026-10-22 |
| Fargate memory | GB-hour | $0.004445 | $0.004865 | `AmazonECS`, 2026-10-22 |
| Aurora PostgreSQL db.t4g.medium | instance-hour | $0.078 | $0.079 | `AmazonRDS`, 2026-10-22 |
| Aurora PostgreSQL db.r7g.large (sensitivity only) | instance-hour | $0.304 | $0.304 | `AmazonRDS`, 2026-10-22 |
| Aurora Serverless v2 | ACU-hour | $0.14 | $0.14 | `AmazonRDS`, 2026-10-22 |
| Aurora storage / I/O (Standard) | GB-month / million I/O | $0.11 / $0.22 | $0.11 / $0.22 | `AmazonRDS`, 2026-10-22 |
| DynamoDB on-demand writes / reads | million request units | $0.705 / $0.1415 | $0.6875 / $0.1375 | `AmazonDynamoDB`, 2026-10-22 |
| DynamoDB storage | GB-month | $0.283 | $0.275 | `AmazonDynamoDB`, 2026-10-22 |
| SQS standard | million requests | $0.40 | $0.40 | `AWSQueueService`, 2026-10-22 |
| SNS publish (delivery to SQS free) | million API requests | $0.50 | $0.50 | `AmazonSNS`, 2026-10-22 |
| EventBridge custom events / archive | million events / GB | $1.00 / $0.11 | $1.00 / $0.12 | `AWSEvents`, 2026-10-22 |
| S3 Standard (Object Lock has no extra price) / PUT | GB-month / million PUT | $0.023 / $5.00 | $0.025 / $5.50 | `AmazonS3`, 2026-10-22 |
| ElastiCache Redis OSS cache.t4g.small / micro | node-hour | $0.034 / $0.017 | $0.035 / $0.018 | `AmazonElastiCache`, 2026-10-22 |
| ElastiCache Valkey cache.t4g.small (savings lever) | node-hour | $0.0272 | $0.028 | `AmazonElastiCache`, 2026-10-22 |
| Application Load Balancer | ALB-hour / LCU-hour | $0.0252 / $0.008 | $0.02475 / $0.0088 | `AWSELB`, 2026-10-22 |
| NAT gateway | hour / GB processed | $0.048 / $0.048 | $0.05 / $0.05 | `AmazonEC2`, 2026-10-22 |
| CloudWatch logs ingestion / storage | GB / GB-month | $0.57 / $0.03 | $0.55 / $0.033 | `AmazonCloudWatch`, 2026-10-22 |
| CloudWatch custom metrics / alarms | metric-month / alarm-month | $0.30 / $0.10 | $0.30 / $0.10 | `AmazonCloudWatch`, 2026-10-22 |
| KMS | key-month / million requests | $1.00 / $0.30 | $1.00 / $0.30 | `awskms`, 2026-10-22 |
| Secrets Manager | secret-month | $0.40 | $0.40 | `AWSSecretsManager`, 2026-10-22 |
| Data transfer EU (Ireland) to Canada (Central) | GB | $0.02 | — | `AWSDataTransfer`, 2026-10-22 |
| Bedrock, Claude Sonnet 4.6 (regional) | million input / output tokens | $3.30 / $16.50 | — | `AmazonBedrockFoundationModels`, 2026-10-22 |
| Fargate Spot discount (savings lever) | % vs on-demand | up to ~70% | up to ~70% | unverified (AWS marketing figure, not in the price list) |
| Compute Savings Plans discount for Fargate (savings lever) | % vs on-demand | up to ~50% | up to ~50% | unverified |

### A.3 Production

**prod, central (eu-west-1):** `ms-template-catalog`, `ms-change-summary`, central EventBridge and LLM inference.

| Item | Quantity | Unit price | Monthly cost |
| --- | --- | --- | --- |
| Fargate vCPU | 2 services × 2 tasks × 0.5 vCPU × 730 h = 1,460 | $0.04048 / vCPU-hour | $59.10 |
| Fargate memory | 2 × 2 tasks × 1 GB × 730 h = 2,920 | $0.004445 / GB-hour | $12.98 |
| Aurora PostgreSQL instances | 2 clusters × 2 instances (Multi-AZ) × 730 h = 2,920 | $0.078 / instance-hour | $227.76 |
| Aurora storage | 4 GB | $0.11 / GB-month | $0.44 |
| Aurora I/O | 10 M | $0.22 / million I/O | $2.20 |
| SQS requests | 0.5 M | $0.40 / million requests | $0.20 |
| SNS publishes | 0.01 M | $0.50 / million API requests | $0.01 |
| EventBridge events | 0.01 M | $1.00 / million custom events | $0.01 |
| S3 Object Lock storage | 1 GB | $0.023 / GB-month | $0.02 |
| S3 PUT requests | 0.001 M | $5.00 / million PUT requests | $0.01 |
| ElastiCache nodes | 2 nodes × 730 h = 1,460 | $0.034 / node-hour | $49.64 |
| ALB hours | 1 ALB × 730 h = 730 | $0.0252 / ALB-hour | $18.40 |
| ALB LCU | 1 LCU × 730 h = 730 | $0.008 / LCU-hour | $5.84 |
| NAT gateway hours | 2 NAT (one per AZ) × 730 h = 1,460 | $0.048 / NAT gateway-hour | $70.08 |
| NAT data processed | 50 GB | $0.048 / GB processed | $2.40 |
| CloudWatch logs ingestion | 10 GB | $0.57 / GB logs ingested | $5.70 |
| CloudWatch logs storage | 30 GB (90 days) | $0.03 / GB-month logs stored | $0.90 |
| CloudWatch custom metrics | 40 metrics | $0.30 / custom metric-month | $12.00 |
| CloudWatch alarms | 10 alarms | $0.10 / alarm-month | $1.00 |
| KMS keys | 3 keys | $1.00 / key-month | $3.00 |
| KMS requests | 0.5 M | $0.30 / million requests | $0.15 |
| Secrets Manager secrets | 4 secrets | $0.40 / secret-month | $1.60 |
| Inter-region transfer | 1 GB template events/summaries to CA | $0.02 / GB EU to Canada | $0.02 |
| Bedrock input tokens | 350 calls × 20,000 tokens = 7 | $3.30 / million input tokens | $23.10 |
| Bedrock output tokens | 350 calls × 2,000 tokens = 0.7 | $16.50 / million output tokens | $11.55 |
| Other (ECR, Route 53, backups, PITR) | 5% of $508.11 | — | $25.41 |
| **Total** | | | **$533.52** |

**prod, EU cell (eu-west-1):** `ms-engagement-index`, `ms-publish-fanout`, `ms-backfill-reconciler`, `ms-audit-ledger`, `ms-experience-gateway`.

| Item | Quantity | Unit price | Monthly cost |
| --- | --- | --- | --- |
| Fargate vCPU | 5 services, 9 tasks = 5.5 vCPU × 730 h = 4,015 | $0.04048 / vCPU-hour | $162.53 |
| Fargate memory | 11 GB × 730 h = 8,030 | $0.004445 / GB-hour | $35.69 |
| Aurora PostgreSQL instances | 2 clusters × 2 instances (Multi-AZ) × 730 h = 2,920 | $0.078 / instance-hour | $227.76 |
| Aurora storage | 25 GB | $0.11 / GB-month | $2.75 |
| Aurora I/O | 40 M | $0.22 / million I/O | $8.80 |
| DynamoDB writes | 6.0 M WRU | $0.705 / million write request units | $4.23 |
| DynamoDB reads | 10 M RRU | $0.1415 / million read request units | $1.41 |
| DynamoDB storage | 1.5 GB | $0.283 / GB-month | $0.42 |
| SQS requests | 7.0 M | $0.40 / million requests | $2.80 |
| SNS publishes | 1.0 M | $0.50 / million API requests | $0.50 |
| EventBridge events | 1.0 M | $1.00 / million custom events | $1.00 |
| EventBridge archive | 1 GB | $0.11 / GB archived | $0.11 |
| S3 Object Lock storage | 25 GB | $0.023 / GB-month | $0.57 |
| S3 PUT requests | 0.2 M | $5.00 / million PUT requests | $1.00 |
| ElastiCache nodes | 2 nodes × 730 h = 1,460 | $0.034 / node-hour | $49.64 |
| ALB hours | 1 ALB × 730 h = 730 | $0.0252 / ALB-hour | $18.40 |
| ALB LCU | 2 LCU × 730 h = 1,460 | $0.008 / LCU-hour | $11.68 |
| NAT gateway hours | 2 NAT × 730 h = 1,460 | $0.048 / NAT gateway-hour | $70.08 |
| NAT data processed | 50 GB | $0.048 / GB processed | $2.40 |
| CloudWatch logs ingestion | 30 GB | $0.57 / GB logs ingested | $17.10 |
| CloudWatch logs storage | 90 GB (90 days) | $0.03 / GB-month logs stored | $2.70 |
| CloudWatch custom metrics | 100 metrics | $0.30 / custom metric-month | $30.00 |
| CloudWatch alarms | 30 alarms | $0.10 / alarm-month | $3.00 |
| KMS keys | 3 keys | $1.00 / key-month | $3.00 |
| KMS requests | 2.0 M | $0.30 / million requests | $0.60 |
| Secrets Manager secrets | 8 secrets | $0.40 / secret-month | $3.20 |
| Other (ECR, Route 53, backups, PITR) | 5% of $661.37 | — | $33.07 |
| **Total** | | | **$694.44** |

**prod, Canada cell (ca-central-1):** same services and volumes as the EU cell, at Canada prices.

| Item | Quantity | Unit price | Monthly cost |
| --- | --- | --- | --- |
| Fargate vCPU | 5 services, 9 tasks = 5.5 vCPU × 730 h = 4,015 | $0.04456 / vCPU-hour | $178.91 |
| Fargate memory | 11 GB × 730 h = 8,030 | $0.004865 / GB-hour | $39.07 |
| Aurora PostgreSQL instances | 2 clusters × 2 instances (Multi-AZ) × 730 h = 2,920 | $0.079 / instance-hour | $230.68 |
| Aurora storage | 25 GB | $0.11 / GB-month | $2.75 |
| Aurora I/O | 40 M | $0.22 / million I/O | $8.80 |
| DynamoDB writes | 6.0 M WRU | $0.6875 / million write request units | $4.12 |
| DynamoDB reads | 10 M RRU | $0.1375 / million read request units | $1.38 |
| DynamoDB storage | 1.5 GB | $0.275 / GB-month | $0.41 |
| SQS requests | 7.0 M | $0.40 / million requests | $2.80 |
| SNS publishes | 1.0 M | $0.50 / million API requests | $0.50 |
| EventBridge events | 1.0 M | $1.00 / million custom events | $1.00 |
| EventBridge archive | 1 GB | $0.12 / GB archived | $0.12 |
| S3 Object Lock storage | 25 GB | $0.025 / GB-month | $0.62 |
| S3 PUT requests | 0.2 M | $5.50 / million PUT requests | $1.10 |
| ElastiCache nodes | 2 nodes × 730 h = 1,460 | $0.035 / node-hour | $51.10 |
| ALB hours | 1 ALB × 730 h = 730 | $0.02475 / ALB-hour | $18.07 |
| ALB LCU | 2 LCU × 730 h = 1,460 | $0.0088 / LCU-hour | $12.85 |
| NAT gateway hours | 2 NAT × 730 h = 1,460 | $0.05 / NAT gateway-hour | $73.00 |
| NAT data processed | 50 GB | $0.05 / GB processed | $2.50 |
| CloudWatch logs ingestion | 30 GB | $0.55 / GB logs ingested | $16.50 |
| CloudWatch logs storage | 90 GB (90 days) | $0.033 / GB-month logs stored | $2.97 |
| CloudWatch custom metrics | 100 metrics | $0.30 / custom metric-month | $30.00 |
| CloudWatch alarms | 30 alarms | $0.10 / alarm-month | $3.00 |
| KMS keys | 3 keys | $1.00 / key-month | $3.00 |
| KMS requests | 2.0 M | $0.30 / million requests | $0.60 |
| Secrets Manager secrets | 8 secrets | $0.40 / secret-month | $3.20 |
| Other (ECR, Route 53, backups, PITR) | 5% of $689.05 | — | $34.45 |
| **Total** | | | **$723.50** |

**prod total:** $533.52 + $694.44 + $723.50 = **$1,951.46 per month**.

### A.4 Non-production

**uat (eu-west-1):** prod-like topology with one cell; it keeps prod task sizes (to test performance and SSE) but runs one task per service and no Multi-AZ, so it costs ≈ 28% of prod.

| Item | Quantity | Unit price | Monthly cost |
| --- | --- | --- | --- |
| Fargate vCPU | 7 services × 1 task (gateway 1 vCPU, rest 0.5) × 730 h = 2,920 | $0.04048 / vCPU-hour | $118.20 |
| Fargate memory | 8 GB × 730 h = 5,840 | $0.004445 / GB-hour | $25.96 |
| Aurora PostgreSQL instances | 4 clusters × 1 instance (no Multi-AZ) × 730 h = 2,920 | $0.078 / instance-hour | $227.76 |
| Aurora storage | 5 GB | $0.11 / GB-month | $0.55 |
| Aurora I/O | 4 M | $0.22 / million I/O | $0.88 |
| DynamoDB writes | 0.3 M (5%) | $0.705 / million write request units | $0.21 |
| DynamoDB reads | 0.5 M (5%) | $0.1415 / million read request units | $0.07 |
| DynamoDB storage | 0.1 GB | $0.283 / GB-month | $0.03 |
| SQS requests | 1.6 M (mostly polling) | $0.40 / million requests | $0.64 |
| SNS publishes | 0.05 M | $0.50 / million API requests | $0.03 |
| EventBridge events | 0.05 M | $1.00 / million custom events | $0.05 |
| EventBridge archive | 0.1 GB | $0.11 / GB archived | $0.01 |
| S3 Object Lock storage | 2 GB | $0.023 / GB-month | $0.05 |
| S3 PUT requests | 0.01 M | $5.00 / million PUT requests | $0.05 |
| ElastiCache nodes | 2 nodes (central + cell) × 730 h = 1,460 | $0.034 / node-hour | $49.64 |
| ALB hours | 1 ALB × 730 h = 730 | $0.0252 / ALB-hour | $18.40 |
| ALB LCU | 0.5 LCU × 730 h = 365 | $0.008 / LCU-hour | $2.92 |
| NAT gateway hours | 1 NAT × 730 h = 730 | $0.048 / NAT gateway-hour | $35.04 |
| NAT data processed | 5 GB | $0.048 / GB processed | $0.24 |
| CloudWatch logs ingestion | 10 GB | $0.57 / GB logs ingested | $5.70 |
| CloudWatch logs storage | 5 GB (14 days) | $0.03 / GB-month logs stored | $0.15 |
| CloudWatch custom metrics | 50 metrics | $0.30 / custom metric-month | $15.00 |
| CloudWatch alarms | 20 alarms | $0.10 / alarm-month | $2.00 |
| KMS keys | 4 keys | $1.00 / key-month | $4.00 |
| KMS requests | 0.2 M | $0.30 / million requests | $0.06 |
| Secrets Manager secrets | 6 secrets | $0.40 / secret-month | $2.40 |
| Bedrock input tokens | 70 calls × 20,000 tokens = 1.4 | $3.30 / million input tokens | $4.62 |
| Bedrock output tokens | 70 calls × 2,000 tokens = 0.14 | $16.50 / million output tokens | $2.31 |
| Other (ECR, Route 53, backups, PITR) | 5% of $516.97 | — | $25.85 |
| **Total** | | | **$542.82** |

**tst (eu-west-1):** one deployment, single AZ, minimal tasks, always on for automated test runs.

| Item | Quantity | Unit price | Monthly cost |
| --- | --- | --- | --- |
| Fargate vCPU | 7 tasks × 0.25 vCPU × 730 h = 1,277.5 | $0.04048 / vCPU-hour | $51.71 |
| Fargate memory | 7 × 0.5 GB × 730 h = 2,555 | $0.004445 / GB-hour | $11.36 |
| Aurora Serverless v2 capacity | 1 shared cluster, avg 0.5 ACU × 730 h = 365 | $0.14 / ACU-hour | $51.10 |
| Aurora storage | 2 GB | $0.11 / GB-month | $0.22 |
| Aurora I/O | 2 M | $0.22 / million I/O | $0.44 |
| DynamoDB writes | 0.06 M (1%) | $0.705 / million write request units | $0.04 |
| DynamoDB reads | 0.1 M (1%) | $0.1415 / million read request units | $0.01 |
| DynamoDB storage | 0.05 GB | $0.283 / GB-month | $0.01 |
| SQS requests | 1.35 M (mostly polling) | $0.40 / million requests | $0.54 |
| SNS publishes | 0.01 M | $0.50 / million API requests | $0.01 |
| EventBridge events | 0.01 M | $1.00 / million custom events | $0.01 |
| S3 Object Lock storage | 0.5 GB | $0.023 / GB-month | $0.01 |
| S3 PUT requests | 0.002 M | $5.00 / million PUT requests | $0.01 |
| ElastiCache node | 1 node × 730 h = 730 | $0.017 / node-hour | $12.41 |
| ALB hours | 1 ALB × 730 h = 730 | $0.0252 / ALB-hour | $18.40 |
| ALB LCU | 0.2 LCU × 730 h = 146 | $0.008 / LCU-hour | $1.17 |
| NAT gateway hours | 1 NAT × 730 h = 730 | $0.048 / NAT gateway-hour | $35.04 |
| NAT data processed | 2 GB | $0.048 / GB processed | $0.10 |
| CloudWatch logs ingestion | 3 GB | $0.57 / GB logs ingested | $1.71 |
| CloudWatch logs storage | 0.7 GB (7 days) | $0.03 / GB-month logs stored | $0.02 |
| CloudWatch custom metrics | 20 metrics | $0.30 / custom metric-month | $6.00 |
| CloudWatch alarms | 5 alarms | $0.10 / alarm-month | $0.50 |
| KMS keys | 2 keys | $1.00 / key-month | $2.00 |
| KMS requests | 0.05 M | $0.30 / million requests | $0.01 |
| Secrets Manager secrets | 4 secrets | $0.40 / secret-month | $1.60 |
| Bedrock input tokens | 35 calls × 20,000 tokens = 0.7 | $3.30 / million input tokens | $2.31 |
| Bedrock output tokens | 35 calls × 2,000 tokens = 0.07 | $16.50 / million output tokens | $1.16 |
| Other (ECR, Route 53, backups, PITR) | 5% of $197.90 | — | $9.90 |
| **Total** | | | **$207.80** |

**dev (eu-west-1):** like tst, but tasks, cache and database run only 220 h per month (scheduled shutdown outside working hours); ALB and NAT gateway stay on.

| Item | Quantity | Unit price | Monthly cost |
| --- | --- | --- | --- |
| Fargate vCPU | 7 tasks × 0.25 vCPU × 220 h (scheduled) = 385 | $0.04048 / vCPU-hour | $15.58 |
| Fargate memory | 7 × 0.5 GB × 220 h = 770 | $0.004445 / GB-hour | $3.42 |
| Aurora Serverless v2 capacity | 1 shared cluster, avg 0.5 ACU × 220 h (auto-pause otherwise) = 110 | $0.14 / ACU-hour | $15.40 |
| Aurora storage | 1 GB | $0.11 / GB-month | $0.11 |
| Aurora I/O | 0.5 M | $0.22 / million I/O | $0.11 |
| DynamoDB writes | 0.03 M (0.5%) | $0.705 / million write request units | $0.02 |
| DynamoDB reads | 0.05 M (0.5%) | $0.1415 / million read request units | $0.01 |
| DynamoDB storage | 0.02 GB | $0.283 / GB-month | $0.01 |
| SQS requests | 0.42 M | $0.40 / million requests | $0.17 |
| SNS publishes | 0.005 M | $0.50 / million API requests | $0.00 |
| EventBridge events | 0.005 M | $1.00 / million custom events | $0.01 |
| S3 Object Lock storage | 0.2 GB | $0.023 / GB-month | $0.00 |
| S3 PUT requests | 0.001 M | $5.00 / million PUT requests | $0.01 |
| ElastiCache node | 1 node × 220 h = 220 | $0.017 / node-hour | $3.74 |
| ALB hours | 1 ALB × 730 h = 730 | $0.0252 / ALB-hour | $18.40 |
| ALB LCU | 0.1 LCU × 730 h = 73 | $0.008 / LCU-hour | $0.58 |
| NAT gateway hours | 1 NAT × 730 h = 730 | $0.048 / NAT gateway-hour | $35.04 |
| NAT data processed | 1 GB | $0.048 / GB processed | $0.05 |
| CloudWatch logs ingestion | 1 GB | $0.57 / GB logs ingested | $0.57 |
| CloudWatch logs storage | 0.1 GB (3 days) | $0.03 / GB-month logs stored | $0.00 |
| CloudWatch custom metrics | 10 metrics | $0.30 / custom metric-month | $3.00 |
| KMS keys | 2 keys | $1.00 / key-month | $2.00 |
| KMS requests | 0.02 M | $0.30 / million requests | $0.01 |
| Secrets Manager secrets | 4 secrets | $0.40 / secret-month | $1.60 |
| Bedrock input tokens | 35 calls × 20,000 tokens = 0.7 | $3.30 / million input tokens | $2.31 |
| Bedrock output tokens | 35 calls × 2,000 tokens = 0.07 | $16.50 / million output tokens | $1.16 |
| Other (ECR, Route 53, backups, PITR) | 5% of $103.31 | — | $5.17 |
| **Total** | | | **$108.48** |

**All environments:** dev $108.48 + tst $207.80 + uat $542.82 + prod $1,951.46 = **$2,810.56 per month** (≈ $2,810 in section 3).

### A.5 LLM inference

"A few dozen summaries per week" is taken as 40 per week × 52 / 12 ≈ 175 per month, doubled to **350 calls per month** for evaluation runs and prompt or model regressions. Each call: **20,000 input tokens** (prompt + JSON diff) and **2,000 output tokens** (structured summary with references), with Claude Sonnet 4.6 on Bedrock in the EU region. Per summary: 0.02 × $3.30 + 0.002 × $16.50 ≈ $0.10. Prod: 7 M × $3.30 + 0.7 M × $16.50 = **$34.65 per month**. Because a summary is shared by all firms, this does not grow with the number of firms.

### A.6 One-off versus recurring costs

The tables above are recurring. The main one-off cost is the **backfill**: 800,000 × 1 min ≈ 13,333 h of slow calls. Assuming, for this estimate only, `C_max` = 100 concurrent calls split 50/50 between cells, each cell runs 50 calls in parallel for 400,000 / 50 min ≈ 133 h (≈ 5.5 days). The calls wait on the Engagements System, so worker cost depends on how many calls one task can hold:

| Scenario | Workers per cell | Compute (EU + CA) | Messaging and index writes (EU + CA) | One-off total |
| --- | --- | --- | --- | --- |
| Asynchronous workers, 25 calls per task | 2 × 0.5 vCPU / 1 GB × 133 h | $6.58 + $7.24 | $0.76 + $0.76 | **$15.34** |
| One call per task (pessimistic) | 50 × 0.25 vCPU / 0.5 GB × 133 h | $82.28 + $90.48 | $0.76 + $0.76 | **$174.28** |

Messaging and index writes per cell: 0.4 M writes × DynamoDB price + 1.2 M SQS requests × $0.40 per million. The backfill is cheap in AWS terms; its real cost is time and the load on the Engagements System, bounded by `C_max`. Other one-off costs (initial environment build, load tests) are not estimated.

### A.7 Sensitivity

- **Aurora instance size and Multi-AZ** move the total most: Aurora instances are ≈ $914 of the ≈ $2,810 monthly total. Moving the 12 prod instances to db.r7g.large adds ≈ $1,977 per month (prod roughly doubles); dropping the prod readers (no Multi-AZ) saves ≈ $343 per month at the price of availability.
- **Fargate task count** is the second driver: doubling the prod tasks adds ≈ $488 per month.
- **NAT gateways** (≈ $318 per month in hours across all environments) are the third driver; VPC endpoints for AWS services reduce the data part, but not the hourly fee.
- **Traffic** barely matters: DynamoDB, SQS, SNS, EventBridge and S3 together are under $40 per month in prod, so a 2× error in the 800,000 messages/week changes the total by tens of dollars.
- **LLM** is small: even 10× more calls adds ≈ $312 per month, and the configurable spending cap bounds it.

### A.8 Savings levers

| Lever | Where | Effect |
| --- | --- | --- |
| Compute Savings Plans | prod Fargate | Up to ~50% on prod Fargate (unverified percentage) |
| Fargate Spot | `ms-publish-fanout` and backfill workers (idempotent, resumable) | Up to ~70% on those tasks (unverified percentage) |
| Scheduled shutdown | dev (already assumed), tst and uat outside working hours | Applying 220 h/month to tst and uat cuts their task, cache and Serverless v2 hours by ≈ 70% |
| Aurora Serverless v2 with low minimum ACU and auto-pause | dev and tst (already assumed); uat | Pays per ACU-hour instead of always-on instances |
| Reserved instances for Aurora | prod | Lower hourly price for 1-3 year commitment (percentage not verified) |
| Valkey instead of Redis OSS | All ElastiCache nodes | −20% per node-hour; ≈ $40 per month on the prod and uat nodes |
| Merge services (section 3) | All | Fewer tasks and databases if `X` is tight |

## Glossary

### Parameters

| Parameter  | What it means                                                                                                                                              |
| ---------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `N_ind`    | Maximum seconds from when a version is published until the pending-updates indicator is visible. The brief requires "seconds".                             |
| `N_res`    | Maximum time until the change summary is available after publication.                                                                                      |
| `C_max`    | Maximum number of simultaneous queries allowed to the Engagements System, because its capacity is limited and it belongs to another team.                  |
| `T_call`   | Maximum wait time for a slow call (~1 minute) to the Engagements System before considering it failed.                                                      |
| `T_ret`    | How long the audit evidence (summaries, diffs, decisions) is retained.                                                                                     |
| `X`        | Monthly budget from the brief ("$X") that the total cost must respect. Its value is unknown; the appendix estimates how large it must be.                 |
| `D_stale`  | Maximum tolerable lag between the indicator and the reality of the engagements.                                                                            |
| `P_div`    | Percentage of divergence tolerable between the index and reality after a reconciliation.                                                                   |
| `Disp`     | Service availability target.                                                                                                                               |
| `RPO`      | How much data can be lost, measured in time, in a disaster.                                                                                                |
| `RTO`      | The maximum time the service takes to recover from a disaster.                                                                                             |
| `N_reint`  | Maximum number of retries on a failure before sending the message to the dead-letter queue.                                                                |

### Terms

| Term                       | What it means                                                                                                                                  |
| -------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------- |
| Engagement file            | A firm's working file for a client during the year, created from a template.                                                                   |
| Template / version         | Product template published by the content team; each update generates a new version.                                                           |
| Version graph              | Versions form a graph and not a line: each has parents, market or branch, and published or withdrawn status.                                   |
| Base version               | Real template version to which an engagement is anchored.                                                                                      |
| Offer                      | Pending update shown to the user: the best reachable target with the accumulated diff from the base.                                           |
| Diff                       | Structured difference (JSON) between two versions of a template.                                                                               |
| Summary                    | Readable text that explains the diff; each claim points to the exact change that supports it.                                                  |
| Apply / reject             | The firm's decision on an offer. The rejection is recorded, but does not block future offers.                                                  |
| `APPLIED_WITHDRAWN`        | Status of an already applied version that was later withdrawn: a notice is sent and nothing is reverted.                                       |
| Index (projection)         | Own record, fed by events, that says which version each engagement has without opening it.                                                     |
| Fan-out                    | Distribution of a publication to all affected engagements.                                                                                     |
| Backfill                   | Initial load of the index with the engagements that existed before the system.                                                                 |
| Reconciliation             | Periodic comparison of the index with reality to correct lags.                                                                                 |
| Outbox                     | Pattern that stores the event together with the data change so it is neither lost nor duplicated.                                              |
| Idempotent                 | Repeating the same operation or message does not produce a double effect.                                                                      |
| SSE (Server-Sent Events)   | One-way channel over HTTP through which the server notifies the screen that there are changes; the browser reconnects on its own and resumes from the last notification received. |
| DLQ                        | Dead-letter queue, where messages that could not be processed are held for reprocessing.                                                       |
| Regional cell              | Complete deployment of a service in one region, so that engagement data does not leave it.                                                     |
| Feature flag / kill switch | Switch to enable or disable a capability without deploying code.                                                                               |
| Hook                       | Point added to an existing system that emits an event when an action occurs (publish, create, decide, open).                                   |
| Shadow phase               | Stage in which the index is built and compared against reality without being shown to the user.                                                |
| Circuit breaker            | Mechanism that stops calling a dependency that fails repeatedly and tries it again later.                                                      |
| SLO / p95                  | Measurable service objective; p95 is the value below which 95% of the measurements fall.                                                       |
| DEC-21                     | Design decision on rejection: it is recorded and does not block future offers (see `DISENO.md`, design document, Spanish).                     |

### Services

| Service                  | What it is for                                                                           |
| ------------------------ | ---------------------------------------------------------------------------------------- |
| `ms-template-catalog`    | Maintains the validated copy of the templates' version graph.                            |
| `ms-change-summary`      | Generates and stores the summary of each version pair, once for all firms.               |
| `ms-engagement-index`    | Stores which version each engagement has, its pending offer and the decisions.           |
| `ms-publish-fanout`      | Distributes each publication to the affected engagements while respecting `C_max`.       |
| `ms-backfill-reconciler` | Onboards existing engagements and corrects index lags.                                   |
| `ms-audit-ledger`        | Keeps the immutable evidence of which summary was shown and what was decided.            |
| `ms-experience-gateway`  | Delivers the indicator and the pending offers to the user.                               |
