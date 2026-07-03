# Security Policy

## Supported versions

Only the latest released version receives security fixes.

## Reporting a vulnerability

Please do not open a public issue for security problems. Use GitHub's private
vulnerability reporting instead:
[Security → Report a vulnerability](https://github.com/Hoss-Mobility/delve-swap/security/advisories/new).
You'll get an acknowledgement in the advisory thread; fixes ship as a normal
plugin release.

## Threat model

DelveSwap's job is to launch the Delve binary that a run configuration points
at. The override path lives in run-configuration XML, which teams commit to
version control — so **anyone who can commit run-config XML to your repository
chooses the binary your IDE executes on the next debug session**. DelveSwap
validates that the path exists and is executable before swapping; it does not
(and cannot) verify that the binary is actually Delve or benign.

Recommendations for shared run configurations:

- Prefer `$PROJECT_DIR$/...` paths, so the binary lives inside the reviewed
  project tree.
- Avoid `$USER_HOME$/...` and absolute paths outside the repository.
- Review changes to `.run/` / `.idea/runConfigurations` XML like you review
  code — with this plugin installed, they are code.
