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
- **Agent Instructions & Workflow:** See [AGENTS.md](AGENTS.md) for contributor guidelines and the issue → pull request → auto-merge flow.
- **Documentation index:** [docs/README.md](docs/README.md).

## Building & Testing

Prerequisites: JDK 21.

To build all modules, run tests, and verify code style:

```bash
./gradlew build
```

To automatically format source code and apply license headers:

```bash
./gradlew spotlessApply
```

## License

Cringle is licensed under the [Apache License, Version 2.0](LICENSE). Source files include the SPDX header:
`// SPDX-License-Identifier: Apache-2.0`.
