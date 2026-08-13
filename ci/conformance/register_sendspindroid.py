"""Register the sendspindroid client adapter in a Sendspin/conformance checkout.

Usage: python register_sendspindroid.py <path-to-conformance-checkout>

Copies sendspindroid_client.py into the harness's adapters package and
appends an implementation registry entry. Idempotent. Kept as a patch
script (rather than a fork) so we always test against the harness's HEAD;
if the IMPLEMENTATIONS dict shape changes upstream, this script fails
loudly and needs updating.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

REGISTRY_ENTRY = '''
IMPLEMENTATIONS["sendspindroid"] = ImplementationSpec(
    name="sendspindroid",
    display_name="SendSpinDroid",
    repo_dirname="sendspindroid",
    remote_url="https://github.com/chrisuthe/sendspindroid.git",
    client=RoleSpec(
        supported=True,
        adapter_kind="python",
        entrypoint="conformance.adapters.sendspindroid_client",
        supports_server_initiated=False,
        supports_client_initiated=True,
        supports_flac=False,
        supports_opus=False,
        supports_discovery=False,
        supported_role_families=("player",),
    ),
    server=RoleSpec(
        supported=False,
        adapter_kind="placeholder",
        entrypoint="conformance.adapters.placeholder",
        reason="SendSpinDroid is a client-only implementation.",
    ),
)
'''


SERVER_ADAPTER = "aiosendspin_server.py"
HARDCODED_FLAG = "allow_unencrypted=True"
ENV_FLAG = (
    'allow_unencrypted=os.environ.get("CONFORMANCE_ALLOW_UNENCRYPTED", "1") '
    'not in ("0", "false", "False")'
)


def patch_server_encryption_flag(conformance: Path) -> int:
    """Make the harness's aiosendspin server adapter honour an env var.

    The adapter hardcodes `allow_unencrypted=True`, which silently accepts the
    pre-encryption dialect and so cannot serve as a Phase 1 target. Rewriting it
    to read CONFORMANCE_ALLOW_UNENCRYPTED (default "1", i.e. current behaviour)
    lets CI flip one variable instead of forking the harness.

    Fails loudly rather than silently proceeding if the literal moves upstream,
    matching this script's existing doctrine.
    """
    adapter = conformance / "src" / "conformance" / "adapters" / SERVER_ADAPTER
    if not adapter.exists():
        print(f"{SERVER_ADAPTER} not found; harness layout changed upstream", file=sys.stderr)
        return 1

    content = adapter.read_text(encoding="utf-8")
    if ENV_FLAG in content:
        print(f"{SERVER_ADAPTER} already patched")
        return 0
    if HARDCODED_FLAG not in content:
        print(
            f"{SERVER_ADAPTER} no longer contains {HARDCODED_FLAG!r}; "
            f"update this script",
            file=sys.stderr,
        )
        return 1

    content = content.replace(HARDCODED_FLAG, ENV_FLAG)
    if not re.search(r"^import os$", content, re.MULTILINE):
        # Insert before the first regular import, but AFTER any
        # `from __future__` import, which the language requires to come first.
        lines = content.splitlines(keepends=True)
        insert_at = 0
        for i, line in enumerate(lines):
            if line.startswith("from __future__"):
                insert_at = i + 1
            elif line.startswith(("import ", "from ")):
                insert_at = max(insert_at, i)
                break
        lines.insert(insert_at, "import os\n")
        content = "".join(lines)

    adapter.write_text(content, encoding="utf-8")
    print(f"Patched {SERVER_ADAPTER} to honour CONFORMANCE_ALLOW_UNENCRYPTED")
    return 0


def main() -> int:
    if len(sys.argv) != 2:
        print(__doc__, file=sys.stderr)
        return 2
    conformance = Path(sys.argv[1]).resolve()
    implementations = conformance / "src" / "conformance" / "implementations.py"
    adapters_dir = conformance / "src" / "conformance" / "adapters"
    if not implementations.exists() or not adapters_dir.is_dir():
        print(f"Not a conformance checkout: {conformance}", file=sys.stderr)
        return 1

    wrapper_src = Path(__file__).parent / "sendspindroid_client.py"
    (adapters_dir / "sendspindroid_client.py").write_text(
        wrapper_src.read_text(encoding="utf-8"), encoding="utf-8"
    )

    if patch_server_encryption_flag(conformance) != 0:
        return 1

    content = implementations.read_text(encoding="utf-8")
    if 'IMPLEMENTATIONS["sendspindroid"]' in content:
        print("sendspindroid already registered")
        return 0
    if "IMPLEMENTATIONS:" not in content or "ImplementationSpec(" not in content:
        print("implementations.py shape changed upstream; update this script", file=sys.stderr)
        return 1

    implementations.write_text(content + REGISTRY_ENTRY, encoding="utf-8")
    print(f"Registered sendspindroid in {implementations}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
