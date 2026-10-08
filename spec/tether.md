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

### Tethers between engines

A tether can end at a port of a block on another engine at a fixed address: the blueprint gives the tether one local
endpoint and a `remote` object (`address`, `fingerprint`, `fabric`, `block`, `port`, optional `index`) instead of the
other endpoint; see `package-format.md`, section 5. The local endpoint is the `OUT` port (`from`) when the remote end
receives and the `IN` port (`to`) when it sends. The remote engine is identified by the fingerprint of its key and
trusted by it only. The types `MESSAGE`, `REQUEST_RESPONSE`, `STREAM` and `BYTE_STREAM` can end on another engine and
behave as they do locally (below); `TCP` and `SERIAL` are local resources and cannot.

**Transport.** Every engine serves `cringle.engine.v1.RemoteTetherService` (`proto/cringle/engine/v1/remote_tether.proto`)
on its tether port (`<engineDir>/tether.port`, option `--tether-port`, default any free port), over mutual TLS with the
identity of the engine. One bidirectional call `Exchange` carries one tether; each gRPC message holds the bytes of one
frame of the wire format (`wire.md`, typed mode, JSON payload). The sender opens the call when its fabric starts, before
the blocks run, and names the receiving end in the call metadata: `cringle-fabric` (the fabric id) and
`cringle-block-port` (`<block>/<port>` or `<block>/<port>/<index>`), taken from the `remote` object of its blueprint.

**Trust.** The key of a caller is accepted at the handshake if, and only if, a fabric of the receiving engine allows it:
every tether of a fabric whose local end receives and that has a `remote` allows the `fingerprint` of that `remote` as a
sender, until the fabric is removed. These keys are kept in a trust store of their own (`<engineDir>/tether-trust.json`,
emptied when the engine starts), separate from the trust of the daemon and the management server. A call also has to
come from the key that the receiving tether names: a call that names an unknown fabric, block or port, or that comes from
another allowed key, gets the same answer, so that a caller learns nothing about what runs on the engine. A sender
connects to the address and the key of its `remote` and to nothing else; a certificate that does not have this key is
refused. There is no trust on first use.

**Wire mode.** `MESSAGE`, `REQUEST_RESPONSE` and `STREAM` tethers use the typed frames of `wire.md`; a `BYTE_STREAM`
tether uses the `bytes` mode (`BYTES` frames, with `STREAM_OPEN`, `STREAM_CLOSE` and `ERROR` as control frames). Values
are checked against the schema of the port at the receiving end (and, for the values a block sends, at the sending end),
as for a local tether; bytes are not checked.

**Calls.** The server answers a call it accepts with one empty message (the call is established), and a call it refuses
with one `ERROR` frame (`cringle.std/Error`, code `unknown-target`) and the end of the call. A sender is started only
after that: a fabric whose tether cannot connect (not reachable, wrong key, refused) fails to start with a message that
names the address and the expected key. After that, `MESSAGE` frames flow from the sender to the receiver. The receiver
validates a value against the schema of its port (the schema of the remote port is only known here) and queues it like a
value of a local tether: ordering, the bounded buffer and `DROP`/`BUFFER` with `retry` behave as for a local tether, and a
full buffer suspends the sender through the flow control of the connection. A value the receiver cannot take is answered
with an `ERROR` frame: code `validation` (the sender sees a `TetherValidationException`), `delivery` (a
`TetherDeliveryException`, for example because the fabric is not running) or `unsupported`; the sender reports it as a
delivery failure of the tether, as it does for a local tether.

**Requests.** `request` writes a `REQUEST` frame with a new correlation ID and waits. The receiver validates the request,
hands it to the block like a local request and answers with a `RESPONSE` frame of the same ID when the block responds;
the response is validated at the sender against the schema of the port (one schema for request and response). A request
that the receiver cannot deliver (the block is not running, the schema is violated) is answered with an `ERROR` frame of
the same ID, and the sender gets the matching `TetherDeliveryException` or `TetherValidationException`. A response that
does not come within the `requestTimeout` of the tether (default 30 s) ends with a `TetherTimeoutException`. Many requests
can be waiting at once.

