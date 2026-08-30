# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project status

**M1 (core engine & strings) complete.** `./gradlew clean build` is verified green end-to-end.

`mnemo-core` holds a working single-threaded, single-shard engine: a binary-safe `Bytes` value
type, a sealed `RedisValue`, a `Keyspace` with lazy TTL expiry, a sealed `Reply`, a
`Command`/`CommandRegistry` pair, 17 string and generic commands, and a stdin REPL. There is
still no RESP codec and no networking — `mnemo-protocol`, `mnemo-persistence`, and `mnemo-server`
remain `*Marker` placeholders.

The full milestone-by-milestone implementation plan (M0–M10) — architecture, data structures,
concurrency model, testing strategy, benchmarking — lives in [ROADMAP.md](ROADMAP.md); consult it
before implementing any new milestone so new code matches the intended design. Note the ROADMAP
is internally inconsistent in a few places (see "Known ROADMAP conflicts" below).

## Commands

Always use the Gradle Wrapper (`./gradlew`, or `.\gradlew.bat` on Windows PowerShell) — never a
system-installed `gradle`. It's pinned to a specific Gradle version with a verified checksum.

```bash
# Full build: compiles every module, runs the pure-JDK dependency check on
# core/protocol/persistence, runs the ArchUnit test, runs all unit tests
./gradlew build

# Run just one module's tests
./gradlew :mnemo-core:test

# Run a single test class / method
./gradlew :mnemo-core:test --tests "dev.vishalverma.mnemo.core.command.SetCommandTest"
./gradlew :mnemo-core:test --tests "*.SetCommandTest.nxOnlySetsWhenAbsent"

# Start the Spring Boot app (mnemo-app)
./gradlew :mnemo-app:bootRun

# With the app running, confirm it's actually up
curl http://localhost:8080/actuator/health
```

Drive the engine directly through the REPL (no Gradle task on purpose — a `JavaExec` task would
need `standardInput = System.in`, which is incompatible with the repo-wide configuration cache,
and the Gradle console mangles interactive stdin anyway):

```bash
./gradlew :mnemo-core:classes && java -cp mnemo-core/build/classes/java/main dev.vishalverma.mnemo.core.repl.Repl
```

Requires JDK 21 (Temurin recommended): `winget install EclipseAdoptium.Temurin.21.JDK`.

CI (`.github/workflows/ci.yml`) just runs `./gradlew build --stacktrace` on `ubuntu-latest` with
JDK 21 — that single command is the full gate (compile + pure-JDK check + ArchUnit + unit tests).

## Architecture

### Module graph and the framework-free core rule

This is a Redis-like in-memory data store, hand-written in Java, exposed over a RESP2-compatible
TCP server. Spring Boot is used **only** as an outer shell (wiring, config binding, admin REST,
Actuator) — it is never allowed to leak into the engine itself. That rule is the organizing
principle of the whole repo:

```
mnemo-core          engine/types, expiry, eviction — pure JDK, zero third-party deps
  ↑
mnemo-protocol       RESP2 codec (depends on core)               — pure JDK
mnemo-persistence    snapshot + AOF (depends on core)            — pure JDK
  ↑
mnemo-server         Netty TCP transport, connections, replication (depends on protocol + persistence)
  ↑
mnemo-app            Spring Boot shell: wiring, config, /actuator/health, admin REST (depends on server)
```

Dependency arrows point only downward/inward. `mnemo-core` is a leaf: compilable, testable, and
(eventually) JMH-benchmarkable with no framework, no network, no filesystem.

`mnemo-core`, `mnemo-protocol`, and `mnemo-persistence` must stay framework-free. This is enforced
two independent ways, both wired into `./gradlew check` (and therefore `build`):

1. **Gradle-level dependency graph check** — `mnemo.pure-java-conventions` (in
   [buildSrc/src/main/kotlin/mnemo.pure-java-conventions.gradle.kts](buildSrc/src/main/kotlin/mnemo.pure-java-conventions.gradle.kts))
   inspects the *resolved* `compileClasspath`/`runtimeClasspath` for any module that applies it,
   and fails the build if anything under `org.springframework`, `jakarta.`, or `io.netty` shows
   up — catching a forbidden dependency the moment it's declared, before any code references it.
2. **ArchUnit test on compiled bytecode** —
   [mnemo-core/src/test/java/dev/vishalverma/mnemo/arch/CoreIsPureJavaTest.java](mnemo-core/src/test/java/dev/vishalverma/mnemo/arch/CoreIsPureJavaTest.java)
   catches what the graph-level check can't see: actual imports/extends/annotations/throws
   referencing a forbidden package (e.g. accidental use of `java.util.logging` instead of SLF4J).
   It also asserts on `core_must_depend_only_on_the_jdk_and_itself` — i.e. `mnemo-core` may only
   reference `dev.vishalverma.mnemo.core..`, `java..`, `javax..`, `jdk..`.

When adding a module or dependency, decide up front whether it belongs on the pure-JDK side
(`mnemo.pure-java-conventions`) or the framework side (`mnemo.java-conventions`) — see
[buildSrc/src/main/kotlin](buildSrc/src/main/kotlin) for both convention plugins.

### Build conventions (buildSrc)

