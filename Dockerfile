# Build the delve-per-config plugin in an isolated container.
#
# Use this when the host environment can't resolve Gradle plugins
# (e.g., HTTP-client redirect handling issues with certain Gradle/JDK
# combinations on Linux).
#
# Build the image:
#   docker build -t delve-plugin-builder .
#
# One-shot full build (test + plugin zip + verifier):
#   docker run --rm \
#     -v "$PWD":/workspace \
#     -v delve-plugin-gradle-cache:/home/gradle/.gradle \
#     delve-plugin-builder
#
# Override the task chain (e.g., just compile):
#   docker run --rm -v "$PWD":/workspace -v delve-plugin-gradle-cache:/home/gradle/.gradle \
#     delve-plugin-builder compileKotlin
#
# Interactive shell for debugging:
#   docker run --rm -it --entrypoint bash \
#     -v "$PWD":/workspace -v delve-plugin-gradle-cache:/home/gradle/.gradle \
#     delve-plugin-builder
#
# Artifacts land in ./build/distributions/ on the host after a successful run.
#
# Notes:
#   - The named volume 'delve-plugin-gradle-cache' persists Gradle's plugin and
#     dependency cache across runs — first build downloads ~1 GB (the IntelliJ
#     Platform), subsequent builds are fast.
#   - This image uses `create()` to download GoLand 2026.1 at build time. The
#     local() fallback configured in build.gradle.kts is not exercised here.
#   - `runIde` is intentionally NOT supported by this image — it requires X11
#     forwarding, which is out of scope. Run it natively on the host instead.

FROM gradle:9-jdk21

WORKDIR /workspace

# Match the host user's UID/GID at runtime if requested, so files written into
# the bind-mounted workspace are owned by the user, not root or gradle:1000.
# Pass `-u $(id -u):$(id -g)` to docker run if the default doesn't match.

ENTRYPOINT ["gradle", "--no-daemon"]
CMD ["test", "buildPlugin", "verifyPlugin"]
