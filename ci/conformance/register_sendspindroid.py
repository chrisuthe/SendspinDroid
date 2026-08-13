"""Register the sendspindroid client adapter in a Sendspin/conformance checkout.

Usage: python register_sendspindroid.py <path-to-conformance-checkout>

Copies sendspindroid_client.py into the harness's adapters package and
appends an implementation registry entry. Idempotent. Kept as a patch
script (rather than a fork) so we always test against the harness's HEAD;
if the IMPLEMENTATIONS dict shape changes upstream, this script fails
loudly and needs updating.
"""

from __future__ import annotations

import ast
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


def _binds_os(source: str) -> bool:
    """True if `source` imports os at module level."""
    try:
        tree = ast.parse(source)
    except SyntaxError:
        return False
    return any(
        isinstance(node, ast.Import) and any(a.name == "os" for a in node.names)
        for node in ast.walk(tree)
    )


def _insert_import_os(source: str) -> str:
    """Insert `import os` at the first legal module-level position.

    Uses the AST rather than scanning lines. A line-based scan cannot tell code
    from text, and adapter docstrings routinely contain usage examples whose
    lines begin with `import ` or `from ` at column 0 - inserting there puts the
    statement inside a string literal, which still compiles and then fails at
    runtime with NameError.

    `from __future__` imports must remain the first statement, so we insert
    after them when present, otherwise after the module docstring.
    """
    tree = ast.parse(source)
    insert_line = 0  # 0-based index into lines
    for node in tree.body:
        is_docstring = (
            isinstance(node, ast.Expr)
            and isinstance(node.value, ast.Constant)
            and isinstance(node.value.value, str)
        )
        is_future = isinstance(node, ast.ImportFrom) and node.module == "__future__"
        if is_docstring or is_future:
            insert_line = node.end_lineno  # already 1-based -> next line, 0-based
        else:
            break

    lines = source.splitlines(keepends=True)
    lines.insert(insert_line, "import os\n")
    return "".join(lines)


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

    # Require exactly one occurrence. Zero means upstream moved it; more than
    # one means we would be rewriting something we have not looked at (and a
    # match inside a comment or docstring would leave the real call site
    # untouched while this script reported success).
    occurrences = content.count(HARDCODED_FLAG)
    if occurrences != 1:
        print(
            f"{SERVER_ADAPTER} contains {occurrences} occurrences of "
            f"{HARDCODED_FLAG!r}, expected exactly 1; update this script",
            file=sys.stderr,
        )
        return 1

    content = content.replace(HARDCODED_FLAG, ENV_FLAG)
    if not _binds_os(content):
        content = _insert_import_os(content)

    # Verify before writing. A previous version of this function produced a file
    # that did not compile (the inserted import landed above `from __future__`),
    # and the failure surfaced much later, inside the harness, as a traceback
    # pointing at upstream code rather than at this script.
    try:
        tree = ast.parse(content)
    except SyntaxError as err:
        print(f"patched {SERVER_ADAPTER} would not compile ({err}); not writing",
              file=sys.stderr)
        return 1

    # Syntax alone is not enough: a line-based insertion can land inside a
    # docstring, which parses fine and then fails at runtime with NameError.
    # Confirm `os` is actually bound at module level.
    binds_os = any(
        isinstance(node, ast.Import) and any(a.name == "os" for a in node.names)
        for node in ast.walk(tree)
    )
    if not binds_os:
        print(f"patched {SERVER_ADAPTER} does not import os (the insertion "
              f"probably landed inside a string); not writing", file=sys.stderr)
        return 1

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
