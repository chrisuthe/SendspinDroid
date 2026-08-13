# Sendspin dev server runbook

How to run a local Sendspin server that **refuses the legacy dialect**, so Phase 1
work is verified against a spec-compliant peer instead of Music Assistant's
compatibility shim.

Deliverable for audit item 0.2 (issue #190). See
`docs/spec-compliance-audit-2026-08-13.md`.

## Why this exists

Music Assistant ships `allow_legacy_clients=true`, which maps to aiosendspin's
`allow_unencrypted` / `allow_noncompliant_clients`. With that on, SendSpinDroid's
current `client/hello`-first dialect is accepted and every Phase 1 failure is
invisible. MA documents the toggle as temporary.

This server sets both flags to `False`. Against it, the current app **must fail to
connect** - that failure is the baseline Phase 1 has to turn green.

## One-time setup

Requires Python 3.12+.

```bash
python -m venv .venv
# Windows
.venv\Scripts\activate
# macOS/Linux
source .venv/bin/activate

pip install "aiosendspin[server]==9.1.0"
```

Pin 9.1.0: it is the version Music Assistant currently requires
(`music_assistant/providers/sendspin/manifest.json`). `aiosendspin.noise.*` is not
a stability-guaranteed API, so expect this script to need touching on
aiosendspin 10.

## Running

```bash
python ci/conformance/dev_server.py --name "Sendspin Dev" --trust-all-unpaired
```

Expected startup output (every line is prefixed with
`HH:MM:SS INFO    dev_server: `, elided here for width; on a first run a
"Generated a new server identity" line precedes it, and aiosendspin logs a
couple of its own lines):

```
========================================================================
Sendspin dev server 'Sendspin Dev' listening on 0.0.0.0:8927/sendspin
server_id: O67GqkUcDwDyoaIToq1vqI474fNR3sZp8CV1kul35W0
identity:  .dev\sendspin\identity.key (do NOT delete - see runbook)
records:   .dev\sendspin\pairing_store.json
allow_unencrypted=False  allow_noncompliant_clients=False
auto-trusting unpaired clients on connect (--trust-all-unpaired)
========================================================================
```

The `server_id` must be **identical** on every restart. If it changes, the
identity file was recreated and every paired client is now broken (see below).

### Console commands

The server reads commands on stdin while running:

| command | effect |
|---|---|
| `clients` | list connected clients with pairing and security state |
| `trust [id]` | make an unpaired (Sentinel-PSK) client playback-capable |
| `untrust [id]` | revoke that |
| `pair <token>` | pair using a `SP:0...` pairing token from the device (Phase 2) |
| `unpair [id]` | drop the record and send `server/unpair` (exercises item 2.7) |
| `quit` | stop |

`id` defaults to the only connected client when omitted.

### `--trust-all-unpaired` is not optional in practice

A Sentinel-keyed client completes the handshake but is **not playback-capable**
until the operator trusts it. In aiosendspin,
`SendspinConnection._playback_capable` requires
`client_info.unpaired_access.enabled and _trusted_unpaired`, and the latter comes
from `pairing_store.trusted_unpaired(client_id)`.

Forget this and you get a clean handshake followed by a `server/activate` with an
empty `active_roles` - which reads exactly like a bug in the client's
`server/activate` handling (item 1.6). It is the single easiest way to lose a day
on Phase 1. `--trust-all-unpaired` takes it off the table.

## Verifying the target is configured correctly

Run the automated checks:

```bash
python ci/conformance/verify_dev_server.py
```

which asserts, on a throwaway state directory:

- the persistent identity is stable across restarts, and `server_id` is 43 chars
- an empty identity file is refused rather than silently replaced
- a corrupt identity file is refused rather than silently replaced
- a strict server **closes** a legacy `client/hello`-first connection
- a **permissive** server accepts that same connection

The last check is the control that gives the one before it meaning. Without it,
a server that was simply broken would also "reject" the legacy client and this
script would report success for the wrong reason. It uses `dev_server`'s own
`build_server`, so it exercises the shipped configuration rather than a copy.

Expected output (aiosendspin also logs an "Accepting unencrypted legacy
connection (transition mode)" line during the control):

```
PASS identity is stable across restarts
PASS server_id is 43 base64url chars
     server_id: Q14IWqmBv7Me1wJAXmOqW-Ge9BXKK_6g6fJZotcERwA
PASS refuses to mint over an EMPTY identity file
PASS refuses to mint over a CORRUPT identity file
PASS strict server REJECTS the legacy client/hello
PASS control: permissive server ACCEPTS the same legacy client/hello

ALL CHECKS PASSED
```

The `server_id` differs on every run: the script uses a fresh temporary state
directory, so it never reuses the one your dev server prints.

Then point the current app build (2.0.0-Beta14) at the running server. It must
discover the server and then **fail to establish a session**, with the server
logging the `client/hello`-first frame being rejected. That failure is the
expected pre-Phase-1 baseline.

## Resetting state

| to reset | delete | consequence |
|---|---|---|
| all pairings | `.dev/sendspin/pairing_store.json` | clients must re-pair; safe |
| the server's identity | `.dev/sendspin/identity.key` | **destructive** |

Deleting `identity.key` changes `server_id`. Every stored-pubkey pairing record on
every paired device then matches on `psk_id` but fails the `server_id` check, and
the spec's failure handling for that is to close the WebSocket **with no
application-level error message** (`connection.md#failure-handling`). The symptom
is an unexplained disconnect loop with nothing useful in any log. The script
refuses to overwrite a corrupt identity file for this reason.

## Windows and WSL2

Run the server on the **Windows host**, not inside WSL2. WSL2 sits behind a NAT,
so neither mDNS advertising nor an inbound WebSocket from a phone reaches it.

If WSL2 is unavoidable:

```powershell
netsh interface portproxy add v4tov4 listenport=8927 listenaddress=0.0.0.0 `
  connectport=8927 connectaddress=<wsl-ip>
```

...and use the app's **Add Server Manually** flow with an explicit `host:8927`,
because discovery will not work. Android's `NsdManager` is also unreliable on some
OEM builds and on networks with client isolation, so keep manual entry in mind
regardless of WSL2.

## Debugging the Noise prologue

The prologue is the concatenation of `client/init` and `server/init`'s **exact
wire bytes** - the spec requires hashing what was sent and received, not a
re-serialization. `kotlinx.serialization` will not round-trip byte-identically,
so a mismatch here is the most likely silent failure in item 1.2.

```bash
python ci/conformance/dev_server.py --debug
```

raises aiosendspin and this script to DEBUG. **Be aware of what that does not
give you:** aiosendspin 9.1.0 does not log the raw init bytes at any level. It
builds the prologue in `aiosendspin/noise/driver.py` as
`client_init_text.encode() + server_init_text.encode()` with no log statement.
To diff our concatenation against the server's you have to capture the frames
yourself - a WebSocket proxy in front of the server, or a local one-line patch
adding a log call to that function. (An earlier version of this runbook claimed
`--dump-wire` surfaced these bytes. It did not; the flag has been renamed to
`--debug` to stop promising it.)

## Relationship to the conformance harness

The harness (`.github/workflows/conformance.yml`) constructs its own server via
`Sendspin/conformance`'s `aiosendspin_server.py` adapter, which hardcodes
`allow_unencrypted=True` and regenerates the identity per run. That is fine for
the legacy scenarios it runs today but cannot be the encrypted target.

`ci/conformance/register_sendspindroid.py` now rewrites that literal to read the
`CONFORMANCE_ALLOW_UNENCRYPTED` environment variable, defaulting to the existing
behaviour, so a future Phase 1 exit criterion can flip one variable in CI instead
of forking the harness. The patch fails loudly if the literal disappears upstream.
