# Redis Clone (Java, no frameworks)

A Redis clone built from scratch in plain Java, to understand what's actually
happening underneath tools like Redis and frameworks like Spring —
networking, protocols, concurrency, persistence — instead of treating them
as magic.

## Plan

1. In-memory key-value store using a `HashMap`
2. TCP server so clients can connect and send commands like SET/GET
3. Implement the real Redis protocol (RESP) so the official `redis-cli` can connect
4. Handle many concurrent clients (threads, locks, concurrency)
5. Persistence: append-only log on disk, replayed on startup
6. Key expiration, then replication to a second server

## Progress

### Step 1 — In-memory store (`src/Store.java`) — done
`HashMap<String, String>` wrapped by `setValue`/`getValue`/`deleteValue`.
Missing key → `null`. Verified via `main`.

### Step 2 — TCP server (`src/Server.java`) — done (single client at a time)
`ServerSocket` on port 6379, `while(true)` around `.accept()`, one client
handled per connection via `BufferedReader`/`PrintWriter` over the socket
streams. Commands parsed with `text.split(" ")`, routed to `Store`. Replies
were plain text at this stage (`"OK"`/`"ERROR: ..."`/`"null"`) — superseded
by RESP in step 3. Known limitation at the time: only one client at a time,
and any unhandled exception would crash the whole server (no threading yet).

### Step 3 — RESP protocol (`src/Server.java`) — done, verified against real `redis-cli`
Switched wire format to RESP so real `redis-cli` can connect: Simple String
(`+OK\r\n`), Error (`-ERROR: ...\r\n`), Bulk String (`$<len>\r\n<content>\r\n`),
Nil (`$-1\r\n`). Incoming commands arrive as a RESP Array
(`*3\r\n$3\r\nSET\r\n...`) — parsed by reading the `*count` header line, then
looping `count` times reading a `$length` line + content line per element.

