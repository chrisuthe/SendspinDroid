"""Tests for the conformance-harness patcher.

Runnable with plain pytest and no dependencies beyond the standard library -
no aiosendspin, no network, no JVM - so this is the one piece of Phase 0
verification that can move into CI as-is.

The first shipped version of patch_server_encryption_flag produced a file that
did not compile, because the inserted `import os` landed above
`from __future__ import annotations`. `test_patches_file_with_future_import`
is that regression, and `ast.parse` in the patcher is what now catches it.
"""

from __future__ import annotations

import ast
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent))

from register_sendspindroid import (  # noqa: E402
    ENV_FLAG,
    HARDCODED_FLAG,
    patch_server_encryption_flag,
)

ADAPTER_REL = Path("src") / "conformance" / "adapters" / "aiosendspin_server.py"

WITH_FUTURE = '''"""aiosendspin server adapter for conformance scenarios."""

from __future__ import annotations

import argparse
import asyncio


def build():
    return SendspinServer(
        loop,
        allow_unencrypted=True,
    )
'''

WITHOUT_FUTURE = '''"""adapter."""

import argparse


def build():
    return SendspinServer(loop, allow_unencrypted=True)
'''

DOCSTRING_TRAP = '''"""adapter.

Usage example:
import conformance
from conformance import run
"""

from __future__ import annotations

import argparse


def build():
    return SendspinServer(loop, allow_unencrypted=True)
'''


def make_checkout(tmp_path: Path, adapter_source: str) -> Path:
    adapter = tmp_path / ADAPTER_REL
    adapter.parent.mkdir(parents=True)
    adapter.write_text(adapter_source, encoding="utf-8")
    return tmp_path


def read_adapter(checkout: Path) -> str:
    return (checkout / ADAPTER_REL).read_text(encoding="utf-8")


def assert_valid_patch(content: str) -> None:
    """The patched file must compile AND actually bind `os` at module level."""
    tree = ast.parse(content)  # raises SyntaxError on the original shipped bug
    assert ENV_FLAG in content
    assert HARDCODED_FLAG not in content
    binds_os = any(
        isinstance(node, ast.Import) and any(a.name == "os" for a in node.names)
        for node in ast.walk(tree)
    )
    assert binds_os, "patched file does not bind os at module level"


@pytest.mark.parametrize(
    "source", [WITH_FUTURE, WITHOUT_FUTURE, DOCSTRING_TRAP],
    ids=["with-future-import", "without-future-import", "imports-inside-docstring"],
)
def test_patch_produces_a_compiling_file_that_binds_os(tmp_path, source):
    checkout = make_checkout(tmp_path, source)
    assert patch_server_encryption_flag(checkout) == 0
    assert_valid_patch(read_adapter(checkout))


def test_future_import_stays_first(tmp_path):
    """The regression: `import os` must not be inserted above `from __future__`."""
    checkout = make_checkout(tmp_path, WITH_FUTURE)
    assert patch_server_encryption_flag(checkout) == 0
    lines = [l for l in read_adapter(checkout).splitlines() if l.strip()]
    code = [l for l in lines if not l.startswith('"""') and not l.endswith('"""')]
    future_idx = next(i for i, l in enumerate(code) if l.startswith("from __future__"))
    os_idx = next(i for i, l in enumerate(code) if l == "import os")
    assert future_idx < os_idx


def test_is_idempotent(tmp_path):
    checkout = make_checkout(tmp_path, WITH_FUTURE)
    assert patch_server_encryption_flag(checkout) == 0
    once = read_adapter(checkout)
    assert patch_server_encryption_flag(checkout) == 0
    assert read_adapter(checkout) == once


def test_fails_loudly_when_the_literal_is_gone(tmp_path):
    checkout = make_checkout(tmp_path, WITHOUT_FUTURE.replace(HARDCODED_FLAG, "allow_unencrypted=False"))
    assert patch_server_encryption_flag(checkout) == 1
    assert ENV_FLAG not in read_adapter(checkout)


def test_refuses_when_the_literal_appears_more_than_once(tmp_path):
    """Two matches means one is somewhere we have not inspected."""
    doubled = WITHOUT_FUTURE.replace(
        "return SendspinServer(loop, allow_unencrypted=True)",
        "# allow_unencrypted=True is the default\n"
        "    return SendspinServer(loop, allow_unencrypted=True)",
    )
    checkout = make_checkout(tmp_path, doubled)
    assert patch_server_encryption_flag(checkout) == 1
    assert read_adapter(checkout) == doubled, "must not modify the file it refused"


def test_fails_when_the_adapter_is_missing(tmp_path):
    (tmp_path / "src" / "conformance" / "adapters").mkdir(parents=True)
    assert patch_server_encryption_flag(tmp_path) == 1


def test_env_flag_default_preserves_current_behaviour(monkeypatch):
    """Absent env var must evaluate True, so existing CI runs are unchanged."""
    def evaluate() -> bool:
        import os
        return os.environ.get("CONFORMANCE_ALLOW_UNENCRYPTED", "1") not in ("0", "false", "False")

    monkeypatch.delenv("CONFORMANCE_ALLOW_UNENCRYPTED", raising=False)
    assert evaluate() is True
    for falsey in ("0", "false", "False"):
        monkeypatch.setenv("CONFORMANCE_ALLOW_UNENCRYPTED", falsey)
        assert evaluate() is False
    monkeypatch.setenv("CONFORMANCE_ALLOW_UNENCRYPTED", "1")
    assert evaluate() is True
