"""Register the sendspindroid client adapter in a Sendspin/conformance checkout.

Usage: python register_sendspindroid.py <path-to-conformance-checkout>

Copies sendspindroid_client.py into the harness's adapters package and
appends an implementation registry entry. Idempotent. Kept as a patch
script (rather than a fork) so we always test against the harness's HEAD;
if the IMPLEMENTATIONS dict shape changes upstream, this script fails
loudly and needs updating.
"""

from __future__ import annotations

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
