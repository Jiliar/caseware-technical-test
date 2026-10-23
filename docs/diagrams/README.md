# Diagrams

This folder holds the diagrams of the design in PDF. They show how the system is built (components) and how it behaves in each process (sequence and activity diagrams). The data contracts that these diagrams refer to are in the `data` folder.

## Structure

```text
diagrams/
├── components.pdf    Component diagram of the whole solution
├── sequence/         9 sequence diagrams: who talks to whom, in what order
└── activity/         8 activity diagrams: the same processes as flows, with decisions
```

The `sequence` and `activity` folders cover the same processes. Use a sequence diagram to see the messages exchanged between services, and an activity diagram to see the flow of the process and its decisions. A file name starts with the process number, and the letter (`a`, `b`, `c`) separates the parts of a process that was split to keep each diagram small.

## Component diagram

| File | What it shows |
| --- | --- |
| `components.pdf` | The existing systems (Templates Service and Engagements System), the central part (central EventBridge, `ms-template-catalog`, `ms-change-summary`, LLM inference) and, inside each region, the regional EventBridge, SNS and SQS with their DLQs, `ms-engagement-index`, `ms-publish-fanout`, `ms-backfill-reconciler`, `ms-audit-ledger` and `ms-experience-gateway`, each with its own database. It also shows the slow query limited by `C_max` and that every consumer is idempotent. |

## Process diagrams

Each process appears in both folders under the same number. The file names differ only by the prefix `flow-` in the activity folder.

| Process | Sequence diagram (`sequence/`) | Activity diagram (`activity/`) | What it shows |
| --- | --- | --- | --- |
| 1a. Publication, from event to indicator | `01a-publication-indicator.pdf` | `01a-flow-publication-indicator.pdf` | A template version is published, the event is validated and copied to each region, the index finds the affected engagements without opening any of them, leaves one offer per engagement, and the indicator is shown to the firm user within `N_ind` seconds. |
| 1b. Publication, summary generation | `01b-publication-summary.pdf` | `01b-flow-publication-summary.pdf` | The summary of a (template, source version, target version) pair is reused if it exists. Otherwise the diff is sent to the LLM, with a deterministic summary as a fallback if the inference fails or the spending cap is reached, and the result is stored immutably. |
| 1c. Publication, engagements without a known base | `01c-publication-engagements-without-base.pdf` | `01c-flow-publication-engagements-without-base.pdf` | For engagements whose base version is unknown, the index shows "verifying" and `ms-publish-fanout` queries the Engagements System page by page, with at most `C_max` concurrent calls, and reports each base version back to the index. |
| 2a. Engagement creation | `02a-engagement-creation.pdf` | `02a-flow-engagement-creation.pdf` | A firm user creates an engagement, the Engagements System writes the change and the event in one transaction (outbox), and the index records the template and the version as the base version, discarding repeated or out-of-order events. |
| 2b. Apply or reject decision | `02b-decision-apply-reject.pdf` | `02b-flow-decision-apply-reject.pdf` | The user sees the offer with its summary and the original diff, and is warned if it includes changes rejected before. Applying moves the base version. Rejecting is recorded but does not change the base or block future offers. `ms-audit-ledger` records what was shown and what was decided. |
| 3. Version withdrawal | `03-version-withdrawal.pdf` | `03-flow-withdrawal.pdf` | A published version is withdrawn. Pending offers that used it are removed and the best target is recalculated. For engagements that already applied it, `APPLIED_WITHDRAWN` is set, nothing is reverted, and the user is notified. |
| 4a. Backfill of existing engagements | `04a-backfill.pdf` | `04a-flow-backfill.pdf` | Existing engagements are brought into the index with a campaign that starts with the most active ones, limited by `C_max`, with retries and a DLQ for what keeps failing. The activity diagram also shows the phased rollout (emit, shadow, UI). |
| 4b. Reconciliation | `04b-reconciliation.pdf` | `04b-flow-reconciliation.pdf` | The index is compared with reality periodically. If they diverge, the divergent engagements are re-verified and corrected. Otherwise the measurement (`P_div`, `D_stale`) is recorded. |
| 5. Recovery from event delivery failures | `05-failure-recovery.pdf` | none | How a duplicated, lost or out-of-order event is handled: duplicates are discarded, failed messages go to the DLQ after the retries and are reprocessed idempotently, and reconciliation repairs a stale index. |

Process 5 has only a sequence diagram.
