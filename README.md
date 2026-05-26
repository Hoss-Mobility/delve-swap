# Per-Config Delve Debugger Path

JetBrains GoLand plugin that lets you override the Delve (`dlv`) debugger binary
on a per-run-configuration basis. Falls back to the global `dlv.path` setting
when no override is set.

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

1. **Marketplace:** GoLand → Settings → Plugins → Marketplace → search
   "Per-Config Delve".
2. **From disk:** download the latest `.zip` from Releases, then
   Plugins → ⚙ → Install plugin from disk.

## Use

1. Open any Go Build or Go Test run configuration.
2. Expand the **Delve** section.
3. Uncheck **Use global dlv.path** and pick a binary.
4. Save. Debug.

Paths support:

- IntelliJ path macros: `$PROJECT_DIR$`, `$USER_HOME$`.
- Environment variables: `$VAR` and `${VAR}` — resolved against the launched
  process's environment (OS env plus the run config's custom env vars).

## How it works

Before each Go debug launch, an `ExecutionListener` reads the override from the
run configuration, expands macros and env vars, validates the resulting path,
and temporarily swaps the IDE's global `dlv.path` Registry value. The original
value is restored as soon as the debug process terminates. A sentinel file is
written before the swap so the original value can be recovered automatically
on next IDE start if the IDE crashes mid-session.

Concurrent debug sessions are tracked with a small reference count: the
original `dlv.path` is captured on the first active session and only restored
after the last one ends. During overlapping sessions the Registry value shows
the most-recently-swapped path, which is harmless because the Go plugin reads
`dlv.path` synchronously at launch time, not continuously.

## Limitations

- **Go Build and Go Test only.** Go Remote attaches to an already-running
  `dlv` on a remote host, so a local-binary override doesn't apply.
- **Targets GoLand 2026.1+.** Older versions are not supported in v0.1.
- **Concurrent debug sessions:** while two sessions are in flight, the Registry
  briefly holds the second session's path; this is invisible in practice but
  worth knowing if you depend on inspecting `dlv.path` mid-debug.

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
