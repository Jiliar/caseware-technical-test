# AI Session History

This is the optional "session history for any AI tooling used" deliverable of the take-home. It is a curated, spec-driven summary of how the work was done with an AI assistant, not a verbatim transcript. Each session below follows the same shape: **intent → spec (acceptance criteria) → AI output → human review and corrections → verification → decisions**.

## 1. Tooling and way of working

| Item | Detail |
| --- | --- |
| Assistant | Claude Code (CLI), working in the repository and in a sibling documentation folder |
| Working mode | Spec-driven: requirements first, then design, then contracts, then code, then verification. The assistant acted as a coordinator and delegated the heavy reading and writing to sub-agents; each delegation carried the spec it had to satisfy and the evidence it had to return |
| Source of truth | The take-home text. Where a generated document disagreed with it, the take-home won. Precedence used when documents disagreed: take-home > delivered design > detailed design > requirements |
| Human role | Set the intent of each session, rejected anything not backed by the take-home, chose between options, and approved scope cuts |
| Time frame | One working day (2026-10-22) |
| Verification rule | A claim of "done" counts only after the commands were re-run on disk by the coordinator, not on a sub-agent's report alone |

## 2. Session overview

| # | Session | Spec / input | Main artifacts | Status |
| --- | --- | --- | --- | --- |
| 1 | Requirements | Take-home text | Product requirements document: functional and non-functional requirements, epics, features, user stories, story map, traceability matrix | Done |
| 2 | Domain decomposition | Requirements | Seven `ms-*` microservices, one database each, asynchronous communication, event and command catalog, six ADRs | Done |
| 3 | Delivered design document | Take-home Part 1 (five required topics) | The design document (2–4 pages target) with component and flow diagrams | Done |
| 4 | De-inference and sync | Human feedback: "no inference beyond the take-home" | Invented figures turned into named parameters, three documents synchronised | Done |
| 5 | Diagrams | Design document | PlantUML component, sequence and activity diagrams; later rendered to PDF | Done |
| 6 | Data contracts | Design document | PostgreSQL ER diagrams, DynamoDB item schemas and examples, event and command schemas, API request/response schemas | Done |
| 7 | Contradictions and gaps | Cross-document review | Decision records for each resolved contradiction or gap | Done |
| 8 | Part 2 design | Take-home Part 2 | Fan-out worker design with inputs, outputs, contracts, patterns | Done |
| 9 | Part 2 implementation | Part 2 design | Java 25 worker, unit tests, plain-JDK validation, CI workflow | Done |
| 10 | Scope reduction | Human feedback: "do not do more than asked" | Smaller implementation aligned with Part 2 | Done |
| 11 | Delivery polish | Review feedback | English versions, PDF diagrams, folder READMEs, project README with patterns and sequence diagram | Done |
| 12 | Infrastructure cost estimate | Take-home "all-in run cost" | Estimated AWS cost per environment (dev, tst, uat, prod) in the design document | Done |

## 3. Sessions in detail

### Session 1 — Requirements

- **Intent:** turn the take-home into a requirements document that leaves no detail out.
- **Spec:** every sentence of the take-home maps to a functional requirement, a non-functional requirement, a constraint, an assumption or a deliverable; epics, features and user stories with acceptance criteria; a user story map.
- **AI output:** a requirements document with a traceability matrix from take-home sentences to requirement ids, plus a coverage checklist.
- **Human review:** accepted the structure; later rejected several of its figures (see session 4).
- **Verification:** script checks for duplicate ids, broken references and stories missing from the story map.
- **Decisions:** "real time within seconds" is treated as an index-level property because reading an engagement takes about a minute.

### Session 2 — Domain decomposition

- **Intent:** split the solution into bounded contexts, each a microservice with its own database and asynchronous communication only.
- **Spec:** one purpose per service, one database per service, relational data in PostgreSQL and key-access data in DynamoDB, idempotent consumers, dead-letter queues, outbox.
- **AI output:** seven `ms-*` services, an event and command catalog (29 events, 7 commands), messaging topology, and six decision records.
- **Human review:** required the `ms-` prefix and the structure of the take-home design document; asked for the decline semantics (a rejection is recorded, the base version does not move, future offers are not blocked).
- **Verification:** cross-checks that every event in a diagram exists in the catalog and every epic is owned by a service.
- **Decisions:** database-per-service; separate reconciler service because merging it with the index would mix two database technologies in one service.

### Session 3 — Delivered design document