**Bugs found and fixed:**
- *Flush bug*: switching `pw.println` → `pw.print` (needed to avoid
  `println`'s extra newline corrupting exact RESP bytes) silently dropped
  every response — `PrintWriter.autoFlush` only triggers on
  `println`/`printf`/`format`, never on plain `print()`. Fixed with explicit
  `pw.flush()` after every `pw.print(...)`.
- *`COMMAND DOCS` deadlock*: real interactive `redis-cli` silently sends
  `COMMAND DOCS` on connect; the `if/else if` chain had no final `else`, so
  nothing was ever sent back and `redis-cli` hung forever waiting for a
  response. Fixed by adding `else { pw.print("-ERROR: Unknown command\r\n"); }`.
- *Homebrew/Rosetta*: `brew install redis` tried to build from source
  (LLVM/Rust included) because the only Homebrew present was the Intel-only
  install at `/usr/local`, running under Rosetta on this `arm64` Mac. Fixed
  by installing native Homebrew at `/opt/homebrew`.

Known limitation: content lines are read via `readLine()` (stops at `\r\n`),
not the exact `$<length>` byte count — would misread a value containing a
literal `\r`/`\n`. Not fixed yet.

Verified against real `redis-cli`: `SET`/`GET`/`DEL` all correct, including
nil on a missing key.

### Step 4 — Concurrency (`src/Server.java`) — done (each client on its own thread)
Per-client logic moved into `new Thread(() -> {...}).start()`, so `.accept()`
is never blocked by a slow/idle client. Thread body wrapped in
`try {...} catch (Exception e) {...} finally { client.close(); }` — broad
catch is deliberate here (failure isolation per client), `finally` guarantees
the socket is always released.

**Bugs found and fixed:**
- *Leaked socket*: an uncaught `NumberFormatException` (only `IOException`
  was caught) skipped `client.close()` entirely, leaving that client's
  connection open forever. Fixed by broadening to `catch (Exception e)` + `finally`.
- *Thread-unsafe map*: `Store`'s `HashMap` was shared across client threads
  with no synchronization — real risk of corruption/lost updates under
  concurrent writes. Fixed by swapping to `ConcurrentHashMap` (chosen over
  `synchronized` methods, which would serialize all access and defeat the
  point of threading).

Verified: a slow/open client no longer blocks a second client from getting
an immediate response.

### Step 5 — Persistence (`src/Store.java`) — done (append-only log)
`setValue`/`deleteValue` each append one line to `log.txt`
(`SET key value` / `DEL key`, human-readable, not RESP) via
`FileWriter(path, append=true)` in try-with-resources. `Store`'s constructor
replays `log.txt` on startup by calling `hashmap.put`/`hashmap.remove`
**directly** (not `setValue`/`deleteValue`, which would re-log and double
the file every restart). Logic lives in `Store`, not `Server`, since `Store`
is what knows when a real mutation happens.

**Bug found and fixed:** a value containing a space (`SET age "22 ar"`) came
back truncated to `"22"` after a restart — `line.split(" ")` produced 4
pieces and replay only read `parts[2]`. Fixed with `line.split(" ", 3)`,
capping the split so everything after the 2nd space stays together as one
piece — same underlying problem RESP's length-prefixing solves, resurfacing
in the simpler log format.

Verified: `SET name ola` → restart → `GET name` (no re-`SET`) → correctly `ola`.

Known limitations: log only ever grows (no compaction); no handling for a
corrupted/incomplete line (e.g. crash mid-write).

### Step 6a — Key expiration / TTL (`src/Store.java`, `src/Server.java`) — done
New `ValueEntry` wrapper (`value` + nullable `Long expiresAt`) replaces the
plain `String` in `Store`'s map (`ConcurrentHashMap<String, ValueEntry>`) —
keeps a key's related data together rather than a second parallel map.
`expiresAt` is boxed `Long` specifically so `null` can mean "never expires."
Expiration is lazy (check-on-read) only, no background sweep.

What changed:
- `getValue`: null entry → `null`; non-null but past `expiresAt` → calls
  `deleteValue(key)` (removes + logs a `DEL`) and returns `null`; otherwise
  → returns the value.
- `setValue` overloaded: `setValue(key, value)` (no expiry) and
  `setValue(key, value, ttlSeconds)` (`expiresAt = now + ttlSeconds * 1000`
  — ttl in seconds, `expiresAt` in ms, units must convert).
- New `setExpiry(key, ttlSeconds)` sets a TTL on an existing key without
  resupplying its value; no-ops if the key is missing.
- New `EXPIRE key seconds` command in `Server.java`, same shape as the
  others, replies `+OK\r\n`.

**Bugs found and fixed:**
- Two separate `NullPointerException` risks (missing `entry == null` checks
  in `getValue` and `setExpiry`) — same bug class each time, caught before shipping.
- Unit mismatch: first draft added `ttlSeconds` directly to
  `currentTimeMillis()` without `* 1000`, mixing seconds and milliseconds —
  keys would've expired almost instantly.
- String-concatenation bug in the log line: `"..." + currentTimeMillis() +
  ttlSeconds * 1000` looked like numeric addition but, once the expression
  was already a `String`, Java appended the second number's *text* instead
  of adding it — e.g. `...1697000000000` immediately followed by `2000`,
  glued together, not summed. Fixed by computing `expiresAt` once into a
  variable and reusing it for both the field and the log line.

Verified against real `redis-cli`: `SET foo bar` → `EXPIRE foo 2` → `GET foo`
still `bar` → after waiting past 2s, `GET foo` → nil.

### Step 6b — TTL persistence across restarts (`src/Store.java`) — done
Log line stores the **absolute** `expiresAt` timestamp (`EXPIRE foo
1697000005000`), not the relative ttl — logging the relative value would be
wrong on replay, since re-adding it to a *new* "now" (however long after
restart) would push the expiry further out or resurrect an already-expired
key.

Constructor's replay loop got an `EXPIRE` branch: looks up the key's
`ValueEntry`, sets `expiresAt = Long.parseLong(parts[2])` (`Long`, not `int`
— a ms timestamp overflows `int`). Missing key → `continue`, not `return`
(`return` would've aborted replaying the rest of the log file too).

Verified: `SET foo bar` → `EXPIRE foo 100` → kill + restart server → `GET foo`
still `bar` immediately after restart.

### Next up
- Replication to a second server (step 7).
- Maybe: fix content-line reading in `Server.java`'s RESP parsing to respect
  `$<length>` exactly, instead of relying on `readLine()`.
- Known gap: the log only ever grows, including `EXPIRE` lines — never compacted.

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
redis-cli -p 6379 EXPIRE name 60
redis-cli -p 6379 DEL name
```
Or connect with raw hand-crafted RESP bytes via `nc` (useful for inspecting
exact bytes with `xxd`, or testing malformed input):
```
printf '*3\r\n$3\r\nSET\r\n$4\r\nname\r\n$3\r\nola\r\n' | nc localhost 6379
```
