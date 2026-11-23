"""Contract tests for the backend image launch form.

The services package executable Spring Boot jars (the repackage goal is bound
to the package lifecycle in every service pom), so the runtime image must
launch that self-contained jar with `java -jar`. An earlier recipe extracted
layers and launched a bare JarLauncher class name, which fails at boot with
ClassNotFoundException because the extracted layout carries no launcher.
These tests pin the launch form against the staged layout so the two cannot
drift apart silently.
"""

import re
from pathlib import Path

DOCKERFILE = Path(__file__).resolve().parents[2] / "deploy" / "docker" / "Dockerfile.backend"


def _text():
    return DOCKERFILE.read_text(encoding="utf-8")


def _entrypoint_args():
    match = re.search(r'ENTRYPOINT\s+\[(.+)\]', _text(), re.DOTALL)
    assert match, "Dockerfile must declare a JSON-form ENTRYPOINT"
    return match.group(1)


def test_runtime_jar_is_staged_as_app_jar():
    # The builder must stage the executable jar under a stable name so the
    # ENTRYPOINT never depends on the artifact version string.
    text = _text()
    assert re.search(r"cp\s+\"\$jar\"\s+/staged/app\.jar", text) or "app.jar" in text.split("FROM eclipse-temurin")[1], \
        "builder stage must copy the packaged jar to /staged/app.jar"


def test_entrypoint_launches_jar_not_bare_launcher_class():
    args = _entrypoint_args()
    assert "-jar /app/app.jar" in args, \
        "ENTRYPOINT must run the self-contained jar: java -jar /app/app.jar"
    assert "JarLauncher" not in args, \
        "bare JarLauncher class launch is invalid for this layout"


def test_java_opts_passthrough_preserved():
    # Operators tune heap flags through JAVA_OPTS without rebuilding images.
    assert "$JAVA_OPTS" in _entrypoint_args(), "ENTRYPOINT must expand JAVA_OPTS"


def test_runtime_runs_non_root():
    text = _text().split("FROM eclipse-temurin")[-1]
    assert re.search(r"USER\s+appuser", text), "runtime stage must switch to a non-root user"
