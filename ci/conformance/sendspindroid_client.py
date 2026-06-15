"""sendspindroid client adapter for the Sendspin conformance harness.

Copied into the harness's src/conformance/adapters/ directory by
register_sendspindroid.py (see this repo's ci/conformance/). Launches the
fat jar built from android/conformance-client, which drives SendSpinDroid's
shared protocol layer.
"""

from __future__ import annotations

import os
import shutil
import subprocess
import sys
from pathlib import Path


def _jar_path() -> Path | None:
    env = os.environ.get("SENDSPINDROID_CLIENT_JAR")
    return Path(env) if env else None


def main() -> None:
    jar = _jar_path()
    if jar is None or not jar.exists():
        print(
            "SENDSPINDROID_CLIENT_JAR is not set or does not exist.\n"
            "Build it with: cd android && ./gradlew :conformance-client:fatJar",
            file=sys.stderr,
        )
        sys.exit(1)

    java = os.environ.get("JAVA_HOME")
    java_bin = str(Path(java) / "bin" / "java") if java else shutil.which("java")
    if java_bin is None:
        print("No java executable found. Set JAVA_HOME or add java to PATH.", file=sys.stderr)
        sys.exit(1)

    result = subprocess.run([java_bin, "-jar", str(jar)] + sys.argv[1:], check=False)
    sys.exit(result.returncode)


if __name__ == "__main__":
    main()