The root [build.gradle.kts](build.gradle.kts) deliberately has no `allprojects {}` /
`subprojects {}` blocks — shared build logic lives in buildSrc convention plugins and is applied
explicitly per module, which is itself part of keeping Spring's dependency management from
leaking into the pure-JDK modules:

- `mnemo.java-conventions` — Java 21 toolchain, `-Xlint:all -Werror`, JUnit 5 + AssertJ test deps.
  Applied by every module.
- `mnemo.pure-java-conventions` — applies `mnemo.java-conventions` plus the ArchUnit test
  dependency and the forbidden-dependency Gradle check described above. Applied only by
  `mnemo-core`, `mnemo-protocol`, `mnemo-persistence`.

Dependency versions are centralized in [gradle/libs.versions.toml](gradle/libs.versions.toml)
(the version catalog) — add new third-party libraries there, not as inline coordinates in a
module's `build.gradle.kts`.

Note on `mnemo-server`'s Netty BOM: it's applied as `enforcedPlatform`, not `platform`, because
`mnemo-app` also pulls in `spring-boot-dependencies`, which manages `io.netty:*` versions too.
`enforcedPlatform` makes `mnemo-server`'s own pinned Netty version win regardless of what a
downstream consumer's BOM says.

### The engine as built (M1)

`mnemo-core` packages, all under `dev.vishalverma.mnemo.core`:

```
Engine.java   dispatch boundary — also the ArchUnit anchor (see below)
type/         Bytes, RedisValue (sealed), StringValue, ListValue
store/        Clock, ValueEntry (package-private), Keyspace
error/        MnemoException + WrongType/Syntax/NotAnInteger
command/      Reply (sealed), Command, Arity, Flag, CommandContext,
              CommandRegistry, Args;  impl/ = one class per command
repl/         InlineParser, ReplyPrinter, Repl
```

Four invariants worth preserving, each of which exists to stop a whole class of bug:

- **`Engine.dispatch` is total — it never throws.** Bad input becomes a `Reply.Err`, and even an
  unexpected `RuntimeException` becomes an internal-error reply. M2 calls this from a Netty event
  loop, where one escaped throwable kills the loop thread and silently stops serving every
  connection bound to it.
- **Commands never touch `ValueEntry`; they go through `Keyspace`.** Lazy expiry lives in one
  private `live()` method and the version bump in one `write()` method, so no command can forget
  either. `ValueEntry` is package-private to enforce this.
- **`Keyspace.version` is keyspace-scoped and monotonic, not per-entry.** A per-entry counter
  restarts at 0 when a key is deleted and recreated, so `WATCH`'s ABA case (delete a watched key,
  put an identical value back) would wrongly look unchanged. Nothing reads it until M7.
- **`Reply` and `RedisValue` are sealed, and switches over them have no `default`.** Adding a
  variant makes the compiler list every site that must handle it — the entire reason for sealing.

**The ArchUnit anchor must stay in the root package.** `@AnalyzeClasses(packagesOf = Engine.class)`
sweeps that class's package *and its subpackages*. Re-anchoring on a class in `store` or `command`
would silently narrow every purity rule to that one subpackage while still passing, because the
`classes_were_actually_imported` net only asserts `> 0`. Verified: the sweep imports 49 classes
across all 7 packages, and a violation planted in a subpackage does fail the rule.

Adding a command touches **exactly two files** — the new class in `command/impl/`, and one
registration line in `CommandRegistry.standard()`. There is deliberately no dispatch switch, no
arity table, and no annotation scanning: everything dispatch needs is read off the `Command`
object. This is M1's literal acceptance criterion; verify it still holds before merging registry
changes.

### Known ROADMAP conflicts

The ROADMAP is the design authority but contradicts itself in a few places. Resolutions in force:

| ROADMAP says | Resolution |
|---|---|
| "SLF4J API in core" | Impossible — the ArchUnit rule bans `org.slf4j`. Core logs nothing; it throws typed exceptions and the caller decides. Changing this means deliberately amending the rule. |
| `record Bulk(byte[] payload)` | Uses `Bytes` — an array component gives a record identity equality, breaking every reply assertion. |
| Reply names `Simple/Error/Integer/Bulk/Array/Null` | Uses the code block's `Simple/Err/Int/Bulk/Arr/Nil`; `Error` and `Integer` would shadow `java.lang` types. |
| `impl/` holds "one class per command family" | One class per *command*, so the two-file rule is literal. A nested `Set` command class would also shadow `java.util.Set` in `flags()`. |
| Lazy expiry is an M3 deliverable | Landed in M1 — `SET ... EX` is meaningless without it. M3 still owns active expiry, the timing wheel, and the cached clock. |
| `ValueEntry.lastAccess` seconds vs `lastAccessMinutes` | Stored in seconds; M5's LFU derives minutes at the call site. |

### Target architecture beyond M1 (per ROADMAP.md, not yet implemented)

`Keyspace` becomes an interface with a sharded implementation and a `MeteredKeyspace` decorator;
eviction and expiration become pluggable strategies; execution becomes single-writer-per-shard
with no locking inside a shard. The one thread handoff in the request path is at the router
(hashing the key to a shard); AOF and replication are fed from inside the shard so their ordering
matches execution ordering, and the AOF write happens before a client-visible reply is released.
Read ROADMAP.md's "01 — Architecture" section in full before implementing engine-level code.
