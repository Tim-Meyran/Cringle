---
id: 010
title: Tether core and in-process tether
milestone: M1
status: open
assignee:
depends_on: [003, 004, 009]
architecture: ["10"]
---

# 010 – Tether core and in-process tether

## Context
A tether is the abstract data connection between two blocks, realized by a driver at runtime. The full tether specification (delivery guarantees, backpressure, wire format) is an open point; this issue delivers the engine-side core with an in-process implementation and a first written spec proposal.

## Scope
- `spec/tether.md`: proposal for modes (sync, async, stream, raw byte stream), configurable backpressure, retries and delivery guarantees. Mark as proposal.
- Tether runtime in the engine: tether wiring from the Blueprint (type fixed at design time, immutable at runtime), port matching, VarArg ports as lists with count fixed at start.
- In-process tether implementation for blocks in the same fabric, with schema validation of messages at runtime.
- Non-blocking guarantee: bounded buffers, suspension-based backpressure.
- **Decided:** engine-internal tether communication is asynchronous, built on coroutines/channels; synchronous tethers are request/response on top of the same mechanism.
- Hook points for the DWH recording mode and breakpoints (interfaces only, no implementation).

## Out of scope
Cross-process and cross-engine transport (018, 020), TCP/serial/filesystem tethers (011 and later).

## Design notes
- Tether behavior properties come from Block/Blueprint config, not from code.
- Serialization only happens at process boundaries (chapter 17, rule 7); in-process passes objects after validation.

## Acceptance criteria
- [ ] All tether modes work between two test blocks.
- [ ] Backpressure test: slow consumer does not block the producer's dispatcher.
- [ ] Type mismatch and schema violations are detected with clear errors.

## Notes / Findings

## Result
