# mini-dbms-sqlite

A mini database management system for IF3140 Sistem Basis Data (ITB, 2026), built by supergroup **SQLike**. It accepts SQL from a CLI, optimizes and executes queries, controls concurrent transactions, and recovers from failures.

## Components

| Folder | Component | Responsibility |
|---|---|---|
| [`query-processor`](query-processor) | Query Processor (QP) | Sessions, transaction commands, and query plan execution |
| [`query-optimizer`](query-optimizer) | Query Optimizer (QO) | Parsing, semantic validation, and cost-based plan selection |
| [`storage-manager`](storage-manager) | Storage Manager (SM) | Records, pages, buffers, indexes, catalog, and statistics |
| [`concurrency-control`](concurrency-control) | Concurrency Control Manager (CCM) | Transaction lifecycle, lock-based and timestamp-based access control |
| [`failure-recovery`](failure-recovery) | Failure Recovery Manager (FRM) | Write-ahead log, rollback, checkpoint, and crash recovery |

Each folder has its own README and class diagram under `docs/`.

## Contracts

Components talk only through the interfaces and shared data types agreed in the SQLike supergroup report: interfaces in `sqlike.contract`, shared types in `sqlike.shared`.

## Requirements

- JDK 17 or newer
- Java standard library only, no third-party libraries

## Drivers

| Component | Entry point |
|---|---|
| QP | `query-processor/run-tests.sh` |
| SM | `sqlike.storage.StorageDriver` |
| CCM | `sqlike.ccm.CCMDriver` |
| FRM | `sqlike.frm.driver.FRMDriver` |

See each component's README for build and run steps.

## Contributing

Each group works on `<type>/<scope>/<description>` branches, merges them into `develop/<scope>`, and then opens a Pull Request from `develop/<scope>` to `main`. Branch names, commit messages, and PR titles are checked by CI. See [CONTRIBUTING.md](CONTRIBUTING.md).

## Milestones

| Milestone | Deadline |
|---|---|
| Milestone 1 | 31 October 2026 |
| Milestone 2 | 7 November 2026 |
| Milestone 3 | 14 November 2026 |
| Final integration | 21 November 2026 |
| Demo | 23 to 27 November 2026 |

Releases are tagged `v<milestone>.<version>`.
