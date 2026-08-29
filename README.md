# Mnemo

A Redis-like in-memory data store, built from scratch in Java. RESP2-compatible TCP server on top of a hand-written engine, with Spring Boot used only as an outer shell (wiring, config, admin REST, Actuator) — not as the data path.

> **Status: M0 (build scaffold) only.** No engine logic exists yet — no `Keyspace`, no commands, no RESP parser. This repo currently proves the module structure and the "core stays pure JDK" enforcement, nothing more. The build has **not** been verified end-to-end on this machine, because no JDK is installed here yet.
>
> See [ROADMAP.md](ROADMAP.md) for the full implementation plan and milestone breakdown (M0–M10).

## Prerequisites

| Tool | Version | Notes |
|---|---|---|
| JDK | 21 (Temurin recommended) | Not installed on this machine yet — see below |
| Git | any recent version | |

**Gradle itself is not a prerequisite.** The Gradle Wrapper is committed (`gradlew`, `gradlew.bat`, `gradle/wrapper/`), pinned to Gradle 9.7.1 with a verified checksum — always use `./gradlew`, never a system-installed `gradle`.

### Installing the JDK

```powershell
winget install EclipseAdoptium.Temurin.21.JDK
```

Then open a new terminal and confirm it's on `PATH`:

```bash
java -version
```

## Setup

```bash
git clone <this-repo-url>
cd Redis-in-java
```

## Common commands

```bash
# Full build — compiles every module, runs the pure-JDK dependency check on
# core/protocol/persistence, runs the ArchUnit test, runs all unit tests
./gradlew build

# Run just one module's tests
./gradlew :mnemo-core:test

# Start the Spring Boot app (mnemo-app)
./gradlew :mnemo-app:bootRun

# With the app running, confirm it's actually up
curl http://localhost:8080/actuator/health
```

On Windows PowerShell, use `.\gradlew.bat` instead of `./gradlew`.

## Project layout

| Module | Role | Depends on |
|---|---|---|
| `mnemo-core` | the engine — pure JDK, zero third-party dependencies | — |
| `mnemo-protocol` | RESP2 codec | `mnemo-core` |
| `mnemo-persistence` | snapshot + AOF persistence | `mnemo-core` |
| `mnemo-server` | Netty TCP transport | `mnemo-protocol`, `mnemo-persistence` |
| `mnemo-app` | Spring Boot shell — wiring, config, `/actuator/health`, admin REST | `mnemo-server` |

`mnemo-core`, `mnemo-protocol`, and `mnemo-persistence` are enforced to stay framework-free two ways: a Gradle-level check on the resolved dependency graph, and an ArchUnit test against compiled bytecode (`mnemo-core/src/test/java/dev/vishalverma/mnemo/arch/CoreIsPureJavaTest.java`). Both fail the build if Spring or Netty ever leak into those modules.

## Roadmap

Full milestone-by-milestone plan — architecture, data structures, concurrency model, testing strategy, benchmarking method — lives in [ROADMAP.md](ROADMAP.md).
