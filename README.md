# Redis Clone (Java, no frameworks)

A Redis clone built from scratch in plain Java, to understand what's actually
happening underneath tools like Redis and frameworks like Spring — networking,
protocols, concurrency, persistence — instead of treating them as magic.

## Plan

1. In-memory key-value store using a `HashMap`
2. TCP server so clients can connect and send commands like SET/GET
3. Implement the real Redis protocol (RESP) so the official `redis-cli` can connect
4. Handle many concurrent clients (threads, locks, concurrency)
5. Persistence: append-only log on disk, replayed on startup
6. Key expiration, then replication to a second server

## Progress

### Step 1 — In-memory store (`src/Store.java`) — done
- Wraps a `HashMap<String, String>`.
- `setValue(key, value)` / `getValue(key)` / `deleteValue(key)`.
- `getValue` on a missing key returns `null` (default `HashMap` behavior) —
  revisit this when implementing RESP in step 3, since real Redis returns a
  "nil" response over the wire, not a null/exception.
- Verified by running `main` directly: set two keys, read them back, delete
  one, confirm it reads back as `null` afterward.

### Step 2 — TCP server (`src/Server.java`) — connected to Store, done (single client at a time)

(Note: response formatting here has since been replaced by RESP — see Step 3
below. Kept for history; `"OK"`/`"ERROR: ..."`/`"null"` in this section
describe the pre-RESP behavior.)
- `ServerSocket` bound to port 6379 (Redis's default port).
- Outer `while (true)` loop around `.accept()` — after one client
  disconnects, the server goes back to waiting for the next one instead of
  exiting.
- `.accept()` blocks until a client connects, returns a `Socket` for that one
  connection.
- Reads lines of text from the client via
  `Socket.getInputStream()` → `InputStreamReader` → `BufferedReader.readLine()`.
- Sends a response back via
  `Socket.getOutputStream()` → `PrintWriter` (autoFlush `true`).
- Inner loop per client: `while ((text = reader.readLine()) != null)` — keeps
  reading/responding to lines from the same client until it disconnects
  (`readLine()` returns `null` when the connection closes).
- `client.close()` called right after the inner loop ends, releasing that
  connection before looping back to `.accept()` for the next client.
- `Store` is instantiated **once**, outside the `while(true)` loop, so data
  persists across different client connections (not a fresh `HashMap` per
  client).
- Each line read is parsed with `text.split(" ")` into a `String[]`, e.g.
  `"SET name ola"` → `["SET", "name", "ola"]`. `parts[0]` is matched against
  `"SET"` / `"GET"` / `"DEL"` (using `.equals()`, not `==`) and routed to the
  matching `Store` method:
  - `SET key value` → `store.setValue(key, value)`, replies `"OK"`.
  - `GET key` → replies with `store.getValue(key)` directly (so the value
    itself is sent back, not just `"OK"`).
  - `DEL key` → `store.deleteValue(key)`, replies `"OK"`.
- Validates `parts.length` before indexing into it, per command (`SET` needs
  ≥3, `GET`/`DEL` need ≥2) — replies an `"ERROR: ..."` message instead of
  proceeding when too few arguments are given.
- Verified manually end-to-end: `SET name ola` → `OK`, `GET name` → `ola`,
  `DEL name` → `OK`, `GET name` again → `null` (correctly gone). Also
  verified malformed input no longer crashes the server: sending bare `GET`
  or `SET onlykey` returns an error message and the server stays alive and
  keeps handling subsequent valid commands in the same connection.

Known limitations (expected at this stage, not bugs):
- Only handles one client **at a time** — `.accept()` is only called again
  after the previous client's loop has fully ended, so a second client can't
  connect while the first is still active. Fixing this (true concurrency,
  many clients at once) is step 4.
- `GET` on a missing key replies the literal text `"null"` — fine for a raw
  `nc` test, but not real Redis wire format. Revisit once RESP (step 3) is
  implemented.
- Any exception *not* already guarded against (only the "too few arguments"
  case is handled) would still crash the whole server, since there's no
  threading/isolation yet and no outer `try/catch` safety net.

### Step 3 — RESP protocol (`src/Server.java`) — done, verified against real `redis-cli`
RESP (REdis Serialization Protocol) is the real wire format Redis uses, so
`redis-cli` (and anything else actually speaking Redis) can talk to the
server. Every value is sent with an explicit type marker and, where needed,
an explicit length — so the receiver never has to guess where one value ends
and the next begins (unlike plain text split on spaces, which breaks the
moment a value itself contains a space).

Types used so far:
- Simple String: `+OK\r\n`
- Error: `-ERROR: ...\r\n`
- Bulk String: `$<length>\r\n<content>\r\n`, e.g. `$3\r\nola\r\n`
- Nil (missing value): `$-1\r\n` — no content section at all, since length
  `-1` means "don't look for content."
- (Not yet used: Integer `:1000\r\n`, Array `*<count>\r\n...`.)

What changed in `Server.java`:
- All six response paths (`SET`/`GET`/`DEL`, success and error cases) now
  send properly formatted RESP bytes via `pw.print(...)`, built as plain
  Java string concatenation (e.g. `"$" + value.length() + "\r\n" + value +
  "\r\n"`).
- `GET` on an existing key replies a Bulk String; on a missing key, replies
  the nil Bulk String (`$-1\r\n`) instead of crashing or sending literal
  `"null"`.
- `SET`/`DEL` success replies `+OK\r\n` (Simple String); all three commands'
  validation-failure replies now use `-ERROR: ...\r\n` (Error type).
- Verified with raw byte inspection (`xxd`) over a real `nc` connection
  covering `SET`, `GET` (hit and miss), `DEL`, and a malformed bare `GET` —
  confirmed byte-for-byte correct RESP output for every case, and confirmed
  the server survives the malformed command.

**Bug found and fixed along the way — buffering/flushing:** switching
responses from `pw.println(...)` to `pw.print(...)` (needed to avoid
`println`'s automatic extra newline corrupting the exact RESP bytes) silently
broke every response — `PrintWriter`'s `autoFlush` only triggers on
`println`/`printf`/`format`, **not** on plain `print()`, regardless of
whether the string itself contains `\n`. Every `pw.print(...)` call was
sitting in an internal buffer that was discarded in full the moment
`client.close()` ran, meaning the client received **zero bytes** for a while
before this was caught by inspecting raw bytes with `xxd` (output just
showed nothing). Fixed by adding an explicit `pw.flush();` after every
`pw.print(...)` call.

**Incoming RESP parsing (Arrays of Bulk Strings):** real `redis-cli` doesn't
send plain text — it sends commands as a RESP Array, e.g.
`SET name ola` arrives as `*3\r\n$3\r\nSET\r\n$4\r\nname\r\n$3\r\nola\r\n`.
Since `BufferedReader.readLine()` already stops at every `\r\n`, no raw-byte
reading was needed — just reading the *right sequence* of lines:
1. Read one line for the array header (`*3`), strip the `*`
   (`String.substring(1)`), parse the rest as an `int` (`Integer.parseInt`)
   to get the element count.
2. Loop that many times; each iteration reads **two** lines — a `$<length>`
   line (currently unused/discarded) and the actual content line — storing
   just the content into a `String[] parts`, the same shape `text.split(" ")`
   used to produce. The rest of the existing `if (parts[0].equals("SET"))`
   chain needed no changes at all.

Key mental model that took some back-and-forth to land: `reader.readLine()`
always advances to the *next* unread line in the stream, never re-reads the
same spot — so two consecutive calls in one loop iteration naturally pull
two different, consecutive lines (the length line, then the content line).

**Verified against the real, official `redis-cli`** (not just hand-crafted
bytes via `nc`) — `SET name ola` → `OK`, `GET name` → `ola`, `DEL name` →
`OK`, `GET name` again → empty/nil. This is a stronger proof of correctness
than any self-written test, since `redis-cli` is an independent, real-world
RESP implementation with zero knowledge of this codebase.

**Known limitation:** content lines are still read with `readLine()`
(stopping at the next `\r\n`), not by reading the exact `$<length>` byte
count. A value containing an embedded `\r` or `\n` (legitimate in real RESP,
e.g. binary data) would be read incorrectly / cut short. Not fixed yet —
noted for later.

Integer RESP type (`:1000\r\n`) isn't implemented yet (not needed by any
current command).

**Side quest — Homebrew/Rosetta fix:** installing `redis` (for `redis-cli`)
via the existing Homebrew first attempted to build `redis` **and its
dependencies, including LLVM and Rust, entirely from source** — a
multi-hour, many-GB undertaking. Cause: this Mac is Apple Silicon (`arm64`),
but the only Homebrew install present lived at `/usr/local` — the
Intel-only location — likely installed a while ago and running under
Rosetta, so it identified as "Intel x86_64" and found no prebuilt binaries
for that (now-dropped) platform/arch combination. Fixed by installing a
second, native Homebrew at `/opt/homebrew` (the correct location for Apple
Silicon) via the official installer script — `redis` then installed in
seconds from a prebuilt `arm64` bottle. Worth remembering if other brew
installs on this machine seem to unexpectedly want to compile from source.

**Bug found and fixed — `COMMAND DOCS` deadlock in real interactive
`redis-cli`:** one-shot commands (`redis-cli -p 6379 SET ...`) and piped
input both worked, but a genuine interactive session (just `redis-cli -p
6379`, typed into live) hung completely — no response, no server output at
all. Root cause, found by capturing the raw bytes `redis-cli` sends on
connect (via `nc` as a dummy listener): real interactive `redis-cli`
silently sends `COMMAND DOCS` (`*2\r\n$7\r\nCOMMAND\r\n$4\r\nDOCS\r\n`)
immediately after connecting, to fetch command metadata for its own
autocomplete/hints. The server's array parsing handled this fine
structurally (`parts = ["COMMAND", "DOCS"]`), but the `if`/`else if` chain
only matched `SET`/`GET`/`DEL` and had no final `else` — so nothing was ever
sent back. Meanwhile `redis-cli` was blocked waiting for *any* response to
`COMMAND DOCS` before proceeding. Both sides waited on each other forever —
a deadlock, not a crash, which is why nothing printed anywhere. Fixed by
adding a final `else` branch that replies `-ERROR: Unknown command\r\n` for
any unrecognized command — a response (even an error) is enough to unblock
`redis-cli` and let it proceed to accept real typed commands.

### Step 4 — Concurrency (`src/Server.java`) — done (each client on its own thread)
Previously, `.accept()` was only called again *after* the current client's
entire read/respond loop had finished — meaning a second client couldn't
connect at all while the first was still active, no matter how slow or idle
it was.

What changed:
- The per-client logic (everything from `getInputStream()` down through
  `client.close()`) is now wrapped in `new Thread(() -> { ... }).start();`,
  handed off immediately after `.accept()` returns. The main loop's only job
  per iteration is now: accept a connection, launch a thread for it, go
  straight back to `.accept()` for the next one — never blocked by any one
  client.
- The thread body is wrapped in `try { ... } catch (Exception e) { ... }
  finally { client.close(); }` — a single broad `catch (Exception e)`,
  deliberately, since this is a failure-isolation boundary: *any* unexpected
  problem from one client (malformed input, a bug, anything) should be
  contained to that one thread, never crash the server or other clients.
  The `finally` guarantees `client.close()` always runs — even if something
  above it threw — releasing the socket (and its OS-level file descriptor)
  so the server doesn't leak resources over time as clients come and go.
- Verified concurrency genuinely works: connected a deliberately
  "slow"/open client first, then a second client while the first was still
  connected — the second client got its response immediately, proving it
  wasn't stuck waiting behind the first.

**Bug found and fixed along the way — uncaught exception leaking a
socket:** while testing, a malformed client command crashed its thread with
an uncaught `NumberFormatException` (only `IOException` was caught at the
time) — the thread died silently, but `client.close()` never ran, since it
sat after the exception point with no `finally`. The symptom: the crashed
client's `nc` process just hung forever, since the server never told its
OS to tear down that TCP connection. This is exactly why the `finally`
block exists now, and why `catch (Exception e)` (broad, but intentional at
this specific boundary) replaced separate `catch (IOException)` /
`catch (NumberFormatException)` blocks — any future exception type is
covered the same way, without needing to anticipate and add a new `catch`
for each one.

**What `client.close()` actually does**, worth remembering: it tells the OS
to close the underlying TCP connection and release the file descriptor tied
to that socket. Every open socket holds onto a limited OS resource; skipping
`close()` means that resource stays reserved forever even after the
client's work is done — eventually exhausting the limit and causing new
connections to start failing. (Analogy: `accept()` picks up a phone line for
one call; `close()` actually hangs it up, freeing that line for the next
caller. Skipping it leaves the line permanently tied up.)

**Known limitation (not yet addressed):** `Store`'s `HashMap` is now shared
across multiple threads calling `setValue`/`getValue`/`deleteValue`
concurrently, with no synchronization at all. `HashMap` is **not**
thread-safe — concurrent writes from different threads can corrupt its
internal structure or produce inconsistent reads. This hasn't caused a
visible problem yet (simple test connections, low real concurrency), but is
a real, latent bug. Next thing to address before trusting this under real
concurrent load.

### Next up
- Make `Store` thread-safe (e.g. `synchronized` methods, or a concurrent map
  like `ConcurrentHashMap`) — currently unsafe under real concurrent access.
- Maybe: fix content-line reading to respect `$<length>` exactly, instead of
  relying on `readLine()`.

## Running it manually

Compile (both files, since `Server` depends on `Store`):
```
javac -d out src/Server.java src/Store.java
```

Run the server:
```
java -cp out Server
```

In another terminal, act as a client with the real `redis-cli`:
```
redis-cli -p 6379 SET name ola
redis-cli -p 6379 GET name
redis-cli -p 6379 DEL name
```
Or connect with raw hand-crafted RESP bytes via `nc` (useful for inspecting
exact bytes with `xxd`, or testing malformed input):
```
printf '*3\r\n$3\r\nSET\r\n$4\r\nname\r\n$3\r\nola\r\n' | nc localhost 6379
```
