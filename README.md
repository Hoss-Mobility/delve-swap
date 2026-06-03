# DelveSwap

Per-config Delve binaries for GoLand. A JetBrains GoLand plugin that lets you
override the Delve (`dlv`) debugger binary on a per-run-configuration basis.
Falls back to the global `dlv.path` setting when no override is set.

Filed as [GO-19800](https://youtrack.jetbrains.com/issue/GO-19800). Not on
JetBrains' roadmap, so this plugin fills the gap.

## Why

Cross-architecture Go development (e.g., ARMv7) needs different `dlv` binaries
per project. GoLand stores `dlv.path` globally and changing it typically needs
an IDE restart. With this plugin, each Go Build / Go Test run configuration can
carry its own override.

## Requirements

- GoLand **2026.1** (build 261) or newer.
- A working `dlv` binary on disk for each override path you configure.

## Install

1. **From disk (always available):** download the latest `.zip` from
   [Releases](https://github.com/Hoss-Mobility/delve-swap/releases), then
   GoLand → Settings → Plugins → ⚙ → Install plugin from disk.
2. **Marketplace:** once the plugin is approved on the JetBrains Marketplace,
   GoLand → Settings → Plugins → Marketplace → search "DelveSwap".

## Use

1. Open any Go Build or Go Test run configuration.
2. Find the **Delve** section (it appears at the bottom of the editor).
3. Choose one of the three options and (for **Custom**) pick a binary.
4. Save. Debug.

Each run configuration chooses its Delve binary from three options:

- **GoLand bundled delve** — always use the `dlv` shipped with GoLand, ignoring any IDE-wide `dlv.path`.
- **Global dlv.path** (default) — use the IDE-wide `dlv.path` captured at startup (equivalent to GoLand's bundled delve when none is configured).
- **Custom** — use a specific `dlv` binary; supports `$PROJECT_DIR$`, `$USER_HOME$`, and `$VAR` / `${VAR}` env vars.

The chosen value is asserted per debug session, so debugging two projects with different Delve binaries at the same time no longer leaks one project's binary into the other.

> Known limitation: a *Run-on-Target* (SSH) debug set to **GoLand bundled delve** while an IDE-wide `dlv.path` is configured may still use the configured global path, because the Run-on-Target path carries an explicit override rather than an "unset" signal. Local debugging is unaffected.

## How it works

Before each Go debug launch, an `ExecutionListener` reads the override from the
run configuration, expands macros and env vars, validates the resulting path,
and temporarily sets the `dlv.path` JVM system property — the value the Go
plugin reads (via `GoRunUtil.dlv()`) when choosing which `dlv` binary to
launch. The original value is restored as soon as the debug process terminates.
A sentinel file is written before the swap so the original value can be
recovered automatically on next IDE start if the IDE crashes mid-session.

Run-on-Target (SSH) debug runs as two back-to-back executions — a short build
phase, then the real debug. The override is re-applied at the second phase's
process start so it survives between the two; this is what makes per-config
overrides work for remote targets, not only local debug.

Concurrent debug sessions are tracked with a small reference count: the
original `dlv.path` is captured on the first active session and only restored
after the last one ends. During overlapping sessions the `dlv.path` property
holds the most-recently-swapped path; the Go plugin reads `dlv.path`
synchronously at `startProcess`, so each session normally picks up its own
value at the right moment. The exception — a real (rare) race — is below
under Limitations.

## Limitations

- **Go Build and Go Test only** — including their Run-on-Target (SSH) variants.
  Go Remote attaches to an already-running `dlv` on a remote host, so a
  local-binary override doesn't apply there.
- **Targets GoLand 2026.1+.** Older versions are not supported.
- **Concurrent debug sessions, narrow race:** if two debug sessions are
  scheduled close enough together that both swap calls complete before either
  session's `startProcess` reads `dlv.path`, last-writer-wins — both sessions
  see the second swap's value. Practically uncommon (the window is sub-millisecond
  on a single thread); a per-session bridge is tracked as a follow-up plan.

## Trust boundary (important for shared repos)

The override path is stored in the run configuration XML, which is committed
to VCS. **A teammate who can commit run-configuration XML can therefore set
the Delve binary path you'll launch.** The plugin checks that the file exists
and is executable before swapping, so an attacker would need to land a binary
at the resolved path (and not just commit a path) for this to result in code
execution.

To stay safe:

- Use `$PROJECT_DIR$/...` paths so the binary must live inside the
  reviewed-by-VCS project tree.
- Avoid `$USER_HOME$/...` paths on team-shared run configurations.
- Treat foreign run-configuration XML the same way you treat any other code
  in the repo — review it before opening the project.

## License

[MIT](LICENSE).
