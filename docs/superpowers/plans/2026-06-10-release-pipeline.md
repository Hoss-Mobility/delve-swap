# Release Packaging Pipeline Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** On every `v*` tag push, build/verify/sign the plugin, attach the signed zip to a GitHub Release with CHANGELOG-derived notes, and publish to JetBrains Marketplace only when `PUBLISH_TOKEN` is configured (issue #4).

**Architecture:** Single-job rewrite of `.github/workflows/release.yml` (Approach A from the spec). The previously monolithic Gradle invocation is split into three steps — build+verify+sign, GitHub Release attach via `gh` CLI, optional `publishPlugin` gated on the secret — so Marketplace failures can never block packaging. No other files change.

**Tech Stack:** GitHub Actions, `gh` CLI (preinstalled on runners), Gradle (IntelliJ Platform Gradle Plugin), awk for CHANGELOG section extraction.

**Spec:** `docs/superpowers/specs/2026-06-10-release-pipeline-design.md`

**Background facts (verified):**
- The v1.0.0 run failed only at `publishPlugin` (`'token' property must be specified`), so signing secrets (`CERTIFICATE_CHAIN`, `PRIVATE_KEY`, `PRIVATE_KEY_PASSWORD`) ARE configured; `PUBLISH_TOKEN` is NOT.
- `signPlugin` outputs `build/distributions/delveswap-<version>-signed.zip` (`pluginName = delveswap` in `gradle.properties`). Glob `*-signed.zip`.
- A GitHub Release for a tag may already exist (v1.0.0 was created by hand), so the upload step must handle both create and reuse.
- CHANGELOG.md is Keep-a-Changelog format; version headers look like `## [1.1.0] — 2026-06-03`.

---

### Task 1: Validate the CHANGELOG release-notes extraction snippet locally

