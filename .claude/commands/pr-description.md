---
description: Generate or update PR_DESCRIPTION.md from the current branch diff
argument-hint: "[notes, issue refs, or 'update PR']"
---

# PR description

Use this command when the user wants `PR_DESCRIPTION.md` generated or updated from the current branch diff. Consider `$ARGUMENTS` before starting.

## Contract

- Write `PR_DESCRIPTION.md` at the repository root.
- Base every claim on the diff against the PR base branch.
- Follow `.github/pull_request_template.md` when it exists. Otherwise use the output structure below.
- Preserve user-written notes and checklist states unless current evidence contradicts them.
- Produce deterministic output. Do not add timestamps, invented motivation, or random wording.
- Never stage files or change branch history.

## Inspect the branch

1. Confirm the repository root and check for an active rebase or cherry-pick. Stop if either is in progress.
2. Determine the base branch from the PR template. Use `main` only when the template does not name one.
3. Fetch the base branch from its remote.
4. Warn if the worktree is dirty because uncommitted changes are outside the branch diff.
5. Confirm `HEAD` contains the fetched base tip. If not, stop and tell the user to update the branch.
6. Inspect the diff, stat, changed paths, relevant surrounding code, and commit messages.
7. Identify the goal, behavior changes, affected contracts, intentional deletions, integration points, risks, and available verification.

For a large diff, group repeated changes by pattern and cite representative files. Do not replace analysis with arbitrary file-count or line-ratio thresholds.

Ask for clarification only when intent or verification cannot be recovered from `$ARGUMENTS`, the diff, code, issue references, or repository documentation. Do not write the file until required product context is known.

## Output structure

Use the repository template's headings and checklist wording when available. Otherwise write:

```markdown
## Title
<type>(<scope>): <description>

## Summary
- **Goal**: <user-visible intent>
- **Before**: <previous behavior, only when existing behavior changed>
- **After**: <new behavior, only when existing behavior changed>
- **New**: <new capability, used instead of Before/After for net-new work>
- **Implementation**: <reviewer-relevant design or integration detail>

## How to Test
1. `<repository-backed command>`
   Expected: <observable result>

## Related Issues
- <issue reference, or None.>

## Author Checklist
- [ ] <template item>

## Additional Notes
- <risk, migration note, review focus, dirty-worktree warning, or None.>
```

## Content rules

- Derive the Conventional Commit title from the primary change, following the type and scope list in `CONTRIBUTING.md`.
- Explain behavior and design. Do not list every file or restate the diff.
- Use separate bullets for independently reviewable components.
- Include `Before` and `After` only for changed behavior. Use `New` for new capabilities.
- Mention specification files only when they are the deliverable or materially affect implementation.
- Include reproducible integration or manual checks that exist in the repository. Use `uv run` for Python commands.
- State expected results. Do not claim a command passed unless it was run successfully.
- Include unit tests when they are material verification. Do not exclude evidence merely because it is a unit test.
- Preserve related issue links without duplicates.
- Check only checklist items verified in this run. Leave uncertain items unchecked.
- Put key review areas, setup requirements, known gaps, and dirty-worktree warnings in `Additional Notes`.
- Use `- None.` for an empty optional section.
- Never add AI attribution (e.g. "Generated with Claude Code", `Co-Authored-By: Claude`).

## Update behavior

When `PR_DESCRIPTION.md` already exists:

- recompute diff-derived content;
- retain still-valid manual notes and checked items;
- remove obsolete generated content;
- sort file-based review notes by path, and
- avoid duplicate issues, checks, and notes.

If `$ARGUMENTS` asks to update an existing GitHub PR, resolve the PR unambiguously and update its title and body after writing the file.

Report the title, analyzed base branch, changed-file count, output path, and any unverified checklist items.
