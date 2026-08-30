# RESP2 support and documented deviations

Mnemo speaks RESP2 over TCP on port **6380**. The unmodified `redis-cli` is the compatibility
target: if it disagrees with us, we are wrong.

## Supported

| Feature | Notes |
|---|---|
| Multibulk requests (`*N` / `$N`) | The normal path every client uses |
| Inline commands (`PING\r\n`) | For raw telnet sessions. Also accepts a bare `LF` terminator |
| Pipelining | The decoder drains until the buffer is exhausted, and replies are flushed once per read |
| All five reply types | `+simple`, `-error`, `:integer`, `$bulk` (incl. `$-1` null and `$0` empty), `*array` |
| Binary-safe keys and values | Embedded `NUL`, `CR`, `LF`, and invalid UTF-8 all round-trip byte for byte |

## Deviations from Redis

| Area | Redis | Mnemo | Why |
|---|---|---|---|
| `proto-max-bulk-len` | 512 MB | **64 MB** | The accumulator must hold a whole bulk before it parses, so 512 MB would let one client dribbling an incomplete command pin half a gigabyte. Configurable via `RespLimits` |
| `COMMAND DOCS` | Full documentation structure | Empty array | The real reply is large and RESP3-shaped. An empty array is a valid answer and `redis-cli` falls back to its built-in help |
| `COMMAND` last-key / step | Accurate per command | Last-key equals first-key | We do not track key ranges. Only cluster-aware clients read these fields, and Mnemo is not a cluster |
| Databases | 16 by default | 1 | `SELECT 0` succeeds; any other index errors |
| RESP3 / `HELLO` | Supported | Not implemented | `redis-cli` negotiates RESP2 by default and degrades cleanly |
| Simple-string and error text | Escaped/truncated | CR and LF replaced with a space, truncated to 128 chars | See below |

## Protocol limits

Enforced per connection, and a violation closes it rather than replying.

| Limit | Default |
|---|---|
| Max bulk length | 64 MB |
| Max multibulk elements | 1,048,576 |
| Max inline / header line | 64 KB |
| Max buffered unparsed bytes | 64 MB + 64 KB |

Declared lengths are validated **before** any allocation, so `$999999999999` is refused rather
than attempted. Negative bulk lengths are rejected in requests — `$-1` is meaningful only in a
reply.

## Response-splitting guard

Error replies embed client-supplied text, e.g. `unknown command '<name>'`, and a RESP bulk string
may legally contain `CRLF`. Without sanitizing, a command named `A\r\nB` would frame as two
replies and desynchronize the connection.

The encoder therefore strips `CR`/`LF` from simple strings and errors and truncates them to 128
characters. It is done there, rather than at each call site, so no future error message can
reintroduce the hole.

## Not yet implemented

Transactions (`MULTI`/`EXEC`/`WATCH`), Pub/Sub, `CONFIG`, `CLIENT`, `SCAN`, and keyspace
notifications. Connection state is `ACTIVE → CLOSING` only; `IN_MULTI` and `SUBSCRIBED` arrive
with transactions and Pub/Sub.
