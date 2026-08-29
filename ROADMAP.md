# Building Redis in Java

**Implementation plan · Java 21 · Spring Boot**

A staged engineering plan for a hand-written, concurrent, RESP-compatible in-memory data store — where every phase is chosen for the interview conversation it earns you.

| | |
|---|---|
| **Target** | SDE-1 / SDE-2 portfolio |
| **Milestones** | M0–M10 |
| **MVP line** | after M6 |
| **Estimate** | 10–14 weeks part-time |

---

## Table of contents

- [00 — The premise](#00--the-premise)
- [01 — Architecture](#01--architecture)
- [P1 — Core key-value store](#p1--core-key-value-store)
- [P2 — Command surface](#p2--command-surface)
- [P3 — TTL & expiration](#p3--ttl--expiration)
- [P4 — Eviction](#p4--eviction)
- [P5 — Concurrency](#p5--concurrency)
- [P6 — Networking](#p6--networking)
- [P7 — Persistence](#p7--persistence)
- [P8 — Advanced features](#p8--advanced-features)
- [02 — Stack decisions](#02--stack-decisions)
- [03 — Data structures & algorithms](#03--data-structures--algorithms)
- [04 — Testing strategy](#04--testing-strategy)
- [05 — Benchmarking](#05--benchmarking)
- [06 — Docker & deployment](#06--docker--deployment)
- [07 — Repository presentation](#07--repository-presentation)
- [08 — Resume assets](#08--resume-assets)
- [09 — Roadmap (milestones)](#09--roadmap-milestones)

---

## 00 — The premise

What separates this from the thousand other "Redis clone" repos on GitHub, and the one rule that protects that difference.

Most portfolio projects fail an interview at the same moment: the interviewer asks "what was hard about it?" and there is no answer, because the framework did the hard parts. This plan is organised so that every phase leaves you with a *specific, defensible engineering decision* you made and can argue both sides of.

The single most important structural rule is this:

> **Design decision.** The database engine has zero Spring dependencies. The core module compiles and runs with nothing but the JDK. Spring Boot sits strictly outside it — wiring, configuration binding, an admin REST surface, Actuator metrics, and lifecycle management.
>
> This is enforceable and therefore provable: a Gradle dependency rule (or an ArchUnit test) that fails the build if `org.springframework` ever appears on the core module's compile classpath. That test is itself a talking point.

Why it matters beyond aesthetics: a framework-free core is unit-testable without an application context (milliseconds, not seconds), benchmarkable under JMH without a container warm-up distorting results, and demonstrates that you understand dependency direction rather than just annotation syntax. It is the difference between "I used Spring Boot" and "I decided where Spring Boot belonged."

### The demo that ends the argument

There is one deliverable that instantly establishes credibility, and you should treat it as the project's north star from Milestone 2 onward:

```
$ redis-cli -p 6380
127.0.0.1:6380> SET user:1 "Ada"
OK
127.0.0.1:6380> ZADD board 1500 alice
(integer) 1
127.0.0.1:6380> INFO server
# Server
mnemo_version:0.6.0
```

The *real* `redis-cli`, unmodified, talking to *your* server. Not a custom client you also wrote, which proves nothing except that your encoder and decoder agree with each other. Wire-protocol compatibility with an external tool you don't control is an objective, falsifiable claim — and it means `redis-benchmark` and `memtier_benchmark` also work against you for free, which is where your performance numbers will come from.

### Naming

Call it something. `redis-clone-java` reads as an exercise; a name reads as a project. **Mnemo** (from Mnemosyne, memory) is used throughout this document — it survives the "why that name?" small-talk question at the top of an interview. *Velo* and *Tessera* are fine alternatives. Whatever you pick, use it consistently in the package name (`dev.yourname.mnemo`), the artifact id, and the `INFO` output.

### What you are explicitly not building

- **Cluster mode with gossip.** Enormous, and the payoff overlaps almost entirely with single-primary replication, which is a tenth of the work.
- **Lua scripting.** Embedding an interpreter demonstrates integration skill, not systems skill.
- **Every Redis command.** 240+ commands is data entry. Roughly 60, chosen for the concepts they force you to solve, is engineering.
- **A web UI.** It adds surface area and subtracts focus. A Grafana dashboard from your real metrics is strictly better and nearly free.

---

## 01 — Architecture

Modules, dependency direction, the class-level skeleton, and the exact path a `SET` takes from socket to acknowledgement.

### Module layout

Five Gradle modules. The split is not decoration — it is what makes the "framework-independent core" claim checkable by a build rule rather than a promise in the README.

```
                    ┌────────────────────────────┐
                    │  Spring Boot boundary       │
                    │  nothing below this line    │
                    │  knows Spring exists        │
                    │                              │
                    │   ┌──────────────┐           │
                    │   │  mnemo-app   │           │
                    │   │  wiring ·    │           │
                    │   │  config ·    │           │
                    │   │  actuator ·  │           │
                    │   │  admin REST  │           │
                    │   └──────┬───────┘           │
                    └──────────┼────────────────────┘
                               │ constructs + owns lifecycle
                               ▼
                       ┌───────────────┐        ┌────────────────────┐
                       │ mnemo-server  │        │  mnemo-benchmarks   │
                       │ netty ·       │◀ · · · │  JMH · load gen     │
                       │ replication · │        │  (depends on core   │
                       │ connections   │        │   directly)         │
                       └───┬───────┬───┘        └──────────┬──────────┘
                           │       │                        │
              ┌────────────┘       └────────────┐           │
              ▼                                  ▼           │
    ┌──────────────────┐              ┌───────────────────┐  │
    │ mnemo-protocol    │              │ mnemo-persistence │  │
    │ RESP2 codec       │              │ snapshot · AOF    │  │
    └─────────┬─────────┘              └──────────┬─────────┘  │
              │                                    │            │
              └──────────────┐      ┌──────────────┘            │
                              ▼      ▼                           │
                        ┌─────────────────┐                      │
                        │   mnemo-core     │◀─────────────────────┘
                        │  engine · types ·│   JDK only.
                        │  expiry · evict  │   Zero third-party
                        └─────────────────┘   compile deps.
```

Dependency arrows point only downward and inward. `mnemo-core` is a leaf: it can be compiled, tested, and benchmarked with no framework, no network, and no filesystem. Benchmarks depend on core directly so JMH measures the engine, not the server.

### Package structure

```
dev.yourname.mnemo.core
├── store/
│   ├── Keyspace.java              // facade over the shards
│   ├── Shard.java                 // one partition: map + its own clock
│   ├── ValueEntry.java            // value + expireAt + lru/lfu meta + version
│   └── KeyspaceStats.java
├── type/
│   ├── RedisValue.java            // sealed interface
│   ├── StringValue.java           // byte[]-backed, binary safe
│   ├── ListValue.java             // ArrayDeque-backed
│   ├── HashValue.java             // listpack → HashMap promotion
│   ├── SetValue.java              // intset → HashSet promotion
│   ├── ZSetValue.java             // skiplist + HashMap
│   └── skiplist/SkipList.java     // hand-written, span-augmented
├── command/
│   ├── Command.java               // interface: name, arity, flags, execute
│   ├── CommandRegistry.java
│   ├── CommandContext.java        // args, connection state, keyspace handle
│   ├── Reply.java                 // sealed: Simple, Error, Integer, Bulk, Array, Null
│   └── impl/                      // one class per command family
├── expire/
│   ├── ExpirationManager.java     // strategy interface
│   ├── SamplingExpirer.java
│   └── TimingWheelExpirer.java
├── evict/
│   ├── EvictionPolicy.java        // strategy interface
│   ├── SampledLruPolicy.java
│   ├── SampledLfuPolicy.java
│   └── MemoryAccountant.java
└── config/EngineConfig.java       // plain record — Spring binds INTO this
```

### Core abstractions

The command contract everything hangs off:

```java
public interface Command {
    String name();
    Arity arity();                    // exact or minimum arg count
    Set<Flag> flags();                // WRITE, READONLY, ADMIN, DENYOOM, BLOCKING
    int firstKey();                   // for shard routing + WATCH tracking
    Reply execute(CommandContext ctx);
}

public sealed interface Reply {
    record Simple(String s)      implements Reply {}   // +OK
    record Err(String code, String msg) implements Reply {}   // -WRONGTYPE ...
    record Int(long v)           implements Reply {}   // :42
    record Bulk(byte[] payload)  implements Reply {}   // $3\r\nfoo\r\n
    record Arr(List<Reply> items) implements Reply {}  // *2\r\n...
    record Nil()                 implements Reply {}   // $-1
}
```

Two things to notice. `Reply` is a **sealed interface of records**, which means the RESP encoder is an exhaustive `switch` pattern match with no default branch — add a reply type and the compiler tells you every place that must handle it. And `firstKey()` exists because two separate subsystems need to know which key a command touches: the shard router, and `WATCH`.

> **Interview note.** `DENYOOM` is the flag that separates people who read the Redis source from people who guessed. It marks commands that *increase* memory (`SET`, `LPUSH`) and must therefore be rejected when memory is exhausted and the policy is `noeviction` — while `GET` and `DEL` continue to work. Getting this right means a full instance is still readable and recoverable rather than a brick.

### Design patterns actually in use

| Pattern | Where | What it buys |
|---|---|---|
| **Command** | `Command` + `CommandRegistry` | Adding a command is one class and one registration; dispatch, arity checking, and metrics are written once |
| **Strategy** | Eviction, expiration, persistence, fsync policy | Swappable at runtime by config — and it is precisely what makes the comparative benchmarks possible |
| **Facade** | `Keyspace` over `Shard[]` | Callers never compute a shard index; the sharding scheme can change without touching commands |
| **Reactor** | Netty event loop | The core networking model — know it by name |
| **Decorator** | `MeteredKeyspace implements Keyspace` | Micrometer timers wrap the engine without a single metrics import in the engine |
| **State** | `ConnectionState` | NORMAL → IN_MULTI → SUBSCRIBED → REPLICA each accept a different command set; explicit states beat scattered booleans |
| **Observer** | Pub/Sub, keyspace notifications | One dispatch mechanism serves both features |

### Data flow: a single SET

```
socket ──bytes──▶ event loop ──▶ RESP decode ──0..n cmds──▶ router ──hash(key)&mask──▶ shard executor
 (TCP)            (1 of N          (incremental)                     (thread             (single writer,
                    threads)                                          handoff here)        map.put + accounting)
                                                                                                   │
                                                            ┌──────────────────────────────────────┼───────────────────┐
                                                            ▼                                      ▼                   │
                                                     AOF buffer                            repl backlog                │
                                                     (group fsync)                          (ring buffer + offset)     │
                                                            │                                                          │
                                                            └───── on success, before reply released ──────────────────┘
                                                                                     │
                                                                                     ▼
                                                                              Reply.Simple("OK")
                                                                                     │
                                                                                     ▼
                                                                    RESP encode (back on event loop) ──▶ socket
```

The only thread handoff is at the router. Everything left of it runs on a Netty event-loop thread; the shard executor owns its map exclusively, so no lock is taken inside it. AOF and replication are fed from inside the shard, which is what makes their ordering match execution ordering.

Two ordering guarantees fall out of that diagram, and both are worth stating explicitly in your README:

- **The AOF write happens before the reply is released to the client.** Otherwise a client can observe an acknowledged write that a crash then loses even under `appendfsync always` — the classic durability hole.
- **Replication is fed from inside the shard, not from the connection handler.** If you propagate from the handler, two commands on the same key can reach replicas in the opposite order from which they were applied locally, and the replica silently diverges.

---

## P1 — Core key-value store

**M1**

The engine, the value model, and the command abstraction that every later phase plugs into.

Start single-threaded and single-shard. Not because concurrency is hard to add later — because the shape of the value model is what determines whether it *can* be added later, and you want to get that shape right while the code is small.

### The value model

`ValueEntry` — the most consequential 6 fields in the project:

```java
final class ValueEntry {
    RedisValue value;        // sealed type: String/List/Hash/Set/ZSet
    long        expireAtMs;  // absolute wall-clock deadline; 0 = no TTL
    int         lastAccess;  // coarse seconds-resolution clock, for LRU
    byte        lfuCounter;  // 8-bit probabilistic counter, for LFU
    long        version;     // bumped on every mutation
    int         sizeBytes;   // cached size estimate for memory accounting
}
```

> **Design decision.** TTL lives inside the entry, not in a parallel `Map<String, Long>`. Every read has to check expiry anyway, so an inline field makes that check free — one hash lookup instead of two, one cache line instead of two, and no possibility of the two maps disagreeing after a crash, an eviction, or a concurrent delete.
>
> Redis itself keeps a separate `expires` dict. It does that because it needs to iterate *only* the keys that have TTLs during active expiry, and the fraction of keys with TTLs is often small. You get that same iteration ability from the timing wheel in Phase 3 without paying for a second dictionary — which is a legitimate improvement on the original design, and exactly the kind of thing worth saying out loud in an interview.

The `version` field looks unnecessary now. It is load-bearing three times later: the timing wheel uses it to detect stale expiry entries, `WATCH` uses it to detect concurrent modification, and the snapshot writer uses it to detect entries mutated mid-scan. Add it now; retrofitting it means touching every mutation site.

### String values are byte arrays, not Strings

Redis keys and values are **binary safe** — arbitrary bytes, including embedded nulls and invalid UTF-8. If you type your store as `Map<String, String>` you will pass your own tests and fail against `redis-cli` the first time someone stores a serialized protobuf or a JPEG.

Use `byte[]` at the storage layer with a small wrapper providing value-based `equals`/`hashCode` (raw arrays use identity semantics and will silently break every map lookup). Decode to `String` only at the edges — command names, integer parsing, log messages.

> **Tradeoff.** Byte-array keys cost you readability in the debugger and force a wrapper allocation per key. You gain actual protocol correctness and roughly half the memory of Java's UTF-16 `String` for ASCII keys. Take the correctness; note the memory win in your benchmark writeup.

### Deliverables for this phase

- `Keyspace` with `get` / `set` / `delete` / `exists` / `type` / `size`
- `Command`, `CommandRegistry`, `CommandContext`, sealed `Reply`
- `StringValue` and roughly ten string commands
- A REPL-less `main` that reads stdin, dispatches, prints replies — enough to demo before any networking exists

---

## P2 — Command surface

**M1 · M4**

Roughly sixty commands, selected because each one forces you to solve something.

The selection principle: implement a command if it introduces a data structure, a concurrency problem, or a protocol subtlety. Skip it if it is a variation on one you already have. `GETRANGE` after `GET` teaches nothing; `SCAN` after `KEYS` teaches cursor stability under concurrent mutation.

| Family | Commands | What it forces you to solve |
|---|---|---|
| **String** | `SET` (NX/XX/EX/PX/KEEPTTL/GET) `GET` `GETDEL` `APPEND` `STRLEN` `INCR` `INCRBY` `INCRBYFLOAT` `DECR` `MSET` `MGET` `SETNX` | Option parsing as a real state machine; atomic read-modify-write; multi-key routing; integer overflow returning an error rather than wrapping |
| **Generic** | `DEL` `EXISTS` `TYPE` `KEYS` `SCAN` `RANDOMKEY` `DBSIZE` `FLUSHDB` `RENAME` `TTL` `PTTL` `EXPIRE` `PEXPIRE` `EXPIREAT` `PERSIST` | Cursor semantics; glob matching; the `WRONGTYPE` discipline; TTL's three-valued return (`-2` missing, `-1` no TTL, `n` seconds) |
| **Hash** | `HSET` `HGET` `HDEL` `HGETALL` `HEXISTS` `HLEN` `HKEYS` `HVALS` `HINCRBY` | Nested containers; the compact-to-hashtable encoding promotion |
| **List** | `LPUSH` `RPUSH` `LPOP` `RPOP` `LRANGE` `LLEN` `LREM` `LINDEX` `BLPOP` | Negative index normalisation; and `BLPOP`, which is the first command that cannot simply return — it must park a client and be woken by another client's write |
| **Set** | `SADD` `SREM` `SMEMBERS` `SISMEMBER` `SCARD` `SPOP` `SINTER` `SUNION` `SDIFF` | Set algebra with small-set-first intersection ordering; integer-set encoding |
| **Sorted set** | `ZADD` `ZSCORE` `ZINCRBY` `ZCARD` `ZRANK` `ZREVRANK` `ZRANGE` `ZREVRANGE` `ZRANGEBYSCORE` `ZREM` | **The skiplist.** The single highest-value DSA component in the project — see Phase 3 of the DSA reference |
| **Server** | `PING` `ECHO` `INFO` `CONFIG GET` `CONFIG SET` `COMMAND` `CLIENT LIST` `SELECT` | `COMMAND` is not optional — `redis-cli` calls it on connect. Answering it wrong is why your first compatibility attempt will appear to hang |
| **Transactions** | `MULTI` `EXEC` `DISCARD` `WATCH` `UNWATCH` | Connection state machine; optimistic concurrency control |
| **Pub/Sub** | `SUBSCRIBE` `UNSUBSCRIBE` `PUBLISH` `PSUBSCRIBE` | Server-initiated pushes; a connection mode where most commands become illegal |

### On SCAN, and being honest about it

Real Redis `SCAN` uses **reverse binary iteration**: the cursor counts by incrementing the high-order bits, which gives a guarantee that survives the table being resized mid-scan — every element present for the whole scan is returned at least once, even if the hash table doubles or halves underneath you. It is one of the cleverest small algorithms in the codebase.

You cannot implement it over `ConcurrentHashMap`, because you do not control the bucket layout. Your options:

| Approach | Guarantee | Cost |
|---|---|---|
| Snapshot the key array, cursor = index | Point-in-time only; misses keys added after the scan starts | O(n) memory spike — unacceptable at scale, which is the whole point of SCAN |
| Cursor = shard index + per-shard iterator position | Per-shard consistency, weaker than Redis's | O(1) memory; needs shards to expose stable iteration |
| **Write your own open-addressing table and implement true reverse binary iteration** ✅ | Full Redis semantics | Significant — but it is also the deepest DSA artifact in the project |

> **Interview note.** Whichever you pick, **document the guarantee you actually provide** in the README. "Our SCAN is per-shard consistent, not resize-stable, because we build on ConcurrentHashMap rather than a custom table — here is what that costs a caller" is a stronger answer than a silent, subtly wrong implementation. Knowing the guarantee you did not implement is itself the signal.

---

## P3 — TTL & expiration

**M3**

Two mechanisms — lazy and active — and a data-structure choice with four real candidates.

### Lazy expiration

Every read path checks `expireAtMs` before returning. If it has passed, delete and reply as though the key were absent. This is O(1), correct, and sufficient for *correctness* on its own.

It is not sufficient for *memory*. A key that expires and is never read again occupies memory forever. That is what active expiration solves — and it is worth being precise that these two mechanisms solve different problems, because interviewers ask exactly that.

### Clock discipline

> **Design decision.** Use **wall-clock time for deadlines** (`System.currentTimeMillis`) and **monotonic time for intervals** (`System.nanoTime`). They are not interchangeable. NTP correction or a manual clock change can move wall-clock time backwards, and if your timing-wheel tick is driven by wall time, a backwards jump stalls the reaper. If your *deadlines* are monotonic, they can't survive a restart or be compared with an `EXPIREAT` timestamp from a client.
>
> Cache the current millisecond in a volatile field updated by the tick thread, and read that instead of calling `currentTimeMillis()` on every operation — it is a real syscall-ish cost at millions of ops/sec, and this is exactly what Redis's `server.mstime` cache is for.

### The active-expiry structure: four candidates

| Structure | Insert | Cancel | Find due | Verdict |
|---|---|---|---|---|
| **Random sampling** (Redis's actual approach) | O(1) | O(1) | probabilistic | No extra structure at all. Sample 20 keys with TTLs, delete expired, repeat while >25% were expired, bounded by a CPU budget. Simple and self-tuning, but expiry lag is unbounded for cold keys |
| `ConcurrentSkipListMap` (deadline → keys) | O(log n) | O(log n) | O(1) peek | Exact next-deadline, so the reaper can sleep precisely. But every `EXPIRE` churns the structure, and it is a second full index over the keyspace |
| `DelayQueue` | O(log n) | O(n) | blocking take | ❌ Rejected. Cancellation is linear and TTL updates leave tombstones that grow without bound. Attractive-looking JDK trap |
| **Hierarchical timing wheel** ✅ | O(1) | O(1) | O(1) per tick | Recommended. Constant-time everything, bounded per-tick work, and it is what Netty's `HashedWheelTimer` and Kafka's purgatory use — so it is a named, recognisable choice |

**Hierarchical timing wheel, sketch:**

```
Wheel 1 — tick 100ms · 512 slots · span 51.2s
   ┌───────────────────────────────────┐
   │  slot[t-1]  slot[t]  slot[t+1] ... │   cursor advances one slot per tick
   └───────────────────────────────────┘
        ▲
        │ insert(key, deadline):
        │   slot = (deadline / tick) mod 512
        │   bucket[slot].add(key, entry.version)   ← O(1)
        │
        │ deadline beyond span? → demote into Wheel 2 (overflow)
        │
Wheel 2 — tick 51.2s · 512 slots · span 7.6h
   cascades down into Wheel 1 as deadlines come into range
```

Insert and cancel are array-index operations, so cost does not grow with the number of pending expirations. Each tick processes exactly one bucket. A two-level wheel with a 100ms tick covers deadlines up to about 7.6 hours; anything longer sits in the overflow wheel until it cascades down.

> **Design decision.** The wheel is a hint index, not the source of truth. `ValueEntry.expireAtMs` is authoritative. A wheel entry carries the `version` it was created against; when a bucket fires, the reaper looks the key up and deletes it only if the key still exists, the version still matches, and the deadline really has passed.
>
> This makes staleness harmless. You never have to remove entries from the wheel on `DEL`, on overwrite, or on TTL extension — stale entries fire once, fail their version check, and are dropped. That eliminates an entire category of bug (index and store disagreeing) at the cost of some wasted ticks, and it is the reason this design is tractable to write correctly in a weekend.

**Build both.** `ExpirationManager` is a strategy interface with a sampling implementation and a wheel implementation, selected by config. That is twenty extra lines and it gives you a genuine comparative benchmark: expiry lag (p99 milliseconds between deadline and actual reclamation) and reaper CPU under a workload of a million keys with randomised TTLs. A graph of one strategy beating the other, from your own measurements, is worth more on a README than any feature.

---

## P4 — Eviction

**M5**

Why the textbook O(1) LRU is the wrong answer for a concurrent store, and what to build instead.

### The trap

Every LRU cache tutorial teaches `HashMap` + intrusive doubly-linked list: move-to-front on access, evict from the tail, O(1) everything. It is the right answer to the interview question "implement an LRU cache." It is the *wrong* answer here, and knowing why is the entire value of this phase.

> **Tradeoff.** Move-to-front is a **write to shared mutable state on every read**. In a concurrent store that turns your read path — the overwhelmingly common path — into a contended critical section. A read-mostly workload that should scale linearly across cores instead serialises on the LRU list head. You have converted an O(1) algorithm into a scalability bottleneck.

### What Redis does instead, and why you should copy it

**Approximated LRU by sampling.** No list. Each entry stores a coarse last-access clock. On eviction, sample *k* random keys (Redis defaults to 5), evict the one with the oldest clock. Redis 3.0 added an **eviction pool**: keep the best 16 candidates seen across sampling rounds, which makes the approximation converge close to true LRU.

- Reads write one plain `int` field on their own entry — no shared structure, no contention, no ordering requirement
- Eviction is O(k) with k constant, not O(1), but eviction is rare relative to reads
- Memory per entry is 4 bytes, versus two pointers for list membership

You are trading exactness for scalability, and you can prove the trade was correct by measuring hit ratio against true LRU on a Zipfian workload. Spoiler: the gap is small. That measurement is a headline chart.

### LFU with a probabilistic counter

LFU needs a frequency count per key, but a 64-bit counter per entry is expensive and never decays, so a key that was hot last Tuesday stays "hot" forever. Redis solves both with an **8-bit logarithmic counter**:

```java
// Morris-style probabilistic increment — 8 bits tracks ~1M accesses
// increment with probability 1 / (counter * lfuLogFactor + 1)
void touch(ValueEntry e) {
    if (e.lfuCounter == 255) return;
    double p = 1.0 / ((e.lfuCounter - LFU_INIT_VAL) * lfuLogFactor + 1);
    if (ThreadLocalRandom.current().nextDouble() < p) e.lfuCounter++;
}

// decay: halve the counter once per lfuDecayTime minutes since last access
byte decayed(ValueEntry e, int nowMinutes) {
    int elapsed = nowMinutes - e.lastAccessMinutes;
    int periods = elapsed / lfuDecayTime;
    return (byte) Math.max(0, e.lfuCounter - periods);
}
```

The higher the counter, the less likely it is to increment — so it grows logarithmically and 8 bits covers a huge dynamic range. This is a **Morris counter**, and naming it correctly in an interview is a strong signal; most candidates have never encountered probabilistic counting.

### Memory accounting: the genuinely hard part in Java

Eviction is triggered by memory pressure, which means you must know how much memory you are using. In C that's `zmalloc_used_memory()`. In Java there is no cheap, accurate per-object size — `Runtime.freeMemory()` tells you about the whole heap including garbage, and reacting to it means reacting to GC timing rather than to your own data.

> **Design decision.** Maintain explicit accounting. Every `RedisValue` implements `sizeBytes()`, computed as payload bytes plus a per-type constant for object header, references, and container overhead. Deltas are applied to a `LongAdder` on every mutation. Calibrate the constants once with [JOL](https://openjdk.org/projects/code-tools/jol/) and document them.
>
> `LongAdder`, not `AtomicLong`: this counter is written by every mutation on every shard, and `AtomicLong` under that contention becomes a CAS-retry hotspot. `LongAdder` spreads writes across cells and pays only on the rare read. Being able to explain that choice unprompted is worth more than the code.

### Policies to implement

`noeviction` (reject `DENYOOM` commands with `-OOM`), `allkeys-lru`, `allkeys-lfu`, `allkeys-random`, `volatile-lru`, `volatile-ttl`. The `volatile-*` family only considers keys that have a TTL — which is exactly where the timing wheel from Phase 3 earns a second use, since it already indexes precisely that subset.

---

## P5 — Concurrency

**M5**

The central architectural decision in the project, and the one you will be asked about most.

Real Redis executes commands on a single thread. That is a defensible design — it makes every command atomic for free and eliminates locking entirely — but on a multi-core machine it leaves throughput on the table, and "I copied Redis" is a weaker interview answer than "I evaluated three models and measured them."

### The three models

**A — Shared ConcurrentHashMap** (lock striping inside the map)

```
  T1   T2   T3   T4
   \    \   /    /
    \    \ /    /
     ▼    ▼    ▼           compute() / CAS
   ┌──────────────────────┐
   │  one map, many bins   │   hot key ⇒ all four threads
   │                        │   retry the same bin
   └──────────────────────┘
```

- \+ trivial to write, no dispatch cost
- \+ reads scale well when keys are spread
- − multi-key atomicity needs an external lock
- − hot-key contention shows up as p99 latency
- − LRU metadata is shared mutable state

**B — Sharded single writer** (actor model: one thread owns each partition)

```
  T1   T2   T3   T4
   \    \   /    /
    ▼    ▼  ▼    ▼
  ┌────────────────────────────┐
  │  router — hash(key) & mask  │
  └───┬──────┬──────┬──────┬────┘
      ▼      ▼      ▼      ▼
   queue   queue  queue  queue
      │      │      │      │
      ▼      ▼      ▼      ▼
  shard0  shard1  shard2  shard3
  HashMap HashMap HashMap HashMap   ← each reachable by exactly one thread
```

- \+ zero locks inside a shard — plain HashMap
- \+ per-shard atomicity is free, like real Redis
- \+ LRU/LFU metadata is thread-confined
- − one handoff per command (queue + wakeup)
- − cross-shard commands need real coordination

> **Design decision.** Build A first, migrate to B, keep both behind the same interface, and benchmark the migration.
>
> This is deliberate. Model A gets you a working concurrent store in a day so the rest of the project can proceed. Model B is the interesting engineering. Keeping both is what turns the work into evidence: a chart of throughput against core count for both models, from your own machine, is the single most persuasive artifact you can put in the README — and the migration story ("here is what broke, here is why") is a better interview answer than either design alone.

### Where synchronisation is required — and where it is not

| Situation | Model A | Model B | Note |
|---|---|---|---|
| Single-key read | none | none | Volatile read of the entry reference is sufficient |
| Read-modify-write (`INCR`, `APPEND`, `LPUSH`) | `map.compute()` | none | In A this is *mandatory*. `get`-then-`put` is the canonical lost-update bug and your test suite must catch it |
| Multi-key on one shard (`MSET` same slot) | external lock | none | B gets this free — the shard thread is already serialised |
| Cross-shard (`MSET`, `SINTER`, `EXEC`) | ordered locks | coordinator | The hard case in both. See below |
| LRU / LFU metadata | plain write, benign race | none | An approximate clock tolerates a lost update by design — do not synchronise it |
| Memory accounting | `LongAdder` | `LongAdder` | Cross-shard by nature |
| Pub/Sub subscriber registry | `ConcurrentHashMap<String, CopyOnWriteArraySet>` | (same) | Read-dominated: publishes vastly outnumber subscribes |
| Blocking ops (`BLPOP`) | per-key waiter queue | (same) | Park a `CompletableFuture`, complete it from the pushing thread. Never block the event loop |

### Cross-shard atomicity

The one place both models get genuinely hard. Options, in increasing order of ambition:

1. **Refuse.** Return an error for multi-key commands spanning shards, exactly as Redis Cluster does with `CROSSSLOT`. Perfectly defensible, and it gives you a reason to implement hash tags (`{user1}:profile`) so callers can co-locate keys deliberately.
2. **Ordered locking.** Acquire shard locks in ascending index order — a total order over locks makes deadlock structurally impossible. Simple, correct, and the explanation ("deadlock needs circular wait; a global lock ordering removes that precondition") is a clean interview answer.
3. **Two-phase coordinator.** Submit a prepare to each shard, collect acknowledgements, then commit. Real distributed-systems shape, real complexity, and probably more than this project needs.

Take option 1 for the first pass and option 2 when you implement `MULTI`/`EXEC`. Document that you considered 3 and why you stopped short — knowing when *not* to build something is a senior signal.

### Thread pools

| Pool | Size | Purpose |
|---|---|---|
| Netty boss group | 1 | Accepts connections only |
| Netty worker group | `cores` | Socket read/write, RESP codec. **Never blocks.** |
| Shard executors | `cores` single-thread executors | Command execution. Not a shared pool — a shard's thread affinity *is* its correctness guarantee |
| Background | 2–3 | AOF fsync, snapshot writing, AOF rewrite, timing-wheel tick |

> **Interview note.** If you use **virtual threads** anywhere (and Phase 6 suggests you should, at least as a benchmark arm), know this: on Java 21 a virtual thread that blocks inside a `synchronized` block **pins its carrier thread**, which can deadlock a thread-per-connection server under load. JEP 491 removed that limitation in Java 24. On 21, use `ReentrantLock` instead of `synchronized` in any path a virtual thread can block in.
>
> This is current, specific, and almost nobody brings it up unprompted.

---

## P6 — Networking

**M2 · M6**

RESP2 on a real TCP socket, and the I/O architecture question with a three-way benchmark attached.

### RESP2

Five type prefixes, all terminated by `\r\n`. It is deliberately trivial to parse — that simplicity is a design achievement worth noting.

`SET user:1 Ada`, on the wire:

```
*3\r\n            ← array of 3
$3\r\nSET\r\n      ← bulk string, 3 bytes
$6\r\nuser:1\r\n
$3\r\nAda\r\n

+OK\r\n           ← simple string reply
-WRONGTYPE Operation against a key holding the wrong kind of value\r\n
:42\r\n           ← integer
$-1\r\n           ← null bulk (key missing)
*0\r\n            ← empty array
```

> **Tradeoff.** TCP is a byte stream, not a message stream. A single `read()` can deliver half a command, three commands, or two and a half. Your decoder must be *incremental*: accumulate into a buffer, attempt to parse, and on incomplete input reset the reader index and return without consuming. It must never assume a read boundary is a message boundary.
>
> This is the number one source of bugs in hand-written protocol servers, and it is why the property test in Phase 4 of the testing plan — split a valid command's bytes at *every* index and feed the fragments — is non-negotiable.

Two more things `redis-cli` will demand that spec summaries omit: **inline commands** (a bare `PING\r\n` with no array framing, used when you type into a raw telnet session) and a sane `COMMAND` / `COMMAND DOCS` reply, which the CLI issues on connect to build tab completion. Return an empty array rather than an error and the CLI proceeds happily.

### Bounding the parser

A client sending `$999999999999\r\n` must be rejected, not trusted. Enforce a maximum bulk length and a maximum multibulk element count, and close the connection on violation. Redis has had CVEs in exactly this area; adding the limit and a test for it is a security-awareness signal that costs ten minutes.

### I/O architecture

| Model | Threads at 10k conns | Verdict |
|---|---|---|
| **Platform thread per connection** | 10,000 | ~1MB stack each and brutal context-switch cost. The C10K problem, live. **Build it anyway** — it takes an afternoon and it is your benchmark baseline |
| **Virtual thread per connection** | 10,000 virtual / `cores` carrier | Blocking code, non-blocking cost. Genuinely current, and a strong comparison arm. Mind the `synchronized` pinning caveat on Java 21 |
| Fixed pool + blocking I/O | bounded | ❌ Worst of both: a slow client occupies a pool thread and head-of-line blocks others. Instructive as a failure demo, not as a design |
| Hand-rolled NIO `Selector` | `cores` | Maximum learning, maximum footguns. Worth one weekend to understand what Netty does |
| **Netty event loop** ✅ | `cores` | The production answer. Reactor pattern, pooled direct buffers, mature backpressure. Recommended as the final architecture |

> **Design decision.** Ship **Netty**, but keep the thread-per-connection server as a selectable transport. Three implementations behind one `Transport` interface, one benchmark, one chart of throughput and p99 latency against connection count. That chart is the deliverable — not the Netty code, which is unremarkable, but the evidence that you understand what it buys and can quantify it.

### Pipelining falls out for free

If your decoder loops until the buffer is exhausted rather than parsing one command per read, you have implemented pipelining. Batch the replies into a single write (Netty: several `write()` calls then one `flush()`) and you collapse N round-trips into one.

Benchmark at depths 1, 8, 64, 512. The improvement is usually dramatic because you have removed the network round-trip from the critical path, and it produces one of the best numbers in the project for effort spent.

### Connection lifecycle

Model it explicitly as a state machine — `CONNECTING → ACTIVE → IN_MULTI → SUBSCRIBED → CLOSING` — because command legality depends on state. A subscribed connection may only issue subscribe/unsubscribe/ping/quit; a connection in `MULTI` queues rather than executes. Handle these with explicit states rather than a scatter of booleans.

Also required: idle timeout (`IdleStateHandler`), a maximum connection count, backpressure via `Channel.isWritable()` and write watermarks so a slow consumer cannot balloon your heap, and graceful shutdown that stops accepting, drains in-flight commands, flushes the AOF, and then closes.

---

## P7 — Persistence

**M7**

A binary snapshot format, an append-only log, and the one place where the JVM makes this genuinely harder than C.

### Snapshot format

Length-prefixed, versioned, checksummed:

```
┌────────────────────────────────────────────────────────────┐
│ MAGIC "MNMO"  (4B) │ FORMAT_VERSION (2B) │ FLAGS (2B)       │
├────────────────────────────────────────────────────────────┤
│ metadata: createdAtMs (8B) · entryCount (varint)            │
├────────────────────────────────────────────────────────────┤
│ ENTRY *                                                     │
│   type       (1B)    0=string 1=list 2=hash 3=set 4=zset    │
│   flags      (1B)    bit0 = has TTL                         │
│   expireAtMs (8B, present only when bit0 set)                │
│   keyLen     (varint) · keyBytes                             │
│   payload    (type-specific, self-describing)                │
├────────────────────────────────────────────────────────────┤
│ EOF marker (1B = 0xFF) │ CRC64 of everything above (8B)     │
└────────────────────────────────────────────────────────────┘
```

Varints for lengths (most keys are short, so a 1-byte length beats a 4-byte one), a version field so a future format change is detectable rather than a mysterious parse failure, and a trailing checksum so a truncated or corrupted file is *refused* rather than half-loaded.

### The fork problem

> **Tradeoff.** Redis takes a snapshot by calling `fork()`. The child inherits a copy-on-write view of the parent's memory, so it can serialise a perfectly consistent point-in-time image while the parent keeps serving writes at full speed, paying only for pages actually modified during the write.
>
> **The JVM has no equivalent.** There is no `fork()`, no process-level copy-on-write over the heap. This is not a limitation to gloss over — it is the most interesting constraint in the whole project, and reasoning through it out loud is exactly the kind of thing that distinguishes a strong candidate.

Your options:

| Approach | Consistency | Write impact |
|---|---|---|
| Stop-the-world | Global point-in-time | ❌ All writes blocked for the whole dump. Unacceptable latency |
| **Per-shard sequential snapshot** ✅ | Each shard internally consistent; no global instant | Only one shard paused at a time — 1/N of writes affected, briefly |
| Fuzzy snapshot + intent log | Reconstructable point-in-time | Near zero, but reconciliation is fiddly and easy to get subtly wrong |
| Persistent structural-sharing map | True MVCC snapshot | Every write allocates path copies — a permanent tax to make a rare operation cheap |

Take per-shard sequential and **document the guarantee precisely**: the snapshot is a valid state each shard passed through, but not necessarily a state the whole keyspace occupied simultaneously. For a cache that is fine. Saying so demonstrates you know the difference — which matters far more than the weaker guarantee costs.

### AOF and group commit

Append every mutating command, in RESP, to a log. Three fsync policies: `always`, `everysec`, `no`.

> **Design decision.** Implement **group commit** for `appendfsync always`. Rather than one `force()` per command, collect concurrent writes arriving within a small window, write them together, issue **one** fsync, then release all the waiting clients at once.
>
> fsync latency is dominated by a fixed per-call cost, so amortising it across a batch improves throughput by close to an order of magnitude at high concurrency *with no loss of durability* — every client still waits for a real fsync before its acknowledgement. This is a standard database technique (PostgreSQL, MySQL, Kafka all do it) and implementing it deliberately is a genuine systems-engineering credential.

### Crash consistency

A crash mid-append leaves a torn record at the tail. Recovery must expect this:

- Parse forward until a record fails to parse or its length runs past EOF
- **Truncate at the last known-good boundary** and log loudly how many bytes were discarded
- Never attempt to interpret a partial record; never fail the whole startup because of a torn tail
- For the snapshot, a bad CRC means *refuse to load* — a corrupt snapshot silently loaded is worse than no snapshot

AOF rewrite: serialise current state to a temp file, buffer commands arriving during the rewrite, append the buffer, then `Files.move(..., ATOMIC_MOVE)`. Atomic rename is what makes the swap crash-safe — at no instant does a reader see a half-written file under the real name.

| Config | Worst-case data loss | Relative write cost |
|---|---|---|
| Snapshot only, every 5 min | up to 5 min | near zero |
| AOF `no` | OS buffer window | near zero |
| AOF `everysec` | ~1 s | low |
| AOF `always` + group commit | 0 acknowledged writes | moderate |
| AOF `always`, no batching | 0 acknowledged writes | high |

Fill the cost column with your own measurements. That table, populated with real numbers, is a README centrepiece.

---

## P8 — Advanced features

**M8 · M9**

Ranked by resume value against implementation cost. Build three; skip the rest deliberately.

| Feature | Value | Cost | Verdict |
|---|---|---|---|
| **Primary-replica replication** | ●●●●● | ●●●●○ | ✅ **Build it.** The only feature here that makes this a distributed-systems project. Handshake, full sync, streaming, offsets, partial resync, replica read-only enforcement |
| **MULTI / EXEC / WATCH** | ●●●●○ | ●●○○○ | ✅ **Build it.** Optimistic concurrency control is a first-class interview topic and `WATCH` reuses the `version` field you already have |
| **Pipelining** | ●●●●○ | ●○○○○ | ✅ **Build it.** Nearly free given a correct decoder, and it produces your largest throughput number |
| Pub/Sub | ●●●○○ | ●●○○○ | Strong fourth. Demos beautifully in a terminal and unlocks keyspace notifications almost free |
| Consistent hashing / client-side sharding | ●●●○○ | ●●●○○ | Good *alternative* to replication if horizontal scaling interests you more than durability. Do not do both |
| Distributed lock (Redlock) | ●●○○○ | ●○○○○ | ❌ Not a server feature — it is an *application* of `SET NX PX`. Ship it as a demo client and discuss the Kleppmann critique |
| Lua scripting | ●●○○○ | ●●●●○ | ❌ Skip. Demonstrates integration, not systems design |
| Cluster mode with gossip | ●●●●● | ●●●●● | ❌ Skip. Months of work; overlaps replication's payoff |

### Replication design

Handshake and streaming:

```
REPLICA                                    PRIMARY
   │                                          │
   ├── PING ──────────────────────────────────▶  (liveness)
   ├── REPLCONF listening-port 6381 ──────────▶
   ├── PSYNC ? -1 ────────────────────────────▶  (no prior history)
   │                                          │
   ◀── +FULLRESYNC <replid> <offset> ─────────┤
   ◀── <snapshot bytes, length-prefixed> ─────┤
   │   load snapshot, set offset                │
   │                                          │
   ◀═══ command stream, continuous ═══════════┤  every write, in
   │    ack offset every second ──────────────▶  execution order
   │                                          │
   ✗ disconnect ... reconnect                   │
   ├── PSYNC <replid> <my-offset+1> ──────────▶
   ◀── +CONTINUE ─────────────────────────────┤  offset still in backlog:
   ◀═══ only the missed bytes ════════════════┤  partial resync, no full dump
```

The **replication backlog** is a fixed-size circular byte buffer of recently propagated commands. On reconnect, if the replica's offset is still inside it, you replay just the gap. If it has fallen out, you fall back to a full resync. It is O(1) to append, bounded in memory, and it is the entire mechanism behind partial resynchronisation — a small, elegant structure with an outsized effect on operational behaviour.

Also required: replicas reject writes from normal clients but accept the primary's stream; `INFO replication` reports role, connected replicas, and offsets; `REPLICAOF NO ONE` promotes a replica, which gives you a manual-failover demo. Propagate **effects, not commands** — a replicated `INCR` is fine, but `SPOP` must be propagated as the specific `SREM` it turned out to be, or the replica diverges because it chose a different random member. That subtlety is a genuinely good thing to have hit and fixed.

### WATCH

Cheap, given Phase 1. `WATCH k` records `(key, entry.version)` on the connection. `EXEC` re-checks every watched version; if any changed, discard and reply nil. Because `version` monotonically increases, this correctly catches the ABA case where a key is deleted and recreated with the same value — something a value-comparison implementation would miss. Write that test.

---

## 02 — Stack decisions

Versions, build tooling, and where Spring is allowed to touch the code.

| Choice | Recommendation | Reasoning |
|---|---|---|
| Java | **21 LTS** (25 LTS if you want the newest) | Records, sealed interfaces, exhaustive pattern-matching switch, and virtual threads — all of which this design actually uses. Note the `synchronized`-pinning caveat on 21 |
| Spring Boot | Current release from [start.spring.io](https://start.spring.io) | Don't pin a version from a document — take what's current when you start. Only the app module depends on it, so the blast radius of an upgrade is one module |
| Build | **Gradle, Kotlin DSL** | Better multi-module ergonomics and a build cache that matters once JMH is in the tree. Maven is fine; Gradle is the stronger signal |
| Networking | Netty 4.1.x | Server module only |
| Logging | SLF4J API in core, Logback in app | Core depends on the *API* only, never a binding — a library that forces a logging implementation on its host is a smell |
| Metrics | Micrometer + Prometheus registry | Applied via the decorator, so core stays clean |
| Testing | JUnit 5, AssertJ, Awaitility, Testcontainers | Awaitility for async assertions without `Thread.sleep`; Testcontainers to run *real* Redis for differential testing |
| Benchmarking | JMH, HdrHistogram, `redis-benchmark` | JMH for the engine, redis-benchmark end-to-end — which works only because you are protocol-compatible |
| Lombok | ❌ Skip | Records and modern Java cover most of it; an annotation processor in the core module weakens the "plain Java" claim for very little gain |

### Configuration without contaminating the core

Spring binds into a plain record the core defines:

```java
// mnemo-core — no Spring, no annotations
public record EngineConfig(
    int shardCount, long maxMemoryBytes, EvictionPolicyType evictionPolicy,
    int evictionSamples, ExpirationStrategy expirationStrategy, int hzTickMs
) {
    public static EngineConfig defaults() { ... }
}

// mnemo-app — Spring's only involvement is populating it
@ConfigurationProperties(prefix = "mnemo.engine")
class EngineProperties { /* setters */ EngineConfig toEngineConfig() { ... } }

@Bean Keyspace keyspace(EngineProperties p, MeterRegistry reg) {
    return new MeteredKeyspace(new ShardedKeyspace(p.toEngineConfig()), reg);
}
```

The dependency arrow points from Spring into plain Java, never the reverse. Add an ArchUnit test asserting no core class imports `org.springframework` and the constraint becomes a build failure rather than a convention people forget.

### Error handling

One exception hierarchy in core, mapping cleanly to RESP error strings: `WrongTypeException → -WRONGTYPE ...`, `SyntaxException → -ERR syntax error`, `OutOfMemoryException → -OOM command not allowed when used memory > 'maxmemory'`.

Catch at the dispatcher boundary and convert to an error reply. **An exception must never escape into the event loop** — one uncaught throwable there can kill the loop thread and silently stop serving every connection bound to it. Wrap the dispatch call, log at ERROR, reply with a generic internal error, keep the connection alive.

### The REST surface

Explicitly *not* the data path. The data path is TCP + RESP; a REST wrapper over `GET`/`SET` would undercut the entire premise. What REST is legitimately for:

- `GET /admin/stats` — keyspace size, memory, hit ratio, evictions, expirations
- `GET /admin/config`, `POST /admin/config` — runtime tuning
- `POST /admin/snapshot` — trigger a save
- `GET /admin/replication` — role, replicas, lag
- `/actuator/health` with a custom `HealthIndicator` that actually round-trips a command through the engine, and `/actuator/prometheus`

Say this out loud in the README. A reviewer who sees a REST controller needs to know within one sentence that you chose the boundary deliberately.

---

## 03 — Data structures & algorithms

Every major component, with complexity, rationale, and the alternative you rejected.

| Component | Structure | Time | Space | Why this | Alternative rejected |
|---|---|---|---|---|---|
| **Keyspace** | Sharded `HashMap[]`, or `ConcurrentHashMap` | O(1) avg / O(n) worst | O(n) | Shard-private maps need no synchronisation at all; a single-threaded owner makes plain `HashMap` safe | `TreeMap` — O(log n) for no ordering benefit, since Redis keyspaces are unordered |
| **Sorted set** | Skiplist with span-augmented forward pointers + `HashMap` for member→score | O(log n) add/rank/range, O(1) score lookup | O(n) expected, ~1.33 ptr/node | Range scans and rank queries in one structure, and far simpler to implement correctly than a balanced tree — no rotations | **`TreeMap` cannot do `ZRANK`** in O(log n) — it has no order-statistic support, so rank degrades to O(n) iteration. The sharpest DSA point in the project |
| **List** | `ArrayDeque`, or a quicklist of chunked arrays | O(1) both ends, O(n) index/remove | O(n), contiguous | Array-backed gives cache locality that a node-per-element list destroys | `LinkedList` — an object header and two pointers per element, and terrible locality |
| **Hash** | Paired array under ~128 entries, promoted to `HashMap` | O(n) small / O(1) large | O(n), very compact when small | Linear scan of a tiny contiguous array beats hashing, and most hashes are tiny. Implementing the promotion is a real memory-optimisation demo | Always `HashMap` — table plus node overhead dwarfs the payload for a 5-field hash |
| **Set** | Sorted `long[]` when all-integer and small, else `HashSet` | O(log n) binary search / O(1) hashed | 8 bytes/element packed | An all-integer set of a few hundred members is dramatically smaller as a primitive array — no boxing, no nodes | `HashSet<Long>` — boxes every element; roughly 6× the memory |
| **Expiration** | Inline deadline + hierarchical timing wheel | O(1) insert/cancel/tick | O(slots + pending) | Constant time regardless of pending count, and bounded work per tick so the reaper never spikes | `DelayQueue` — O(n) cancel and unbounded tombstones. `ConcurrentSkipListMap` — O(log n) churn on every TTL write |
| **LRU** | Per-entry clock + k-sampling with a 16-slot candidate pool | O(1) read, O(k) evict | 4 bytes/entry | Reads touch only their own entry — no shared structure, therefore no contention | HashMap + intrusive DLL — O(1) but every *read* mutates shared state and serialises the read path |
| **LFU** | 8-bit Morris counter, logarithmic increment + time decay | O(1) | 1 byte/entry | ~1M accesses of dynamic range in one byte, with decay so yesterday's hot key cools | Exact `long` counter — 8× the memory and no decay, so it never adapts |
| **Memory accounting** | `LongAdder` + per-type `sizeBytes()` | O(1) amortised | O(cores) | Contention-free under write-heavy load; deterministic, unlike heap introspection | `AtomicLong` — CAS hotspot. `Runtime.freeMemory()` — measures GC timing, not your data |
| **Pub/Sub registry** | `ConcurrentHashMap<channel, CopyOnWriteArraySet>` | O(1) lookup, O(n) fanout | O(subscriptions) | Publishes vastly outnumber subscribes, so pay on write and make iteration lock-free | Synchronised list — publishes would contend with every subscribe |
| **Replication backlog** | Fixed circular `byte[]` + monotonic offset | O(1) append | O(1), configured | Bounded memory with a tunable partial-resync window | Unbounded list — grows without limit and defeats the purpose |
| **Glob matching** | Iterative backtracking matcher | O(n·m) worst, O(n+m) typical | O(1) | No regex compilation per call, no allocation, no catastrophic backtracking | Translating to `java.util.regex` — allocates, and exposes you to pathological patterns |

> **Interview note.** The three rows to be able to defend cold, because they are the ones that reveal depth: **why `TreeMap` can't serve `ZRANK`**, **why textbook O(1) LRU is a scalability regression under concurrency**, and **why `LongAdder` beats `AtomicLong` for a write-hot counter**. Each is a case where the naive answer is *asymptotically* better and *practically* worse, which is exactly the kind of reasoning senior interviews probe for.

---

## 04 — Testing strategy

Six layers, and the specific edge cases that actually catch bugs in a store like this.

### 1 · Unit — core engine, no framework

Fast, in-memory, no Spring context. Every data structure against a reference implementation: your skiplist against `TreeMap` for ordering, your glob matcher against a table of known cases, your `sizeBytes()` against JOL.

**Edge cases:** empty and 512MB-boundary values · binary keys with embedded `\0` and invalid UTF-8 · `INCR` at `Long.MAX_VALUE` (must error, not wrap) · `INCR` on a non-numeric string · negative and out-of-range `LRANGE` indices · `ZADD` with `NaN`, `+inf`, `-inf` · sorted-set ties broken lexicographically by member · operations on a missing key returning the type-correct empty result rather than an error.

### 2 · Protocol — property-based

> **Design decision.** The fragmentation test is mandatory. Take a valid command's byte array, and for every split index from 0 to length, feed it to the decoder as two separate chunks. Then three chunks. Assert you always get exactly the same parsed command and never an exception.
>
> This single parameterised test catches the entire class of "works on localhost, fails over a real network" bugs that plague hand-written protocol servers — because on loopback your reads almost always arrive whole, and in production they don't.

**Also:** null bulk `$-1` · empty bulk `$0\r\n\r\n` (distinct from null!) · empty array `*0` · negative and absurd declared lengths (must reject, not allocate) · `\r` or `\n` inside a payload · inline commands · unknown command · wrong arity · a 10,000-command pipelined batch in one buffer.

### 3 · Differential — against real Redis

Run real Redis in Testcontainers. Generate random command sequences, execute each against both servers with the same client library, assert identical replies.

This is **oracle testing**, and it is the highest-leverage technique available to you: it finds discrepancies you would never think to write an assertion for — error message wording, the exact reply type for an edge case, whether `SET` with `KEEPTTL` preserves the TTL. Restrict the generator to your implemented command subset and it becomes a continuous compatibility proof. Mentioning "differential testing against the reference implementation" in an interview lands hard.

### 4 · Concurrency

Use `CyclicBarrier` to release all threads simultaneously and maximise collision probability; repeat each test hundreds of times, since a race that reproduces one time in fifty passes a single run.

- **Lost update:** N threads × M `INCR` on one key. Final value must be *exactly* N×M. This test fails immediately on a naive get-then-put and is the canonical demonstration
- **Exactly-one-winner:** N threads race `SETNX` on the same key; exactly one returns 1
- **Expiry race:** read a key whose TTL elapses mid-operation; never observe a half-deleted entry
- **Eviction under write load:** hold at `maxmemory` while writing hard; assert memory stays bounded and no entry is ever partially removed
- **`WATCH`/`EXEC` ABA:** delete and recreate a watched key with an identical value; `EXEC` *must* still abort
- **Shard routing:** the same key must always land on the same shard, across restarts and rehashes

For genuinely memory-model-level questions (is a field safely published?), [jcstress](https://openjdk.org/projects/code-tools/jcstress/) is the right tool and citing it signals real awareness of what ordinary tests cannot prove.

### 5 · Persistence & recovery

- **Round-trip:** populate with all types and TTLs, snapshot, wipe, reload, assert full equality including remaining TTLs
- **Torn tail:** write an AOF, truncate at every byte offset, assert recovery loads the longest valid prefix and never throws
- **Corrupt checksum:** flip one byte in a snapshot; loading must be *refused*, loudly
- **Rewrite under load:** trigger an AOF rewrite while writing continuously; the resulting file must reconstruct exactly
- **Kill -9:** a real script that kills the JVM mid-write and restarts it, asserting every acknowledged write survives under `appendfsync always`. This one test justifies the whole durability section
- Empty file, absent file, version mismatch, disk full mid-append

### 6 · Network, failure & load

Client disconnects mid-command · connection killed with a pipelined batch in flight · slow consumer that never reads (assert backpressure engages and heap stays bounded) · max-connections rejection · idle timeout · graceful shutdown draining in-flight work · replica disconnect and partial resync · replica falling out of the backlog and correctly escalating to full resync.

> **Tradeoff.** Coverage percentage is a weak signal and chasing it wastes time. What a reviewer actually looks for is whether the *hard* paths are tested: the concurrency suite, the fragmentation property test, and the crash-recovery script. 70% coverage with those three is far stronger than 95% coverage of getters. Say so in the README rather than posting a bare coverage badge.

---

## 05 — Benchmarking

What to measure, which tool measures it honestly, and the four comparisons worth charting.

### Tools by layer

| Layer | Tool | Measures |
|---|---|---|
| Engine, no network | **JMH** | ns/op for map, skiplist, eviction, expiry. Handles JIT warm-up and dead-code elimination — a hand-rolled loop with `nanoTime` does not and will lie to you |
| End-to-end | **`redis-benchmark`** | QPS and latency over real TCP. Works against your server because you are protocol-compatible — a result you did not have to build |
| Realistic mixes | `memtier_benchmark` | Configurable read/write ratios, key-space distributions, pipelining depth |
| Latency distribution | **HdrHistogram** | p50/p95/p99/p99.9 without the precision loss of naive percentile estimation |
| Memory | JOL, `MEMORY USAGE` | Bytes per key by type and encoding — this is what validates your accounting constants |
| GC | JFR, `-Xlog:gc*` | Pause distribution under load |

> **Tradeoff.** Report percentiles, never averages, and understand coordinated omission. A load generator that waits for each response before sending the next stops sending during a stall — so the stall never appears in the results. Your average looks superb and your p99.9 is fiction.
>
> Use a generator that maintains a fixed *request rate* rather than a fixed concurrency, or use HdrHistogram's `recordValueWithExpectedInterval` to correct for it. Knowing this term and why it matters is a stronger benchmarking signal than any number you produce.

### The four comparisons worth charting

1. **Concurrency model vs core count.** Global lock, `ConcurrentHashMap`, sharded single-writer — throughput at 1, 2, 4, 8, 16 threads. Shows whether your design actually scales, or just claims to.
2. **Eviction policy hit ratio under Zipfian load.** Not speed — *hit ratio*. Generate a Zipf-distributed key access pattern (α ≈ 0.99, roughly what real caches see) and compare true LRU, sampled LRU, LFU, and random at a fixed memory cap. This is how caching is actually evaluated, and it is the chart that shows you know that.
3. **I/O architecture vs connection count.** Thread-per-connection, virtual threads, Netty — p99 latency at 100, 1k, 10k connections. This is where the C10K story becomes a graph.
4. **Pipelining depth.** 1, 8, 64, 512. Usually your most dramatic number.

Secondary: fsync policy against write throughput (the Phase 7 table), and expiry lag for sampling versus timing wheel.

### Method

- Fixed heap (`-Xms` = `-Xmx`) so GC behaviour doesn't drift between runs
- Warm up until the JIT stabilises — JMH does this; your end-to-end harness must too
- Server and client on separate machines if possible; if not, say so, because loopback is not a network
- Report machine specs, JVM version, GC, and heap size next to every number
- Three runs, report the median, note the spread
- Commit the raw output to `benchmarks/results/` — reproducibility *is* the credibility

> **Interview note.** Also benchmark **against real Redis**, and publish the result honestly even though you will lose. Redis is decades of optimised C with no GC. A README that says "we reach roughly X% of Redis's throughput on identical hardware; the gap is dominated by GC pauses and JVM object overhead, measured here" is *far* more impressive than an unqualified number, because it proves you measured the right things and can reason about the difference. Overclaiming is the fastest way to lose a reviewer's trust.

---

## 06 — Docker & deployment

A container that behaves correctly under a memory limit, and a compose topology that demonstrates replication.

`Dockerfile` — multi-stage, non-root, container-aware heap:

```dockerfile
FROM gradle:jdk21 AS build
WORKDIR /src
COPY . .
RUN gradle :mnemo-app:bootJar --no-daemon

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S mnemo && adduser -S mnemo -G mnemo
WORKDIR /app
COPY --from=build /src/mnemo-app/build/libs/*.jar app.jar
RUN mkdir -p /data && chown mnemo:mnemo /data
USER mnemo
VOLUME /data
EXPOSE 6380 8080

ENV JAVA_OPTS="-XX:MaxRAMPercentage=70 -XX:+UseZGC -XX:+ExitOnOutOfMemoryError"

HEALTHCHECK --interval=10s --timeout=3s --start-period=15s --retries=3 \
  CMD wget -qO- http://localhost:8080/actuator/health | grep -q UP || exit 1

ENTRYPOINT ["sh","-c","exec java $JAVA_OPTS -jar app.jar"]
```

> **Design decision.** `-XX:MaxRAMPercentage=70`, not `-Xmx512m`. A hardcoded heap ignores the container's actual limit and either wastes memory or gets OOM-killed when the limit changes. The percentage form reads the cgroup limit at startup.
>
> The remaining 30% is not waste — it is Netty's direct buffers, thread stacks, metaspace, and JVM overhead, all of which live *outside* the heap but inside the container limit. Getting OOM-killed with a heap that looks half-empty is the classic containerised-JVM failure, and being able to explain it is a real operational credential.
>
> **Your `maxmemory` setting must be well under the heap size**, not equal to it — eviction accounts for stored data, not for garbage awaiting collection or the working set of a snapshot in progress.

`compose.yaml` — the topology that demonstrates the project:

```yaml
services:
  primary:
    build: .
    ports: ["6380:6380", "8080:8080"]
    environment:
      MNEMO_ENGINE_MAXMEMORY: 256mb
      MNEMO_ENGINE_EVICTIONPOLICY: allkeys-lfu
      MNEMO_PERSISTENCE_AOF_ENABLED: "true"
      MNEMO_PERSISTENCE_AOF_FSYNC: everysec
    volumes: ["primary-data:/data"]

  replica-1:
    build: .
    environment:
      MNEMO_REPLICATION_PRIMARY: "primary:6380"
    depends_on: { primary: { condition: service_healthy } }

  prometheus:
    image: prom/prometheus
    volumes: ["./ops/prometheus.yml:/etc/prometheus/prometheus.yml:ro"]

  grafana:
    image: grafana/grafana
    ports: ["3000:3000"]
    volumes: ["./ops/grafana:/etc/grafana/provisioning:ro"]
```

A provisioned Grafana dashboard — ops/sec, p99 latency, hit ratio, memory, evictions/sec, replication lag — that comes up populated on `docker compose up` is one screenshot that communicates more about your engineering maturity than a page of README prose. Put that screenshot at the top of the README.

Env vars map to Spring properties by the standard relaxed-binding rules, so `MNEMO_ENGINE_MAXMEMORY` binds to `mnemo.engine.maxmemory` with no glue code.

---

## 07 — Repository presentation

A reviewer gives you ninety seconds. Structure the repo so the first screen does the work.

> **Design decision.** The first screenful of the README must contain, in this order: **one sentence** saying what it is, **one animated terminal recording** of real `redis-cli` talking to your server, and **one benchmark number** with the hardware it came from. A reviewer who scrolls past that is already convinced enough to keep reading; one who has to hunt for it is already gone.
>
> Record the terminal with [asciinema](https://asciinema.org) or VHS. It is fifteen minutes of work and it is the highest-return fifteen minutes in the entire project.

README structure:

```
Mnemo — a concurrent in-memory data store in Java
  ├── one-line description + language/license/CI badges
  ├── asciinema recording: real redis-cli against Mnemo
  ├── Highlights            ~6 bullets, each with a number
  ├── Quick start           docker compose up  →  redis-cli -p 6380
  ├── Architecture          the module diagram + the SET data-flow diagram
  ├── Design decisions      ← the section that gets you hired
  │     · why sharded single-writer over a shared concurrent map
  │     · why sampled LRU over textbook O(1) LRU
  │     · why a timing wheel over DelayQueue
  │     · why per-shard snapshots (and what fork() would have given us)
  │     · what our SCAN guarantees, and what it does not
  ├── Benchmarks            charts + method + raw data + honest Redis comparison
  ├── Supported commands    table, grouped, with a compatibility note
  ├── Protocol              RESP2 support and documented deviations
  ├── Configuration         every property, default, and unit
  ├── Testing               what each layer covers; the crash-recovery script
  ├── Project layout        module table
  ├── Roadmap               what is next, honestly
  └── References            Redis internals docs, papers you actually used
```

**Design decisions is the section that matters.** Every other section describes what you built; that one describes how you think, and it is the only part a senior reviewer will read closely. Give each decision the same shape: the problem, the options, what you chose, what it cost you. Three to six of them, written properly, outweigh every feature bullet in the file.

Supporting material: `docs/architecture.md` with the diagrams · `docs/protocol.md` listing deviations from RESP2 · `docs/benchmarks.md` with method and raw output · a CI workflow running the full suite on every push (a green badge that runs real concurrency tests is worth more than a coverage badge) · a genuine `CONTRIBUTING.md` if you want it to look maintained.

And keep the commit history clean. `feat(evict): add sampled LFU with Morris counter` reads as engineering; `fix stuff` forty times reads as a homework assignment. Reviewers do open the commit log.

---

## 08 — Resume assets

Fill every highlighted blank with your own measurement. Fabricated numbers are the one unrecoverable mistake here.

**Title**

> Mnemo — Concurrent In-Memory Data Store (Java, Netty, Spring Boot)

**One-line description**

> A RESP-compatible in-memory key-value store built from scratch in Java — sharded single-writer concurrency, O(log n) sorted sets on a hand-written skiplist, timing-wheel TTL expiry, sampled LRU/LFU eviction, AOF and snapshot durability, and asynchronous primary-replica replication.

**Bullet points**

1. Built a **thread-safe, RESP2-compatible in-memory data store** in Java 21 supporting `[N]` Redis commands across 5 data types, wire-compatible with the official `redis-cli` and `redis-benchmark`; sustained `[X]` ops/sec at `[Y]` ms p99 on `[hardware]`.
2. Designed a **sharded single-writer concurrency model** that partitions the keyspace across per-core executors, eliminating lock contention on the read path and improving throughput `[Z]`× over a shared-`ConcurrentHashMap` baseline at 16 threads; verified with a deterministic race-condition suite using barrier-synchronised concurrent clients.
3. Implemented **O(1) TTL expiration via a hierarchical timing wheel** and **sampled LRU/LFU eviction with an 8-bit probabilistic (Morris) counter**, holding memory within a configured bound at `[W]`% of true-LRU hit ratio under a Zipfian workload while removing all per-read shared-state mutation.
4. Engineered **crash-consistent durability** with a checksummed binary snapshot format and an append-only log with configurable fsync; **group commit** amortises fsync across concurrent writers, raising durable write throughput `[V]`× under `appendfsync always` with zero loss of acknowledged writes, validated by an automated kill -9 recovery harness.
5. Added **asynchronous primary-replica replication** with offset-tracked partial resynchronisation over a circular backlog, cutting reconnect cost from a full `[S]`-MB transfer to a delta; validated end-to-end with **differential testing against real Redis** in Testcontainers across `[K]` generated command sequences.

> **Tradeoff.** Five bullets is right for a project section only if this is your flagship project. On a one-page resume competing with work experience, **cut to three** — keep 01, 02, and whichever of 03/04/05 you built most thoroughly, because that is the one you will be asked to go deep on.
>
> And every number must be yours. An interviewer who asks "how did you measure that?" and gets a vague answer has learned something much worse about you than a modest number would have told them.

---

## 09 — Roadmap (milestones)

Eleven milestones, ordered so that stopping early still leaves a strong project.

### M0 — Skeleton
**Time:** 2–3 days · **Difficulty:** ▓░░░░

- **Build:** 5 Gradle modules, Kotlin DSL · Spring Boot app module that starts and exposes `/actuator/health` · CI workflow on push · ArchUnit test banning Spring from core
- **Learn:** Multi-module Gradle · Enforcing dependency direction as a build rule
- **Done when:** `./gradlew build` is green in CI and the ArchUnit test *fails* if you deliberately import a Spring class into core.

### M1 — Core engine & strings
**Time:** 1 week · **Difficulty:** ▓▓░░░

- **Build:** `Keyspace`, `ValueEntry`, sealed `RedisValue` · `Command`, `CommandRegistry`, sealed `Reply` · ~12 string + generic commands · stdin REPL for demoing
- **Learn:** Sealed interfaces & exhaustive switch · Command pattern at scale · Binary-safe `byte[]` keys
- **Test:** Per-command unit tests · Type errors → `WRONGTYPE` · `INCR` overflow and non-numeric
- **Done when:** the REPL round-trips every implemented command and adding a new command requires touching exactly two files.

### M2 — RESP + TCP server
**Time:** 1 week · **Difficulty:** ▓▓▓░░

- **Build:** Incremental RESP2 decoder + encoder · Thread-per-connection server · `COMMAND`, `PING`, `INFO`, inline commands · Parser bounds & limits
- **Learn:** TCP is a byte stream, not messages · Incremental parsing & buffer management · Protocol-level DoS defence
- **Test:** **Fragmentation property test** · Malformed & oversized input · Pipelined batch in one buffer
- **Done when:** `redis-cli -p 6380` connects, tab-completes, and round-trips every implemented command. **Record the asciinema here** — this is the project's first real milestone.

### M3 — TTL & expiration
**Time:** 4–5 days · **Difficulty:** ▓▓▓░░

- **Build:** Lazy expiry on every read path · `SamplingExpirer` · `TimingWheelExpirer` with version-checked entries · Cached clock; monotonic vs wall discipline
- **Learn:** Hierarchical timing wheels · Hint-index vs source-of-truth design · Clock correctness
- **Test:** TTL's three return values · Overwrite clears TTL; `KEEPTTL` preserves it · 1M keys with random TTLs → memory returns to baseline
- **Done when:** both strategies are config-selectable and you have measured expiry lag for each.

### M4 — Data types & the skiplist
**Time:** 1–1.5 weeks · **Difficulty:** ▓▓▓▓░

- **Build:** List, Hash, Set · **Span-augmented skiplist** + ZSet · Encoding promotion (intset→hash, paired-array→map) · `OBJECT ENCODING`, `MEMORY USAGE`
- **Learn:** Probabilistic balancing · O(log n) rank via span accumulation · Memory/latency encoding tradeoffs
- **Test:** Skiplist vs `TreeMap` ordering oracle · Rank correctness after heavy churn · Promotion at the exact threshold boundary
- **Done when:** `ZRANK` is provably O(log n) — measured, not asserted — and encodings switch at the configured thresholds.

### M5 — Concurrency & eviction
**Time:** 1–1.5 weeks · **Difficulty:** ▓▓▓▓▓

- **Build:** `ConcurrentHashMap` keyspace (model A) · Sharded single-writer keyspace (model B) · `MemoryAccountant` with `LongAdder` · Sampled LRU + LFU + eviction pool · Ordered shard locking for cross-shard ops
- **Learn:** Lock striping vs thread confinement · Deadlock avoidance by lock ordering · Probabilistic counting · Benign vs harmful data races
- **Test:** **Lost-update INCR test** · SETNX exactly-one-winner · Eviction under sustained write load · Shard-routing stability
- **Done when:** both models pass the identical concurrency suite and you have a throughput-vs-cores chart comparing them.

### M6 — Netty, pipelining & benchmarks
**Time:** 1–1.5 weeks · **Difficulty:** ▓▓▓▓░

- **Build:** Netty transport behind the `Transport` interface · Virtual-thread transport (third arm) · Pipelining + batched flush · Backpressure, idle timeout, graceful shutdown · JMH suite + load harness
- **Learn:** Reactor pattern & event loops · Backpressure and write watermarks · JMH, HdrHistogram, coordinated omission
- **Test:** 10k concurrent connections · Slow-consumer heap bound · Disconnect mid-pipeline
- **Done when:** `redis-benchmark` runs clean against all three transports and `docs/benchmarks.md` holds real numbers with stated hardware.

> **MVP line — a strong portfolio project stops here.** At M6 you have a concurrent, protocol-compatible, benchmarked database with a hand-written skiplist, a timing wheel, two eviction policies, and three I/O architectures compared with real measurements. That is already more depth than most candidates bring. **If you stop here, do M10 next** — an unpresented project is an invisible one.

### M7 — Persistence
**Time:** 1.5–2 weeks · **Difficulty:** ▓▓▓▓▓

- **Build:** Binary snapshot writer/reader + CRC64 · Per-shard sequential snapshot · AOF writer with 3 fsync policies · Group commit · AOF rewrite with atomic rename · Recovery with torn-tail truncation
- **Learn:** Binary format design & versioning · Why the JVM can't do fork+COW · Group commit · Crash consistency & atomic rename
- **Test:** Round-trip all types + TTLs · Truncate AOF at every offset · Corrupt checksum refused · **kill -9 recovery harness**
- **Done when:** the kill -9 script runs 100 iterations and every acknowledged write survives under `appendfsync always`.

### M8 — Transactions & Pub/Sub
**Time:** 1 week · **Difficulty:** ▓▓▓░░

- **Build:** `MULTI`/`EXEC`/`DISCARD`/`WATCH` · Connection state machine · Pub/Sub + pattern subscriptions · Keyspace notifications · `BLPOP` via parked futures
- **Learn:** Optimistic concurrency control · State-dependent command legality · Non-blocking blocking operations
- **Test:** **WATCH ABA case** · EXEC under concurrent modification · Fanout to 1000 subscribers · BLPOP wakeup ordering (FIFO)
- **Done when:** a concurrent check-and-set workload driven through `WATCH`/`EXEC` never produces a lost update across 10,000 attempts.

### M9 — Replication
**Time:** 2 weeks · **Difficulty:** ▓▓▓▓▓

- **Build:** REPLCONF/PSYNC handshake · Full resync via snapshot stream · Circular replication backlog + offsets · Partial resync on reconnect · Read-only replica enforcement · Effect-propagation for nondeterministic commands · `REPLICAOF NO ONE` promotion
- **Learn:** Async replication & its consistency window · Offset-based log shipping · Determinism in state-machine replication
- **Test:** Replica converges under sustained writes · Disconnect → partial resync · Backlog overflow → full resync · `SPOP` does not diverge
- **Done when:** a replica killed for 30 seconds mid-workload reconnects via partial resync and converges byte-identically to the primary.

### M10 — Observability & presentation
**Time:** 1 week · **Difficulty:** ▓▓░░░

- **Build:** Micrometer decorator + Prometheus · Provisioned Grafana dashboard · Dockerfile + compose topology · Admin REST + health indicator · README, architecture & benchmark docs · asciinema recording
- **Learn:** Container-aware JVM tuning · Metric cardinality & naming · Technical writing as an engineering skill
- **Test:** Health endpoint reflects real engine state · Compose stack green from cold start · Metrics scraped and dashboard populated
- **Done when:** a stranger can run `docker compose up`, connect with `redis-cli`, and see a populated Grafana dashboard — without asking you anything. **Do this milestone wherever you stop.**

### Sequencing notes

- **M2 before M3.** Get `redis-cli` talking to your server as early as possible — it makes every later phase demoable and keeps motivation up when the work gets abstract.
- **M5 is the hinge.** Budget for it to overrun. The migration from model A to model B will break things you did not expect, and that breakage is the most valuable material in the project. Keep notes as you go — they become the README's design-decisions section.
- **M7 and M9 are independent.** If time is short, pick the one that matches the roles you are targeting: persistence for infrastructure and storage teams, replication for distributed-systems teams.
- **Benchmark continuously, not at the end.** Every milestone from M6 on should update `docs/benchmarks.md`. Retrofitted benchmarks are always worse, and you lose the regression history that makes the numbers credible.
