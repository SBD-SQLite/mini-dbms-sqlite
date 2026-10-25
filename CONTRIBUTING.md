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
4. Only members of **@SBD-SQLite/group-leads** can merge into `main`. A PR is ready to merge when:
   - the **Conventions** check passes,
   - it has at least one approval from a member of **@SBD-SQLite/group-leads**, and
   - all review conversations are resolved.

   Pushing new commits after approval dismisses the existing approval, so the PR must be re-approved.

   Group leads may bypass these requirements when merging a Pull Request (e.g. to merge their own PR). Direct pushes to `main` remain blocked for everyone.

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
