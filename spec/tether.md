# Tether specification (proposal)

**Status: proposal.** Delivery guarantees, backpressure and wire format are open points of the architecture (chapter 10). This document describes what the in-process implementation does today.

## Modes

The type of a tether is fixed in the blueprint and never changes at runtime.

| Type | Meaning | Sender operation | Receiver event |
|---|---|---|---|
| `MESSAGE` | asynchronous, fire and forget | `send` | `Message` |
| `REQUEST_RESPONSE` | request, sender suspends until the response arrives | `request` | `Request` (answer with `respond`) |
| `STREAM` | bidirectional stream of values | `openStream` | `StreamOpened` |
| `BYTE_STREAM` | bidirectional raw bytes | `openByteStream` | `ByteStreamOpened` |
| `TCP` | raw bytes over a real TCP connection on loopback | `openByteStream` | `ByteStreamOpened` |

A tether connects an `OUT` port to an `IN` port. Both ports must support the tether type. The `OUT` end's handle initiates traffic; the `IN` end's handle rejects all operations with `IllegalStateException`. An operation of the wrong type throws `IllegalStateException`. An endpoint can be part of one tether only. VarArg ports are wired per index; their size is fixed at start.

### TCP tethers

A `TCP` tether is a byte transport: it carries raw bytes over a real TCP connection and is meant for communication with external systems and devices, not for typed messages between blocks. It stays that; typed messages across process boundaries (TCP, IPC, cross-engine) come with the wire format of issue #76, for every tether type alike. The blueprint gives the tether a `port` (1 to 65535, required). When the fabric starts, the engine has the receiving (IN) block listen on that loopback port through the TCP driver; the port registry makes a conflict with another block or fabric fail the start with a message that names the owner (`port N is already used by block 'x' of fabric 'y'`). `openByteStream()` at the sending (OUT) end connects; the receiver gets `ByteStreamOpened` for every connection. Only delivery `DROP` is allowed: a listener buffers the tether's buffer capacity of accepted connections, and a connection that arrives while that buffer is full is closed at once and logged, because a listener must not hold connections nobody takes. Listeners and connections are closed when the fabric stops; the ports are free once the stop is done. Byte data is not checked against a schema. Connections across engines are not covered (see #20).

## Schemas

Ports carry a schema. `MESSAGE`, `REQUEST_RESPONSE` and `STREAM` values are checked against the schema of the sending port before they enter the tether; violations throw `TetherValidationException` naming the tether, the schema and the JSON path. The schemas of both ports must be assignable (nominal, see `schema.md`). The schema check applies to these three types, on local tethers, and to nothing else: byte streams (`BYTE_STREAM` and `TCP`) are not checked. Schema messages for every tether type, across process boundaries too, follow with the wire format of #76. **Decided:** a response is validated against the same schema as the request; a port has one schema for both directions.

## Backpressure and ordering

Every tether has bounded buffers (default 64 entries; every stream direction has its own). A full buffer suspends the sender; no thread is ever blocked. Values of one tether arrive in send order.

## Delivery guarantees (in process)

- Per tether the blueprint sets a delivery policy (`delivery`, default `DROP`) for a receiver that is not running. `DROP`: the message, request or stream opening is dropped and logged (at most once); a request fails with `TetherDeliveryException`. `BUFFER`: delivery is retried until the receiver runs again; the tether's bounded buffer fills up meanwhile and the sender suspends. A request under `BUFFER` is abandoned once its timeout has passed. Retries and at-least-once delivery across process boundaries are open points.
- A request fails with `TetherDeliveryException` if it cannot be delivered and with `TetherTimeoutException` if no response arrives within the timeout (default 30 s).
- See "Start and stop" for what stopping does to senders, requests and streams.

## Start and stop

**Start.** The engine opens the tethers of all blocks first and only then starts the blocks, so a block can already use its tether handles in `start()`. Deliveries run on the fabric thread, which is busy starting the blocks, so a value that a block sends in `start()` is delivered only after all blocks have been started. If the receiver is running at that moment it gets the value, whatever the delivery policy is: a block that starts after the sender, and starts normally, receives it with `BUFFER` and with `DROP`. The delivery policy decides only when the receiver is not running at the time of the delivery, for example because its start failed or because it was stopped: `BUFFER` holds the value until the receiver runs (for example after a restart), `DROP` (the default) drops it and logs it, and the receiver never gets it. Only a start that opens every tether opens the block ports; a start that fails (for example because a port is taken or the working directory cannot be created) closes the tethers again, leaves the fabric stopped, and can be repeated.

**Stop.** Stopping does not wait for anything that is not making progress. A sender waiting for buffer space and a `request` without a response fail at once with `TetherDeliveryException` naming the stop as the reason; they do not run into their timeout and their coroutines are not cancelled. Every open stream is closed, the tethers are closed, and afterwards the driver sets of the blocks are closed, so the resources they hold (TCP ports, connections) are free when the stop is done. Closing a driver or a driver set never blocks a thread: it only starts the closing and the caller waits for the result with a timeout if it needs the ports to be free (see `DriverSet.awaitClosed`).

## Hooks

`TetherObserver` sees every value after validation (basis for DWH recording); `TetherInterceptor` runs before delivery and may suspend (basis for breakpoints). Both are interfaces only; the engine ships no implementation.

## Not covered

- The wire format for typed (schema) messages across process boundaries and between engines, one format for every tether type: issue #76 (together with #20).
- The serial tether and the filesystem driver: issue #75.
