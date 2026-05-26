# Manual Smoke Matrix

Run on each supported GoLand version (start with 2026.1+) before publishing a release.

## Setup

1. Build two distinct `dlv` binaries — `/tmp/dlv-alpha` and `/tmp/dlv-beta`. Wrap each as a shell script that writes its own path to `/tmp/dlv-invoked.log` before `exec`-ing the real dlv:

   ```sh
   #!/usr/bin/env bash
   echo "$(date -Iseconds) $0 $@" >> /tmp/dlv-invoked.log
   exec /usr/local/bin/dlv "$@"
   ```

2. Set the global `dlv.path` (Help → Find Action → Registry → `dlv.path`) to `/tmp/dlv-alpha`.

## Matrix

For each row, clear `/tmp/dlv-invoked.log`, run a Debug session, and read the log to confirm the expected binary was invoked.

| Config type | Override state | Path (raw) | Expected `/tmp/dlv-invoked.log` entry |
|---|---|---|---|
| Go Build | disabled | — | `/tmp/dlv-alpha ...` |
| Go Build | enabled | `/tmp/dlv-beta` | `/tmp/dlv-beta ...` |
| Go Build | enabled | `$PROJECT_DIR$/dlv-beta` (symlink in project) | `<project>/dlv-beta ...` |
| Go Build | enabled | `${ARM_DLV}` (env set to `/tmp/dlv-beta`) | `/tmp/dlv-beta ...` |
| Go Test | disabled | — | `/tmp/dlv-alpha ...` |
| Go Test | enabled | `/tmp/dlv-beta` | `/tmp/dlv-beta ...` |
| Concurrent | A=beta, B=alpha (start A then B before A ends) | — | A's launch uses beta; B's launch uses alpha; after both end, Registry reads `/tmp/dlv-alpha` |

## Failure-mode checks

| Scenario | Expected behavior |
|---|---|
| Override path doesn't exist on disk | Warning notification "File does not exist"; launch proceeds with global `dlv.path`. |
| Override path is set but not executable | Warning notification; launch proceeds using the path anyway (OS will fail to exec, surfacing a clear error). |
| IDE force-killed mid-debug-session | On next IDE start, `dlv.path` is restored to its pre-swap value (verify via Registry editor). |
