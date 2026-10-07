# Cringle Wire Format Specification

**Status: proposal.** This document is the single source of truth for the wire format that turns a byte stream (TCP, IPC, cross-engine) into a typed message stream. The wire format is an open point of the architecture (chapters 10 and 12); the rules below are the proposal that the Kotlin codec implements exactly. The wire format is encoding-agnostic: a non-Kotlin engine may pick any payload encoding, both sides agree on the envelope and on whatever the chosen encoding emits.

## 1. Overview

The wire format defines how a sequence of bytes on a transport (TCP, IPC, cross-engine) is split into **frames**, and how each frame is interpreted as a typed message of a tether. It covers:

- **Framing**: how the reader finds the boundary of one frame inside a continuous byte stream.
- **Frame types**: the eight kinds of frame the codec understands (`MESSAGE`, `REQUEST`, `RESPONSE`, `STREAM_OPEN`, `STREAM_ITEM`, `STREAM_CLOSE`, `BYTES`, `ERROR`).
- **Schema reference encoding**: how the schema of a typed frame is identified inside the frame header.
- **Correlation and stream IDs**: how a `RESPONSE` is matched to its `REQUEST`, and how `STREAM_ITEM` frames are matched to their `STREAM_OPEN`.
- **Error frame**: a dedicated frame type that carries a `cringle.std/Error` value.
- **Payload codec extension point**: the interface that turns the payload bytes of a typed frame into a value and back. The first implementation is `JsonPayloadCodec`; others (CBOR, protobuf) plug in later behind the same interface.

The wire format is **encoding-agnostic**: the envelope (length, version, frame type, correlation/stream ID, schema namespace, payload bytes) is fixed, but the payload bytes themselves are produced and consumed by a `PayloadCodec` chosen at runtime. A non-Kotlin engine may pick any payload encoding; both sides agree on the envelope and on whatever the chosen encoding emits.

## 2. Mode

Each tether in the blueprint carries a `mode` field with one of two values:

| Mode | Meaning |
|---|---|
| `typed` | the tether carries schema-checked values; the codec uses the typed frame types (`MESSAGE`, `REQUEST`, `RESPONSE`, `STREAM_OPEN`, `STREAM_ITEM`, `STREAM_CLOSE`, `ERROR`) |
| `bytes` | the tether carries raw bytes; the codec uses the `BYTES` frame type and the control frames that carry no value of a schema: `STREAM_OPEN`, `STREAM_CLOSE` and `ERROR` |

The default mode depends on the tether type:

| Tether type | Default mode |
|---|---|
| `MESSAGE` | `typed` |
| `REQUEST_RESPONSE` | `typed` |
| `STREAM` | `typed` |
| `BYTE_STREAM` | `bytes` |
| `TCP` | `bytes` |

A tether has exactly one mode; the mode is fixed in the blueprint and never changes at runtime. The codec rejects a frame the tether was not declared to carry: a frame with a value (`MESSAGE`, `REQUEST`, `RESPONSE`, `STREAM_ITEM`) on a `bytes`-mode tether, or a `BYTES` frame on a typed tether, raises `WireFormatException`. A `bytes`-mode tether can carry several byte streams over one connection: `STREAM_OPEN` and `STREAM_CLOSE` open and close one, and the stream ID of its `BYTES` frames tells them apart.

## 3. Frame layout

Every frame on the wire has the following byte layout, in order:

```
| u32 BE total length (covers everything after this field) |
| u8    wireFormatVersion (= 1)                            |
| u8    frameType (FrameType code)                         |
| u64   correlationOrStreamId                              |
| u16   schemaNamespaceByteLen (0x0000 => absent)          |
| [schema namespace UTF-8 bytes, exactly that many]        |
| [payload bytes; encoding depends on PayloadCodec]        |
```

Field by field:

- **total length** (`u32` big-endian): the number of bytes that follow this field, that is, the size of the rest of the frame. The reader uses it to find the boundary of one frame inside a continuous byte stream.
- **wireFormatVersion** (`u8`): the version of the wire format, currently `1`. See §10.
- **frameType** (`u8`): the code of the frame type. See §4.
- **correlationOrStreamId** (`u64` big-endian): the correlation ID for `REQUEST`/`RESPONSE`, or the stream ID for `STREAM_OPEN`/`STREAM_ITEM`/`STREAM_CLOSE`. See §6.
- **schemaNamespaceByteLen** (`u16` big-endian): the length in bytes of the schema namespace that follows. The value `0x0000` means the schema namespace is **absent** (used for `BYTES`, `STREAM_OPEN`, `STREAM_CLOSE`).
- **schema namespace** (UTF-8 bytes, exactly `schemaNamespaceByteLen` bytes): the namespace of the schema of the value carried by the frame. See §5.
- **payload** (bytes): the payload of the frame. The encoding depends on the `PayloadCodec` chosen for the tether. See §8.

The reader advances exactly the declared length so a partially framed byte stream never desynchronizes: if the declared length exceeds the bytes available, the reader returns the bytes it has so far alongside the frame for the next call. Trailing bytes after the last complete frame are returned alongside the frame for the next call. All integers in the frame header are big-endian.

## 4. Frame types

The codec understands eight frame types:

| Code | Name | Description |
|---|---|---|
| `0x01` | `MESSAGE` | asynchronous message; payload: canonical JSON of the value |
| `0x02` | `REQUEST` | a request; payload: canonical JSON of the value |
| `0x03` | `RESPONSE` | a response to a request; payload: canonical JSON of the value |
| `0x04` | `STREAM_OPEN` | opens a stream; no payload |
| `0x05` | `STREAM_ITEM` | a stream item; payload: canonical JSON of the value |
| `0x06` | `STREAM_CLOSE` | closes a stream; no payload |
| `0x07` | `BYTES` | raw bytes; payload: literal bytes (not base64) |
| `0x08` | `ERROR` | an error; payload: canonical JSON of `cringle.std/Error` |

