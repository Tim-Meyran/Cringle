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
| `SERIAL` | raw bytes over a serial connection to a device | `openByteStream` | `ByteStreamOpened` |

A tether connects an `OUT` port to an `IN` port. Both ports must support the tether type. The `OUT` end's handle initiates traffic; the `IN` end's handle rejects all operations with `IllegalStateException`. An operation of the wrong type throws `IllegalStateException`. An endpoint can be part of one tether only. VarArg ports are wired per index; their size is fixed at start.

### TCP tethers

A `TCP` tether is a byte transport: it carries raw bytes over a real TCP connection and is meant for communication with external systems and devices, not for typed messages between blocks. It stays that; typed messages across process boundaries (TCP, IPC, cross-engine) come with the wire format of issue #76, for every tether type alike. The blueprint gives the tether a `port` (1 to 65535, required). When the fabric starts, the engine has the receiving (IN) block listen on that loopback port through the TCP driver; the port registry makes a conflict with another block or fabric fail the start with a message that names the owner (`port N is already used by block 'x' of fabric 'y'`). `openByteStream()` at the sending (OUT) end connects; the receiver gets `ByteStreamOpened` for every connection. Only delivery `DROP` is allowed: a listener buffers the tether's buffer capacity of accepted connections, and a connection that arrives while that buffer is full is closed at once and logged, because a listener must not hold connections nobody takes. Listeners and connections are closed when the fabric stops; the ports are free once the stop is done. Byte data is not checked against a schema. Connections across engines are not covered (see #20).

### Serial tethers

A `SERIAL` tether is a byte transport like `TCP`, but to a serial device instead of a socket. The blueprint gives
the tether a `serial` object (`device`, `baudRate`, `dataBits`, `parity`, `stopBits`; see `package-format.md`).
The device is an exclusive resource: the engine keeps one registry of open devices, and a second tether that asks
for a device that is already in use fails the start with a message that names the owner
(`device '/dev/ttyUSB0' is already used by block 'x' of fabric 'y'`). At start the receiving (IN) block's serial
driver opens the device; the IN block gets `ByteStreamOpened` with the connection. `openByteStream()` at the
sending (OUT) end attaches to the same connection. Only delivery `DROP` is allowed, and byte data is not checked
against a schema. A missing device or a missing permission fails the start with a message naming the tether and
the device; the failed start releases the device, so it can be repeated. An I/O error while running closes the
byte stream and is logged; the device is not reopened automatically (a follow-up). The device is closed when the
fabric stops; `awaitClosed` waits for it, like the TCP ports.

### Remote tethers (blueprint field)

A tether can end at a port of a block on another engine at a fixed address: the blueprint gives the tether one local
endpoint and a `remote` object (`address`, `fingerprint`, `fabric`, `block`, `port`, optional `index`) instead of the
other endpoint; see `package-format.md`, section 5. The local endpoint is the `OUT` port (`from`) when the remote end
receives and the `IN` port (`to`) when it sends. The types `MESSAGE`, `REQUEST_RESPONSE`, `STREAM` and `BYTE_STREAM`
may have a `remote`, both delivery policies apply. The remote engine is identified by the fingerprint of its key and
trusted by it only. Only the blueprint field is defined so far; an engine that does not implement the connection yet
rejects the deployment of a blueprint with a `remote` tether with `remote tethers are not supported by this engine yet`.

## Schemas

Ports carry a schema. `MESSAGE`, `REQUEST_RESPONSE` and `STREAM` values are checked against the schema of the sending port before they enter the tether; violations throw `TetherValidationException` naming the tether, the schema and the JSON path. The schemas of both ports must be assignable (nominal, see `schema.md`). The schema check applies to these three types, on local tethers, and to nothing else: byte streams (`BYTE_STREAM`, `TCP` and `SERIAL`) are not checked. Schema messages for every tether type, across process boundaries too, follow with the wire format of #76. **Decided:** a response is validated against the same schema as the request; a port has one schema for both directions.

## Backpressure and ordering

Every tether has bounded buffers (default 64 entries; every stream direction has its own). The blueprint can set
`bufferCapacity` per tether. A full buffer suspends the sender; no thread is ever blocked. Values of one tether arrive in send order.

## Delivery guarantees (in process)

- Per tether the blueprint sets a delivery policy (`delivery`, default `DROP`) for a receiver that is not running. `DROP`: the message, request or stream opening is dropped and logged (at most once); a request fails with `TetherDeliveryException`. `BUFFER`: delivery is retried until the receiver runs again; the tether's bounded buffer fills up meanwhile and the sender suspends. A request under `BUFFER` is abandoned once its timeout has passed.
- `BUFFER` retries with the tether's `retry` configuration: `maxAttempts` (default unlimited), `backoffMs` and `backoff` (`FIXED` or `EXPONENTIAL`, capped by `maxBackoffMs`). When the attempts are exhausted the message is dropped and logged; a request fails with `TetherDeliveryException`. Only a `FabricException` (the receiver is not running) is retried: any other exception thrown by the receiver drops that message and is logged, and is not retried.
- A request fails with `TetherDeliveryException` if it cannot be delivered and with `TetherTimeoutException` if no response arrives within the timeout (default 30 s).
- See "Start and stop" for what stopping does to senders, requests and streams.

## Start and stop

**Start.** The engine opens the tethers of all blocks first and only then starts the blocks, so a block can already use its tether handles in `start()`. Deliveries run on the fabric thread, which is busy starting the blocks, so a value that a block sends in `start()` is delivered only after all blocks have been started. If the receiver is running at that moment it gets the value, whatever the delivery policy is: a block that starts after the sender, and starts normally, receives it with `BUFFER` and with `DROP`. The delivery policy decides only when the receiver is not running at the time of the delivery, for example because its start failed or because it was stopped: `BUFFER` holds the value until the receiver runs (for example after a restart), `DROP` (the default) drops it and logs it, and the receiver never gets it. Only a start that opens every tether opens the block ports; a start that fails (for example because a port is taken or the working directory cannot be created) closes the tethers again, leaves the fabric stopped, and can be repeated.

**Stop.** Stopping does not wait for anything that is not making progress. A sender waiting for buffer space and a `request` without a response fail at once with `TetherDeliveryException` naming the stop as the reason; they do not run into their timeout and their coroutines are not cancelled. Every open stream is closed, the tethers are closed, and afterwards the driver sets of the blocks are closed, so the resources they hold (TCP ports, connections) are free when the stop is done. Closing a driver or a driver set never blocks a thread: it only starts the closing and the caller waits for the result with a timeout if it needs the ports to be free (see `DriverSet.awaitClosed`).

## Hooks

`TetherObserver` sees every value after validation (basis for DWH recording); `TetherInterceptor` runs before delivery and may suspend (basis for breakpoints). Both are interfaces only; the engine ships no implementation.

## Not covered

- The wire format for typed (schema) messages across process boundaries and between engines, one format for every tether type: issue #76 (together with #20).
- The filesystem driver: issue #75.