**Streams.** `openStream` allocates a stream ID and writes `STREAM_OPEN`; the receiving block gets `StreamOpened` like a
local one. Values flow in both directions as `STREAM_ITEM` frames of that ID, validated like `MESSAGE` values, in order. A
full buffer suspends the writer (no thread is blocked): the receiver stops reading, the flow control of the connection
holds the sender back, and its stream buffer fills. **Closing is for both directions**, unlike a local stream: `close()`
writes `STREAM_CLOSE` and closes the reading end of the same side at once; the other side reads what was sent before the
close and then sees the end of the stream, and its writes fail. (A local stream can be closed in one direction only.)
The frames of all streams of a tether share one call, so a stream whose reader is slow holds back the others of the
same tether.

**Byte streams.** `openByteStream` allocates a stream ID and writes `STREAM_OPEN`; bytes flow in both directions as
`BYTES` frames of that ID (a write is split into frames of at most 256 KiB), in order, with the same backpressure and
closing rules as a stream. Bytes are not checked against a schema.

**Finding the target.** Without `address` in the `remote` object the sender finds the engine of the fabric at run time
(`BindingResolver` and `FabricResolver` in `cringle.engine.tether`). The `fabric` of the `remote` is the *abstract
dependency* of the tether; a `BindingResolver` binds it to a concrete fabric instance at deploy time (the engine binds every
target to the fabric of the same id; a management server and a discovery model with capabilities and priorities are not
defined yet). The concrete fabric id is looked up with `LookupFabric` at the router of the engine
(`RegistryService`, `proto/cringle/router/v1/registry.proto`); the engine entry carries `tether_address`, which every
engine sends when it registers. The answer is kept for 30 seconds (`resolutionTtl`) and is dropped as soon as the
connection to that address ends. The `fingerprint` of the `remote` is still the key that the engine has to present: the
registry names where to connect, not whom to trust. The tether is established at once, also when the target is not in the
registry yet: the fabric of the sender is not held back by a fabric that has not started.

**Supervision and reconnecting.** A connection whose target was found through the registry is kept up. When it ends, or
when `healthFailures` (3) checks in a row find it down, everything that waited for it fails at once (requests, writers,
streams: a `TetherDeliveryException`), the cached address is dropped and the sender looks the fabric up and connects again,
waiting 250 ms before the first new attempt and twice as long after each failure up to 10 s (`backoffStart`,
`backoffCap`). It never gives up while the fabric runs. Meanwhile a message is handled as for a receiver that is not
running: with the delivery policy `BUFFER` it is kept and tried again (`retry` of the tether), with `DROP` it is dropped and
logged; a request or a stream fails at once. The block sees no failover, only these semantics. The checks are a coroutine
of the driver (not of a block) that looks at the state of the connection every `healthInterval` (5 s); the transport pings
a silent peer (HTTP/2 keep-alive, at most every 10 s). A receiver that is slow is not a connection that is down. All times
are options of the driver (`RemoteTetherOptions`), with an injectable clock. A tether with a fixed `address` is not
reconnected, as before.

**Failure.** A connection that is lost, or a call that the other engine ends, fails the waiting senders and every later send
at once with a `TetherDeliveryException` (as a stopping fabric does): so do the waiting requests and writers and the open
streams, on both engines. A fabric that stops tells the senders first (an `ERROR` frame with the code `stopping` and the
ID 0, then the end of the call), so that the exception names the stop and nobody waits for a timeout. The tether is not
reconnected if it has a fixed address: the fabric has to be restarted (see "Supervision and reconnecting" for the others). At-least-once delivery is not part of this.


### Service ports (#177)

A blueprint that provides a service (`package-format.md`, "Services") has a receiving end without a tether of its own:
the `IN` port of the service. The engine of the fabric is told which engines may call its service ports, as public key
fingerprints in the deploy request (`service_callers`) and later with `SetServiceCallers`. Each of these engines is
allowed like the sender of a `remote` tether (trusted by its key, never on first use) and reaches the service ports by
naming them as any remote port, `<fabric>/<block>/<port>`. Several engines can call the same port. A caller that is
removed is refused at its next call, and its open calls end with the error `stopping`. Calls are delivered with the
delivery policy `DROP`.
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

- At-least-once delivery across a lost connection, a discovery model for the binding of abstract dependencies (capabilities, priorities), and the binding in the ManagementServer.
- The filesystem driver: issue #75.
