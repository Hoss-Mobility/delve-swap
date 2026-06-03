# Contributing to DelveSwap

Thanks for taking the time to dig in. DelveSwap is a small focused plugin — most
contributions land in a single PR.

## Quick start

```bash
git clone git@github.com:Hoss-Mobility/delve-swap.git
cd delve-swap
./gradlew test                      # JUnit 5, no IDE bootstrap, ~seconds
./gradlew buildPlugin               # produces build/distributions/delveswap-*.zip
./gradlew runIde                    # launch sandbox GoLand with the plugin loaded
```

The Gradle wrapper takes care of provisioning the right Gradle and JDK 21 toolchain;
you do not need a global Gradle install.

## Useful gradle properties

Set on the command line as `-P<name>=<value>` or in your local `~/.gradle/gradle.properties`:

| Property | Effect |
|----------|--------|
| `localPlatformPath=/path/to/GoLand` | Develop against an already-installed GoLand instead of downloading 2026.1 (~1 GB). |
| `skipSearchableOptions=true` | Skip the IDE-launched indexing step in `buildPlugin` for faster local iteration. |
| `localPluginRepo=file:///path/to/mirror` | Offline Gradle-plugin mirror. |

## Running a single test

```bash
./gradlew test --tests com.hoss.delveswap.DlvPathSwapListenerTest
./gradlew test --tests "*sessionKey*"
```

## Verifying compatibility

```bash
./gradlew verifyPlugin    # slow: pulls multiple GoLand builds (~1.5 GB)
```

CI runs this on every PR against GoLand 261 and 262. If your change touches
internal Go-plugin APIs, expect the verifier to flag it.

## Code style

- Kotlin idioms over Java idioms. Prefer `data class`, `sealed interface`,
  `object`, expression bodies, named arguments at call sites.
- Default to **no comments**. Add one only when *why* is non-obvious — a hidden
  constraint, a workaround for a specific bug, a JetBrains-version quirk.
- Pure functions over IDE-coupled ones. Tests run without an IDE harness; the
  codebase factors out `resolveDesiredValue`, `recoveryAction`, `sessionKeyFor`,
  `resolveForLaunch`, `PathExpansion.expandEnv` so they can be unit-tested in
  isolation. New logic should follow the same shape.
- Backward-compatible XML: `DelvePathSettings.readFrom` migrates the v0.1.0
  `useGlobal` boolean to the v1.0.0 `source` enum. If you change the schema,
  add a similar migration and a test.

## Trust boundary (don't forget)

Run-config XML is committed to VCS, so a teammate who can commit it can also
set the `dlv` binary path you'll launch. The plugin validates exists+executable
before swapping. Prefer `$PROJECT_DIR$/...` paths for shared run configs.

## Reporting bugs / asking questions

Open an issue: <https://github.com/Hoss-Mobility/delve-swap/issues>.

Include:
- GoLand build (Help → About).
- Your `dlv` binary version (`dlv version`).
- Whether the failure is local debug, Run-on-Target SSH, or both.
- The relevant snippet from `idea.log` (Help → Show Log in Files).

## Release flow

Maintainers: bump `pluginVersion` in `gradle.properties`, add an entry to
`CHANGELOG.md` and to the `<change-notes>` block in `plugin.xml`, tag `vX.Y.Z`,
push. `.github/workflows/release.yml` signs and publishes to the JetBrains
Marketplace. The required secrets (`CERTIFICATE_CHAIN`, `PRIVATE_KEY`,
`PRIVATE_KEY_PASSWORD`, `PUBLISH_TOKEN`) live in the repo's GitHub Actions
configuration.