`MESSAGE`, `REQUEST`, `RESPONSE`, `STREAM_ITEM` and `ERROR` are typed frames: they carry a value of a schema, the schema namespace is present in the header, and the payload is encoded by the `PayloadCodec` of the tether. `STREAM_OPEN` and `STREAM_CLOSE` are typed control frames: they carry no value, the schema namespace is absent, and the payload is empty. `BYTES` is the frame type of a `bytes`-mode tether: the schema namespace is absent, the payload is the literal bytes of the stream, and the `correlationOrStreamId` is the ID of the stream (see §6). `STREAM_OPEN`, `STREAM_CLOSE` and `ERROR` are allowed on a `bytes`-mode tether too, because they carry no value of a schema.

## 5. Schema reference encoding

The schema of a typed frame is identified by its **namespace** (for example `cringle.std`). The type name is implied by the frame type:

| Frame type | Schema |
|---|---|
| `MESSAGE` | the schema of the port |
| `REQUEST` | the schema of the port |
| `RESPONSE` | the schema of the port (same as the request, see `spec/tether.md`) |
| `STREAM_ITEM` | the schema of the port |
| `ERROR` | `cringle.std/Error` |

The schema namespace is encoded as UTF-8 bytes in the frame header, preceded by its length (`schemaNamespaceByteLen`). The receiving application validates the value against its local `SchemaRegistry` (see `spec/schema.md` §3); the codec does not validate. A frame whose schema namespace does not resolve in the receiving registry is rejected by the driver, not by the codec.

## 6. Correlation and stream IDs

The `correlationOrStreamId` field carries one of two things, depending on the frame type:

- For `REQUEST` and `RESPONSE`, it is the **correlation ID**. The responder echoes the same ID in the `RESPONSE` frame so the requester can match the response to the request.
- For `STREAM_OPEN`, `STREAM_ITEM`, `STREAM_CLOSE` and `BYTES`, it is the **stream ID**. Opening a stream allocates the ID; both ends use the same ID for every `STREAM_ITEM` (or `BYTES` frame) until `STREAM_CLOSE`.
- For `ERROR`, it is the correlation ID or the stream ID of the request or stream that the error concerns, or `0` if it concerns neither (for example a `MESSAGE` that could not be taken, or a call that is refused).

For `MESSAGE`, the field is reserved and set to `0` by the sender; the receiver ignores it.

The codec is **stateless**: it does not allocate, remember or match correlation or stream IDs. The driver owns the ID map and is responsible for allocating IDs, matching responses to requests, and matching stream items to their stream.

## 7. Error frame

The `ERROR` frame carries a value of type `cringle.std/Error` (see `spec/schema.md` §4). The payload is the canonical JSON of that value. The schema namespace is always `cringle.std`; the codec writes it on send and checks it on receive.

Drivers translate an `ERROR` frame to `TetherDeliveryException` or `TetherValidationException` on the receiving side, depending on the `code` field of the error value; the codec does not perform this translation. The codec only frames and unframes the value.

## 8. Payload codec extension point

The payload of typed frames is encoded by a `PayloadCodec`. The codec interface has two operations:

- **encode**: turn a value into the payload bytes of a frame.
- **decode**: turn the payload bytes of a frame back into a value.

The first implementation is `JsonPayloadCodec`, which uses the canonical form per `spec/schema.md` §6 (RFC 8785 with the integer extension). Other codecs (CBOR, protobuf) may plug in later behind the same interface. The wire envelope is encoding-agnostic: the codec is chosen per tether, and both ends must agree on the codec before they exchange frames.

## 9. Length limit

The default maximum frame length is **4 MiB** (4 × 1024 × 1024 = 4 194 304 bytes), configurable on the codec. Oversize frames are rejected with `WireFormatException` **before allocation**: the reader reads the declared length, fails if it exceeds the limit, then allocates and reads the payload. The limit covers the entire frame after the length field (version, frame type, correlation/stream ID, schema namespace, payload).

## 10. Wire format version

The wire format version is a `u8` in the header, currently `1`. Receivers MUST reject an unknown version with `WireFormatException` that names the value (for example `unsupported wire format version 2`). A version mismatch is a configuration error, not a transient failure: the connection cannot be used.

## 11. Byte order

All integers in the frame header are big-endian. Receivers MUST check alignment: a frame whose declared length is not a multiple of the alignment of its fields, or whose declared length is smaller than the fixed header, is rejected with `WireFormatException`. The fixed header (version, frame type, correlation/stream ID, schema namespace length) is 1 + 1 + 8 + 2 = 12 bytes; a frame whose declared length is smaller than 12 is rejected.

## 12. Stream open/close semantics

Opening a stream allocates the `correlationOrStreamId`; both ends use the same ID for every `STREAM_ITEM` until `STREAM_CLOSE`. The codec is stateless; the driver owns the ID map. A `STREAM_ITEM` whose stream ID has not been opened, or whose stream has already been closed, is rejected by the driver, not by the codec. A `STREAM_CLOSE` whose stream ID has not been opened is rejected by the driver, not by the codec.

## 13. References

- `spec/tether.md` — tether types and semantics, delivery guarantees, backpressure.
- `spec/schema.md` — canonical form, standard types, validation, registry.
- `docs/Architecture.md` chapters 10 (Tether) and 12 (Schema) — the architecture this spec implements.
