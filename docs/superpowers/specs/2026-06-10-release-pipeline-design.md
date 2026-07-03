# Release pipeline: package plugin zip on release tag (issue #4)

**Date:** 2026-06-10
**Issue:** [#4 Create build pipeline](../../../.github) — "Create automated build pipeline, to package the plugin on release tag."

## Problem

`release.yml` already runs on `v*` tags, but it executes
`buildPlugin verifyPlugin signPlugin publishPlugin` as a **single Gradle
invocation in a single step**. On the v1.0.0 tag the run failed at
`publishPlugin` (`'token' property must be specified` — the `PUBLISH_TOKEN`
Marketplace secret is not configured), and because everything was one step,
**no packaged zip was produced as a release asset**. The manually created
v1.0.0 GitHub Release has zero assets.

Key facts from the failed run:

- `signPlugin` **succeeded** → `CERTIFICATE_CHAIN` / `PRIVATE_KEY` /
  `PRIVATE_KEY_PASSWORD` secrets are configured. Signing stays unconditional.
- Only `publishPlugin` failed → Marketplace publishing must become optional so
  it can never block packaging.

## Goal

On every `v*` tag push: build, verify, and sign the plugin; attach the signed
zip to a GitHub Release for that tag; publish to JetBrains Marketplace **only
if** `PUBLISH_TOKEN` is configured, without failing the run when it is not.

## Design (Approach A — single job, split steps)

Modify `.github/workflows/release.yml` only. Keep the single `release` job
(one disk-cleanup + one GoLand platform download), but split the Gradle
invocation into separate steps:

1. **Build + verify + sign:**
   `gradle --no-daemon test buildPlugin verifyPlugin signPlugin`
   with the three signing secrets in `env`. A `verifyPlugin` failure aborts
   before any release is created — no half-baked releases. On failure, upload
   the verifier report as a workflow artifact (same as ci.yml).
2. **GitHub Release:** create the release for the tag if it does not exist,
   otherwise reuse it (v1.0.0 was created by hand — `gh release upload`
   against an existing release must work). Attach the signed zip from
   `build/distributions/` (signPlugin outputs `*-signed.zip`; verify the exact
   name during implementation and glob accordingly). Release notes: extract
   the matching `## [X.Y.Z]` section from `CHANGELOG.md` (Keep-a-Changelog
   format); fall back to GitHub auto-generated notes if the section is
   missing. Implemented with the `gh` CLI (already on runners) rather than a
   third-party action.
3. **Marketplace publish (optional):**
   `gradle --no-daemon publishPlugin` as its own step, gated with
   `if: env.PUBLISH_TOKEN != ''` (secret mapped into `env` at job level so the
   expression is valid). When the token is absent the step is skipped and the
   run stays green; a log notice says why.

Additional workflow changes:

- `permissions: contents: write` (required for release creation/upload;
  default token is read-only).
- `GH_TOKEN: ${{ github.token }}` for the `gh` CLI steps.

## Out of scope

- ci.yml restructuring / `workflow_call` reuse (Approach C — rejected as
  harder to read for no gain at this repo's scale).
- Multi-job pipeline (Approach B — rejected: each Gradle job re-downloads the
  GoLand platform; disk-heavy and slow for no real benefit).
- Prerelease handling (`v1.2.0-rc1` → `--prerelease` flag) — not requested;
  can be added later if RC tags become a practice.
- Backfilling the v1.0.0 release asset.

## Error handling

| Failure | Behavior |
|---|---|
| `test`/`buildPlugin`/`verifyPlugin`/`signPlugin` fails | Run fails, no release created/updated; verifier report uploaded as artifact. |
| Release already exists for the tag | Reuse it; upload asset with `--clobber`. |
| `PUBLISH_TOKEN` missing | Publish step skipped, run green. |
| `publishPlugin` fails (token present but invalid) | Run fails **after** the zip is already attached — packaging is never lost. |

## Testing / verification

- `actionlint` on the workflow file (if available locally).
- Shell-test the CHANGELOG-section extraction against the real CHANGELOG.md.
- Real end-to-end verification requires a tag push; do it with the next
  release tag (or a throwaway RC tag if earlier validation is wanted).
