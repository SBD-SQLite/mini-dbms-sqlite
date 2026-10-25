# Contributing Guide

The `main` branch is protected. Direct pushes are not allowed; every change must go through a Pull Request.

## Workflow

1. Sync `main` and create a new branch:
   ```bash
   git switch main && git pull
   git switch -c feat/qp/select-parser
   ```
2. Commit your changes following the conventions below, then push the branch.
3. Open a Pull Request targeting `main`. The PR title must follow the same convention.
4. A PR can be merged only when:
   - the **Conventions** check passes, and
   - it has at least one approval from a member of **@SBD-SQLite/group-leads**.

   Pushing new commits after approval dismisses the existing approval, so the PR must be re-approved.

## Conventions

**Types:** `feat`, `fix`, `docs`, `test`, `refactor`, `perf`, `ci`, `chore`

**Scopes:** `qp` (Query Processor), `qo` (Query Optimizer), `sm` (Storage Manager), `ccm` (Concurrency Control Manager), `frm` (Failure Recovery Manager), `global` (cross-component or repository-wide)

| | Format | Example |
|---|---|---|
| Branch name | `<type>/<scope>/<short-description>` (kebab-case) | `fix/sm/buffer-flush` |
| Commit message & PR title | `<type>(<scope>): <description>` (scope optional) | `feat(qo): add join reordering` |

Git-generated `Merge ...` and `Revert ...` commits are allowed.

## Project Rules

- Only modify code within your own group's component directory. Coordinate with the owning group before making cross-component changes.
- Use standard libraries only. Libraries that implement core DBMS functionality (e.g. `sqlite3`, `pandas`, SQL parser libraries) are prohibited.
