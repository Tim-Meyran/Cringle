# Tether specification (proposal)

**Status: proposal.** Delivery guarantees, backpressure and wire format are open points of the architecture (chapter 10). This document describes what the in-process implementation does today; items marked `[Zu bestätigen]` need a decision.

## Modes

The type of a tether is fixed in the blueprint and never changes at runtime.

| Type | Meaning | Sender operation | Receiver event |
|---|---|---|---|
| `MESSAGE` | asynchronous, fire and forget | `send` | `Message` |
| `REQUEST_RESPONSE` | request, sender suspends until the response arrives | `request` | `Request` (answer with `respond`) |
| `STREAM` | bidirectional stream of values | `openStream` | `StreamOpened` |
| `BYTE_STREAM` | bidirectional raw bytes | `openByteStream` | `ByteStreamOpened` |

A tether connects an `OUT` port to an `IN` port. Both ports must support the tether type. The `OUT` end's handle initiates traffic; the `IN` end's handle rejects all operations with `IllegalStateException`. An operation of the wrong type throws `IllegalStateException`. An endpoint can be part of one tether only. VarArg ports are wired per index; their size is fixed at start.

## Schemas

Ports carry a schema. `MESSAGE`, `REQUEST_RESPONSE` and `STREAM` values are checked against the schema of the sending port before they enter the tether; violations throw `TetherValidationException` naming the tether, the schema and the JSON path. The schemas of both ports must be assignable (nominal, see `schema.md`). Byte streams are not checked. `[Zu bestätigen]` A response is validated against the same schema as the request.

## Backpressure and ordering

Every tether has bounded buffers (default 64 entries; every stream direction has its own). A full buffer suspends the sender; no thread is ever blocked. Values of one tether arrive in send order.

## Delivery guarantees (in process)

- A message is delivered at most once. If the receiving block cannot take it (not running), it is dropped and logged. `[Zu bestätigen]` retries and at-least-once delivery are configuration matters for later.
- A request fails with `TetherDeliveryException` if it cannot be delivered and with `TetherTimeoutException` if no response arrives within the timeout (default 30 s).
- Stopping the fabric fails waiting senders and closes open streams; starting it again reopens the tethers with the same handles.

## Hooks

`TetherObserver` sees every value after validation (basis for DWH recording); `TetherInterceptor` runs before delivery and may suspend (basis for breakpoints). Both are interfaces only; the engine ships no implementation.

## Not covered

Cross-process and cross-engine transport (wire format), TCP/serial/filesystem tethers.