This is the only non-trivial logic in the workflow, so test it against the real CHANGELOG before embedding it. (CI YAML can't be unit-tested; this is the test-first analog.)

**Files:**
- Read only: `CHANGELOG.md` (repo root)

- [ ] **Step 1: Run the extraction snippet for an existing version**

```bash
cd /home/skast/vault/projects/github/delve-swap
VERSION="1.1.0"
awk -v ver="$VERSION" '
  $0 ~ "^## \\[" ver "\\]" {flag=1; next}
  /^## \[/ {flag=0}
  flag' CHANGELOG.md
```

Expected: prints the body of the `## [1.1.0] — 2026-06-03` section (starts with a blank line then `### Changed`), stops before the next `## [...]` header, and does NOT include the `## [Unreleased]` section or any `## [...]` header lines.

- [ ] **Step 2: Run it for a version with no CHANGELOG section**

```bash
VERSION="9.9.9"
awk -v ver="$VERSION" '
  $0 ~ "^## \\[" ver "\\]" {flag=1; next}
  /^## \[/ {flag=0}
  flag' CHANGELOG.md | wc -c
```

Expected: `0` (empty output — the workflow falls back to `--generate-notes` in this case).

If either expectation fails, fix the awk expression and re-run both checks before proceeding to Task 2 (and use the fixed version there).

No commit in this task — nothing changed on disk.

---

### Task 2: Rewrite `.github/workflows/release.yml`

**Files:**
- Modify: `.github/workflows/release.yml` (replace entire file, currently 29 lines)

- [ ] **Step 1: Replace the file with the following content**

```yaml
name: Release
on:
  push:
    tags: ['v*']

# contents: write is required to create the GitHub Release and upload assets;
# the default GITHUB_TOKEN is read-only.
permissions:
  contents: write

jobs:
  release:
    runs-on: ubuntu-latest
    # Mapped to env at job level so step-level `if: env.PUBLISH_TOKEN != ''`
    # works (secrets.* is not allowed in step `if` expressions).
    env:
      PUBLISH_TOKEN: ${{ secrets.PUBLISH_TOKEN }}
    steps:
      - uses: actions/checkout@v4
      # verifyPlugin downloads two full GoLand IDEs (261 + 262) plus the
      # platform SDK and ~1.5 GB of plugin dependencies, which overruns the
      # default runner disk. Reclaim the preinstalled toolchains we don't use.
      - name: Free up disk space
        run: |
          sudo rm -rf /usr/share/dotnet /opt/ghc /usr/local/lib/android /opt/hostedtoolcache/CodeQL
          sudo docker image prune --all --force || true
          df -h /
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: 21
      - uses: gradle/actions/setup-gradle@v3
      # Packaging must never depend on Marketplace publishing succeeding
      # (the v1.0.0 release produced no asset because publishPlugin failed
      # in the same Gradle invocation), so publishPlugin runs separately below.
      - name: Build, verify, sign
        run: gradle --no-daemon test buildPlugin verifyPlugin signPlugin
        env:
          CERTIFICATE_CHAIN: ${{ secrets.CERTIFICATE_CHAIN }}
          PRIVATE_KEY: ${{ secrets.PRIVATE_KEY }}
          PRIVATE_KEY_PASSWORD: ${{ secrets.PRIVATE_KEY_PASSWORD }}
      - uses: actions/upload-artifact@v4
        if: failure()
        with:
          name: verifier-report
          path: build/reports/pluginVerifier/**
      - name: Extract release notes from CHANGELOG.md
        run: |
          VERSION="${GITHUB_REF_NAME#v}"
          awk -v ver="$VERSION" '
            $0 ~ "^## \\[" ver "\\]" {flag=1; next}
            /^## \[/ {flag=0}
            flag' CHANGELOG.md > release-notes.md
          if [ -s release-notes.md ]; then
            echo "Extracted notes for $VERSION:"
            cat release-notes.md
          else
            echo "::warning::No CHANGELOG.md section for $VERSION — using auto-generated notes."
          fi
      # The release may already exist (v1.0.0 was created by hand): reuse it
      # and just attach the asset. --clobber makes re-runs idempotent.
      - name: Attach signed plugin to GitHub Release
        env:
          GH_TOKEN: ${{ github.token }}
        run: |
          ZIP=$(ls build/distributions/*-signed.zip)
          if gh release view "$GITHUB_REF_NAME" --repo "$GITHUB_REPOSITORY" >/dev/null 2>&1; then
            gh release upload "$GITHUB_REF_NAME" "$ZIP" --repo "$GITHUB_REPOSITORY" --clobber
          elif [ -s release-notes.md ]; then
            gh release create "$GITHUB_REF_NAME" "$ZIP" --repo "$GITHUB_REPOSITORY" --title "$GITHUB_REF_NAME" --notes-file release-notes.md
          else
            gh release create "$GITHUB_REF_NAME" "$ZIP" --repo "$GITHUB_REPOSITORY" --title "$GITHUB_REF_NAME" --generate-notes
          fi
      - name: Publish to JetBrains Marketplace
        if: env.PUBLISH_TOKEN != ''
        run: gradle --no-daemon publishPlugin
      - name: Marketplace publish skipped
        if: env.PUBLISH_TOKEN == ''
        run: echo "::notice::PUBLISH_TOKEN secret not configured — skipped JetBrains Marketplace publish."
```

Notes for the implementer:
- The "Free up disk space", `setup-java`, and `setup-gradle` steps are byte-identical to the current file — keep the existing comment.
- `publishPlugin` in its own Gradle invocation reuses the build outputs from the earlier step (tasks report UP-TO-DATE); it does not duplicate the verify cost.
- Do NOT pass the signing env vars to the publish step; `publishPlugin` only needs `PUBLISH_TOKEN`, which is already in the job env.

- [ ] **Step 2: Lint the workflow**

```bash
cd /home/skast/vault/projects/github/delve-swap
if command -v actionlint >/dev/null; then
  actionlint .github/workflows/release.yml
else
  docker run --rm -v "$PWD":/repo -w /repo rhysd/actionlint:latest .github/workflows/release.yml
fi
```

Expected: no output (exit 0). If neither actionlint nor Docker is available, at minimum validate YAML syntax:

```bash
python3 -c "import yaml,sys; yaml.safe_load(open('.github/workflows/release.yml')); print('YAML OK')"
```

Expected: `YAML OK`. Fix any reported issues before committing.

- [ ] **Step 3: Commit**

```bash
git add .github/workflows/release.yml
git commit -m "ci: attach signed plugin zip to GitHub Release; make Marketplace publish optional

Closes #4. The v1.0.0 release run failed at publishPlugin (PUBLISH_TOKEN
not configured) and, because build/sign/publish were one Gradle invocation,
no packaged zip was ever attached to the release. Split the workflow so
packaging and the GitHub Release asset never depend on Marketplace publishing."
```

(Per user preference: no Co-Authored-By trailer.)

---

### Task 3: Final verification

**Files:** none (read-only checks)

- [ ] **Step 1: Confirm the working tree is clean and the commit is present**

```bash
git status --short && git log --oneline -2
```

Expected: empty status; top commit is the `ci: attach signed plugin zip...` commit.

- [ ] **Step 2: Note the end-to-end verification constraint**

Real verification requires pushing a `v*` tag, which triggers signing and a public GitHub Release — do NOT do this autonomously. Report to the user that the workflow is ready and will be exercised by the next release tag (suggest an optional throwaway `v1.1.0-rc1`-style tag if they want earlier validation; note the workflow does not mark prereleases specially — that is an explicit non-goal in the spec).