- **Intent:** produce the document the take-home asks for, with the five required topics and the closing sections.
- **Spec:** architecture and data ownership; correctness and production evolution; scale, cost and operations with arithmetic; summary generation and evaluation; trade-offs, assumptions and the requirement to challenge; riskiest part; what was left out.
- **AI output:** a condensed document derived from the detailed design, with component and flow diagrams.
- **Human review:** asked for a more functional, less technical tone while still naming the AWS services used, and a glossary of the parameters.
- **Verification:** word count against the length target; each of the five topics located by heading.
- **Decisions:** the requirement challenged is "read engagements in seconds", not the index-level indicator.

### Session 4 — De-inference and synchronisation

- **Intent:** remove everything not supported by the take-home.
- **Spec:** only statements from the take-home, from the human's instructions, or labelled design decisions; no invented numbers.
- **AI output:** an inventory of unsupported figures and services per document, then edits replacing figures by named parameters (`N_ind`, `N_res`, `C_max`, `T_call`, `T_ret`, `X`, `D_stale`, `Disp`, `RPO`/`RTO`, `N_reint`, `P_div`) and services outside the stated stack by "to be confirmed".
- **Human review (corrections):**
  - rejected labels such as "E/U/D/S" and the "origin of statements" tables inside the delivered document;
  - rejected remaining unsupported figures after the first pass;
  - rejected Mermaid in the delivered document in favour of PlantUML files.
- **Verification:** greps for forbidden values across all documents; cross-document check of services, events, parameters and requirement ids.
- **Decisions:** WebSocket replaced by Server-Sent Events for the indicator (one-way, simpler); the ledger and the index keep separate databases.
- **Incident worth recording:** a generated file was silently overwritten by an older version while another edit was in flight. It was caught by re-reading the file from disk, and the practice of re-reading before every write was adopted.

### Session 5 — Diagrams

- **Intent:** show the processes visually and keep each diagram small.
- **Spec:** component, sequence and activity diagrams for publication, creation, decision, withdrawal, backfill, reconciliation and failure recovery.
- **AI output:** one PlantUML file per diagram.
- **Human review:** diagrams were too large, so they were split per process step; Mermaid blocks were removed from the delivered document and referenced as diagram files instead.
- **Verification:** the PlantUML jar was run in syntax-check mode on every file; later each file was rendered to SVG and then to PDF, and the PDFs were checked for validity.

### Session 6 — Data contracts

- **Intent:** specify the data each service owns and what travels through the broker and the APIs.
- **Spec:** UUID ids for relational data; JSON Schema for every non-relational collection and every message; audit fields (`created_by`, `created_at`, `updated_by`, `updated_at`, `state`) on every table and item; metadata and payload for every message.
- **AI output:** four PostgreSQL ER diagrams, three DynamoDB table diagrams, 14 item schemas with 13 examples, a common message envelope plus 36 message schemas, and API request/response schemas.
- **Human review:** asked for the audit fields and the metadata after the first draft; asked for a README that explains every folder and document.
- **Verification:** every schema passes Draft 2020-12 meta-validation; every example validates against its schema; every event name in the diagrams exists in the catalog.

### Session 7 — Contradictions and gaps

- **Intent:** leave nothing contradictory or open internally.
- **Spec:** every contradiction and gap listed by the earlier sessions gets a decision recorded in the detailed design and applied everywhere (documents, diagrams, schemas).
- **AI output:** a set of numbered decisions (routing through the event bus, producer of the reprocessing commands, pagination and error conventions, idempotency header on writes, lifecycle states per entity, and others).
- **Human review:** insisted that the work stay inside the take-home and that nothing be left as a question.
- **Verification:** scripted checks for broken references, missing schemas, and examples that no longer validate.

### Session 8 — Part 2 design

- **Intent:** design the fan-out worker before programming it.
- **Spec:** the take-home's Part 2: concurrency, failure, idempotency and downstream capacity; interfaces and contracts first; tests or validation logic; a short README.
- **AI output:** a design with an inputs/outputs inventory, contracts, a behaviour-to-test table, concurrency design, failure handling and a pattern catalogue.
- **Human review (corrections):**
  - ignored coding standards from another folder;
  - asked that the design follow only what the take-home says;
  - removed fairness between firms, rate limiting, adaptive concurrency, obsolescence and graceful shutdown as out of scope.
- **Verification:** design diagrams syntax-checked; class list compared with the code later.
- **Decisions:** the slow call is used to verify and classify engagements whose base version is unknown; the index computes "pending" without calling the slow service.

### Session 9 — Part 2 implementation

