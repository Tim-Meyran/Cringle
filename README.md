# Cringle

Cringle is a modular framework in which applications are assembled from **Blocks** that communicate over **Tethers**, described as **Blueprints** and executed by **Engines** across distributed machines.

## Architecture & Layout

Cringle enforces a strict architectural separation between language-independent platform specifications (**Part A**) and the Kotlin reference implementation (**Part B**).

```
Cringle/
├── proto/           # Language-independent Protocol Buffers definitions (Part A)
├── spec/            # Language-independent specifications (schemas, packages, tethers) (Part A)
├── kotlin/          # Kotlin JVM reference implementation (Part B)
│   ├── contract/          # Core interfaces (Block, BlockProvider, Driver, Tether)
│   ├── common/            # Shared utilities & generated gRPC/proto stubs
│   ├── schema/            # Schema validation and type system
│   ├── packaging/         # Packaging format and manifest handling
│   ├── engine/            # Engine runtime, fabric execution, and isolation
│   ├── router/            # Node router, fabric lookup registry, user management
│   ├── daemon/            # Node-level host bootstrap daemon
│   ├── repository/        # Central repository service
│   ├── management-server/ # Central deployment and placement orchestrator
│   ├── cli/               # Command-line interface
│   ├── testkit/           # In-memory test harness and fakes
│   └── gradle-plugin/     # Included build for cringle.plugin and cringle.project Gradle plugins
├── docs/            # Architecture (Architecture.md) and decisions
├── scripts/         # Development helper scripts
└── .github/         # CI workflows and issue/PR templates
```

## Documentation & Decisions

- **Architecture:** See [docs/Architecture.md](docs/Architecture.md) for detailed architectural documentation.
- **Project Decisions:** See [docs/decisions.md](docs/decisions.md) for binding design decisions.
- **Tasks:** Work items are GitHub issues (label `agent-task`), grouped by milestones M0–M9.
- **Agent Instructions & Workflow:** See [AGENTS.md](AGENTS.md) for contributor guidelines and the issue → pull request → independent review → merge flow.
- **Documentation index:** [docs/README.md](docs/README.md). Start with [Getting started](docs/getting-started.md), then [Operations](docs/operations.md) and [Security](docs/security.md); the samples are in [samples/README.md](samples/README.md); what 1.0.0 contains and what not: [docs/acceptance-1.0.md](docs/acceptance-1.0.md).

## Install

Prerequisite: a JDK 21 or newer on the machine. The installers come with every [release](https://github.com/Tim-Meyran/Cringle/releases/latest): they download the archive, check its SHA-256 and set up the daemon as a service that also runs the management server (web interface, user logins) and the repository, then start it.

**Linux** (systemd), in a terminal:

```bash
curl -fsSL https://github.com/Tim-Meyran/Cringle/releases/latest/download/install.sh | sudo sh -s -- --start
```

**Windows**, in PowerShell (it asks for administrative rights itself):

```powershell
irm https://github.com/Tim-Meyran/Cringle/releases/latest/download/install.ps1 -OutFile install.ps1; powershell -ExecutionPolicy Bypass -File .\install.ps1 -Start
```

Then open a new terminal and log in with the admin token of the first start:

```bash
sudo cat /var/lib/cringle/management/bootstrap-token                     # Windows: type C:\ProgramData\Cringle\management\bootstrap-token
cringle login --server 127.0.0.1:7500 --yes --token-file <file with the token>
cringle machine list
```

The web interface is at `https://127.0.0.1:8443` (log in with the same token). Options (a fixed version, the daemon alone, uninstall) and the details are in [docs/daemon-service.md](docs/daemon-service.md); updates in [docs/updating.md](docs/updating.md).

## Building & Testing

Prerequisites: JDK 21.

To build all modules, run the fast tests, and verify code style:

```bash
./gradlew build
```

The slow tests (processes, network, Gradle builds) are tagged `integration` and run with `./gradlew integrationTest`; `./gradlew build integrationTest` is the full verification.

To automatically format source code and apply license headers:

```bash
./gradlew spotlessApply
```

## License

Cringle is licensed under the [Apache License, Version 2.0](LICENSE). Source files include the SPDX header:
`// SPDX-License-Identifier: Apache-2.0`.
