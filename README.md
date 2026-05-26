# Per-Config Delve Debugger Path

JetBrains GoLand / IntelliJ plugin that lets you override the Delve (`dlv`) debugger
binary on a per-run-configuration basis. Falls back to the global `dlv.path` setting
when no override is set.

Filed as [GO-19800](https://youtrack.jetbrains.com/issue/GO-19800). Not on JetBrains'
roadmap, so this plugin fills the gap.

## Why

Cross-architecture Go development (e.g., ARMv7) needs different `dlv` binaries per
project. GoLand stores `dlv.path` globally and changing it typically needs an IDE
restart. With this plugin, each Go Build / Go Test run configuration can carry its
own override.

## Install

1. **Marketplace:** GoLand → Settings → Plugins → Marketplace → search "Per-Config Delve".
2. **From disk:** download the latest `.zip` from Releases, then Plugins → ⚙ → Install from disk.

## Use

1. Open any Go Build or Go Test run configuration.
2. Expand the **Delve** section.
3. Uncheck **Use global dlv.path** and pick a binary.
4. Save. Debug.

Paths support:

- IntelliJ macros: `$PROJECT_DIR$`, `$USER_HOME$`.
- Environment variables: `$VAR` and `${VAR}` (resolved against the launched process's environment).

## Limitations

- Supports Go Build and Go Test only. Go Remote attaches to an already-running `dlv`
  and is out of scope.
- Targets GoLand 2026.1+ for now.
- The override is shared via VCS (stored in the run config XML). Use macros and env
  vars to keep paths portable across machines.

## License

MIT.