- **Intent:** implement the worker in Java 25.
- **Spec:** bounded concurrency with virtual threads, retries with backoff and jitter, dead-letter queue, idempotency key `publishId:engagementId:targetVersion`, lease-based recovery, bounded reading, in-memory adapters behind interfaces; everything in English; tests and a plain-JDK validation runnable without Maven; a CI workflow.
- **AI output:** the worker, in-memory adapters, unit tests, a standalone validation program that prints inputs and outputs per scenario, and a GitHub Actions workflow with two jobs.
- **Human review (corrections):**
  - JDK 25 was required (the assistant first suggested 21 after seeing only that JDK in the environment);
  - asked that unit tests not prevent running without Maven;
  - asked for the five known limitations to be fixed instead of documented.
- **Verification:** validation program exit code, Maven build, repeated runs for flakiness; the coordinator re-ran everything from a clean build directory.

### Session 10 — Scope reduction

- **Intent:** make the implementation look like the small component the take-home asks for.
- **Spec:** a few classes and interfaces, a fake for the downstream, tests, a short README; no demo application.
- **AI output:** removal of the demo entry point, of waiting for other workers' leases, of durable re-send to the dead-letter queue and of extra state; the worker went from 25 files and about 1,060 lines to 13 files and about 590 lines.
- **Human review:** asked for the scope cut after pointing out that the first version looked like a built application; asked to keep the CI workflow.
- **Verification:** 7 validation scenarios pass; 15 unit tests pass; the main sources import no test library.
- **Decisions:** recovery by re-running after the lease expires; the permit is kept during backoff.

### Session 11 — Delivery polish

- **Intent:** make the repository readable for reviewers.
- **Spec:** English documents, PDF diagrams referenced by the documents, a README per documentation folder, and a project README with structure, how to run, CI, inputs and outputs, design patterns used and a sequence diagram.
- **AI output:** translated documents and diagrams, a folder README for the data contracts and one for the diagrams, and the project README.
- **Human review:** asked for these READMEs to explain every folder and file, to reference the CI workflow and to list the patterns actually used.
- **Verification:** all relative links checked; PDF counts compared with diagram sources.

### Session 12 — Infrastructure cost estimate

- **Intent:** state estimated AWS costs for four environments: dev, tst, uat and prod.
- **Spec:** reproducible estimates (quantity × unit price) with the source and date of each unit price; production volumes derived from the take-home numbers; compared with the unknown budget "$X".
- **AI output:** a cost summary in the scale section of the design document and an appendix with assumptions, prices and per-service costs.
- **Verification:** a script re-checked every quantity × unit price line, every subtotal and every total; unit prices were taken from AWS's own published price lists for the EU and Canada regions on 2026-10-22; only the Savings Plans and Spot discounts are marked unverified and are not in the totals.
- **Result:** about $110 (dev), $210 (tst), $540 (uat), $1,950 (prod) per month, about $2,810 in total, dominated by fixed capacity (database instances, always-on tasks, NAT gateways) and not by traffic or by the LLM.
- **Human review:** estimates are order-of-magnitude figures to validate with the AWS Pricing Calculator.

## 4. Where the AI helped, where it was corrected, and where it should not be trusted

| Question | Answer from this work |
| --- | --- |
| Where did AI help most? | Producing and cross-checking large, structured documents (requirements, traceability, event and schema catalogs); keeping many files consistent; generating test scaffolding and diagrams; running scripted consistency checks |
| Where was the AI output corrected or ignored? | Invented figures and services; labels cluttering the delivered document; a larger-than-asked implementation; an older JDK chosen because of what was installed; coding standards from an unrelated folder; a file overwritten by a stale version; extra features beyond the take-home |
| Where should AI not be trusted? | Numbers and prices without a source; concurrency and idempotency logic (needs tests that simulate failures); key design for DynamoDB; IAM and security settings; claims of completion that were not re-run; scope: the assistant tends to add more than asked |
| How to guide other engineers using AI here | Give the spec and the acceptance evidence with every request; make the take-home the highest-precedence source; ask for a list of unsupported statements before accepting a document; re-run builds and validations yourself; re-read files before editing them; keep the scope small and add features only with a requirement behind them |

## 5. Traceability from take-home to sessions

| Take-home item | Sessions |
| --- | --- |
| Part 1: architecture and data ownership | 2, 3, 5, 6 |
| Part 1: correctness and production evolution | 2, 3, 5, 7 |
| Part 1: scale, cost and operations with arithmetic | 3, 12 |
| Part 1: generation and evaluation of summaries | 1, 3 |
| Part 1: trade-offs, assumptions, requirement challenged | 3, 4 |
| Part 2: worker implementation | 8, 9, 10 |
| Part 2: tests or validation logic and README | 9, 10, 11 |
| AI usage questions | 4 and this document |
| Deliverables: design document, code, session history, diagrams | 3, 5, 9, 11, this document |
