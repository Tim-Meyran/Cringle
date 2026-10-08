# Cringle Package Format Specification

Status: format version 1. Language independent; the reference implementation is the Kotlin module `packaging`
(`cringle.packaging`). Architecture: chapters 8 and 15. Schema documents are defined in `schema.md`.

## 1. Overview

Projects and plugins are plain ZIP files. Each has one JSON manifest at the root, and further JSON files, JARs
and static files below fixed folders. Everything that is JSON in a package is UTF-8 text, must be a single
JSON value without duplicate object keys, and is read **strictly**: an unknown key is an error, never ignored.

## 2. ZIP layout

| Entry | Project | Plugin | Meaning |
|---|---|---|---|
| `cringle-project.json` | required | forbidden | Project manifest |
| `cringle-plugin.json` | forbidden | required | Plugin manifest |
| `blueprints/<name>.json` | one per listed blueprint | not allowed | Blueprints |
| `schemas/<file>.json` | one per listed schema | one per listed schema | Schema documents (`schema.md`) |
| `lib/<file>.jar` | not allowed | one per listed jar | Classes for the plugin's class loader |
| `binaries/**` | optional, any files | optional, any files | Static resources, managed by Cringle |

Rules:

- Every file below `blueprints/`, `schemas/` and `lib/` must be listed in the manifest, and every listed file
  must exist. Files below `binaries/` are not listed. No other entries are allowed. Directory entries are ignored.
- Entry names are relative, use `/`, and have no empty, `.` or `..` segments, no backslash, no `:` and no NUL. A
  reader rejects the whole package when one entry name breaks this rule (zip-slip protection); an extractor must
  additionally check that the resolved path stays inside the target directory, must never overwrite existing
  files, and must cap the total number of extracted bytes.
- Blueprint files are named after the blueprint: `blueprints/<name>.json` holds the blueprint `<name>`.
- Entry names within a package are unique.

## 3. Manifests

Common keys: `format` (integer, must be `1`), `kind` (`"project"` or `"plugin"`, must match the manifest
file), `name`, `version`, `dependencies`. Missing optional keys mean empty.

- **name**: `[a-z][a-z0-9]*([.-][a-z0-9]+)*`. Names are unique per kind in a repository.
- **version**: semantic version `MAJOR.MINOR.PATCH` (no leading zeros) with optional `-prerelease` identifiers.
- **dependencies**: object from package name to a version range string. The range is npm-style and only checked
  to be non-blank here; its syntax and resolution belong to the versioning specification (issue #6).

Package names, block names and blueprint names use the `name` grammar above. Names that become a file or directory
name of a fabric, and references to such names, use the **identifier** grammar below, which is looser but excludes
everything that could leave a directory:

- **identifier**: `[A-Za-z0-9]([A-Za-z0-9._-]{0,62}[A-Za-z0-9])?` — 1 to 64 characters from letters, digits, `.`,
  `-` and `_`, never starting or ending with a separator.
- Not allowed are the names Windows reserves for files and directories (`aux`, `con`, `nul`, `prn`, `com1` to
  `com9`, `lpt1` to `lpt9`), whatever the extension: Windows refuses `con.txt` just like `con`.

Identifiers are the block `id` and the `block` and `port` of a tether endpoint in a blueprint, the port `name` of a
block definition, and the `blueprint` a fabric config refers to. They are rejected when a package is read and again
when it is validated, with the path of the offending value and a message; the engine additionally checks every
name and version of a deploy request against the same rules before it builds a path, and refuses a path that would
leave the directory it belongs to.

### 3.1 Plugin manifest

| Key | Type | Meaning |
|---|---|---|
| `providers` | string[] | Class names of `BlockProvider` implementations |
| `drivers` | string[] | Class names of `Driver` implementations |
| `blocks` | object[] | Block definitions, see section 4 |
| `libs` | string[] | Entry names `lib/*.jar` |
| `schemas` | string[] | Entry names `schemas/*.json` |
| `processors` | object | Optional `update` and `downgrade` class names |

### 3.2 Project manifest

| Key | Type | Meaning |
|---|---|---|
| `blueprints` | string[] | Entry names `blueprints/<name>.json` |
| `schemas` | string[] | Entry names `schemas/*.json` |
| `fabrics` | object[] | Fabric config, see section 5 |

Projects and plugins have **no** JAR list: only plugins carry code.

## 4. Block definition

Object with keys `name` (block name, same grammar as package names), `schemas` (schema references the block
uses, `namespace/Name`), `ports`, `requiredDrivers` (driver ids, unique) and optional `configSchema`. A port has
`name` (identifier grammar of section 3, unique per block), `direction` (`IN` or `OUT`), `tetherTypes` (non-empty,
values `REQUEST_RESPONSE`, `MESSAGE`, `STREAM`, `BYTE_STREAM`), `schema` and optional `varArg` (boolean, default
`false`). This is exactly the model of `cringle.contract.BlockDefinition`. Within a project a block is addressed as
`<pluginName>/<blockName>`.

## 5. Fabric config and blueprint

A fabric config entry is `{"blueprint": <name>, "instances": <n>, "roles": [...], "labels": {...}}`: run `n`
(default 1, at least 1) copies of the blueprint on engines that have all listed logical roles and labels.
Fabric configs never name concrete engines. This is the **only** place where placement is expressed.

A blueprint is `{"name", "blocks", "tethers"}` and optionally `"provides"` (see "Services" below); its `name` uses the package-name grammar of section 3. It has no
version of its own and no engine, role or label fields, so it always runs entirely inside one engine
(chapter 9.1); any such key is rejected as unknown.

- Block: `id` (unique within the blueprint), `block` (`pluginName/blockName`), optional `config` (object,
  default `{}`), optional `isolation` (`SHARED` default, or `PROCESS`), and `varArgCounts` (object from VarArg
  port name to a fixed size of at least 0).
- Tether: `type` (a tether type), `from` and `to`. An endpoint is `{"block": <id>, "port": <name>}` plus `"index"`
  for VarArg ports. The type is fixed here and never changes at runtime. Optional `delivery` (`DROP` default, or
  `BUFFER`) says what happens when the receiving block is not running: `DROP` drops and logs, `BUFFER` keeps the
  value in the tether's bounded buffer until the receiver runs again (see `tether.md`). A `TCP` tether needs `"port"` (1 to 65535) and supports only `DROP`; no other type may set `port`.
- Per-tether options, all optional and falling back to the engine defaults: `bufferCapacity` (integer >= 1, the
  bounded buffer of this tether), `requestTimeout` (milliseconds > 0, how long a `REQUEST_RESPONSE` request waits),
  and `retry` (object, only with `delivery` `BUFFER`): `maxAttempts` (integer >= 1, default unlimited),
  `backoffMs` (integer > 0, default 50), `backoff` (`FIXED` default, or `EXPONENTIAL`) and `maxBackoffMs`
  (integer > 0, default 5000, the cap of `EXPONENTIAL`). A `retry` object on a `DROP` tether is rejected.
- A `SERIAL` tether is a raw byte transport to a serial device and needs a `serial` object: `device` (string,
  required), `baudRate` (integer > 0, default 9600), `dataBits` (5 to 8, default 8), `parity` (`NONE` default,
  `EVEN` or `ODD`) and `stopBits` (1 or 2, default 1). It supports only `DROP` and carries no schema. Only a
  `SERIAL` tether may set `serial`.
- `record` (optional object) marks the tether for recording in the data warehouse of its engine: `{"maxAge": <milliseconds>,
  "maxBytes": <bytes>}`, both optional and positive, are the retention of the recorded partition; `{}` records without limits. Only
  typed tethers (`MESSAGE`, `REQUEST_RESPONSE`, `STREAM`) can be recorded.
- A tether to a port on **another engine** has one local endpoint and a `remote` object instead of the other one.
  The local endpoint is `from` (an `OUT` port) when the remote end receives, and `to` (an `IN` port) when it sends;
  the missing endpoint is omitted. `remote` is `{"fingerprint", "fabric", "block", "port"}` plus `"address"` and
  `"index"` for a VarArg port. `address` is optional, `host:port` (port 1 to 65535; an IPv6 host is written in brackets;
  without it the engine of the `fabric` is found at run time through the registry of the router, see `tether.md`),
  `fingerprint` is the SHA-256 of the public key of the remote engine as 64 lowercase hex characters (the remote
  engine is trusted by this key only, never on first use), and `fabric`, `block` and `port` name the remote port in
  the blueprint that is deployed there, with the identifier grammar of section 3; both sides have to agree on those
  names out of band. Only `MESSAGE`, `REQUEST_RESPONSE`, `STREAM` and `BYTE_STREAM` tethers may have a `remote`:
  `TCP` and `SERIAL` tethers are local resources. Both delivery policies are allowed. This is the one place where a
  blueprint names another engine; it does not add engine, role or label fields to the blueprint. How the tether is
  carried, and which types an engine carries, is in `tether.md` ("Tethers between engines"). On the receiving side
  `address` is not used for a connection (the sender connects); `fingerprint` is the key of the sending engine that the
  receiving fabric allows.

  ```json
  { "type": "MESSAGE", "from": { "block": "source", "port": "out" },
    "remote": {
      "address": "10.0.0.7:7443",
      "fingerprint": "ab12ab12ab12ab12ab12ab12ab12ab12ab12ab12ab12ab12ab12ab12ab12ab12",
      "fabric": "shop", "block": "sink", "port": "in"
    } }
  ```

### Services (shared services, Architecture chapter 13)

A blueprint can offer ports to the tethers of other projects as **services**, and a tether can name such a service
instead of a concrete engine:

- `"provides": [{"service": <name>, "block": <id>, "port": <name>, "type": <tether type>}, ...]` (optional, default
  none) at the top of the blueprint. `service` follows the package-name grammar of section 3 and is unique within the
  blueprint; `block` and `port` name an `IN` port of a block of this blueprint. `type` (optional) is the tether type
  of the calls to the port: `MESSAGE`, `REQUEST_RESPONSE`, `STREAM` or `BYTE_STREAM`, and one that the port supports.
  Without `type` the port must support exactly one of these types, which is then the type of the service; a port with
  several needs `type`. A service that is offered with two types is two entries with two names.
- An **abstract** `remote` is `{"service": <name>}` and nothing else (no `address`, `fingerprint`, `fabric`,
  `block`, `port`). It replaces the concrete `remote`; a tether has one of the two forms, never both. The local
  endpoint and the rules for the tether type are the same as for a concrete `remote`.

The abstract form is design time only: a deploy binds it to a concrete instance (ManagementServer, chapter 13.2). An
engine refuses a fabric that still has an unbound service tether.

  ```json
  { "type": "MESSAGE", "from": { "block": "source", "port": "out" }, "remote": { "service": "orders" } }
  ```

## 6. Semantic validation

Validators return a list of problems, each with a path and a message, and never stop at the first one. Plugin
validation checks that every schema reference of every block resolves in the plugin's own schemas, the schemas of its
dependencies and `cringle.std`, that schema documents parse and do not conflict, that block, provider and driver
entries are unique, that a plugin with blocks lists a provider, and that every block name, port name and package
name follows the grammar of section 3. Project validation is given the already resolved plugins and checks, per
blueprint:

1. every `block` exists; block ids are unique;
2. `varArgCounts` has an entry for every VarArg port of the block and no entry for any other name;
3. `config` is valid for the block's `configSchema` (paths like `$.blocks[1].config.limit`); a block without a
   `configSchema` takes no configuration;
4. tether endpoints exist; `from` is an OUT port and `to` an IN port; a tether has `from` and `to`, or exactly one of
   them and a `remote` (and nothing else is valid);
5. the tether `type` is in the `tetherTypes` of both ports;
6. the `schema` of the OUT port is assignable to the `schema` of the IN port (`schema.md`, section 7);
7. `index` is present and below the `varArgCounts` size for a VarArg port, and absent for any other port;
8. every fabric config names an existing blueprint;
9. every block id, port name and blueprint reference follows the identifier grammar of section 3;
10. `bufferCapacity` is at least 1 and `requestTimeout` is positive;
11. `retry` is only set with `BUFFER`, `maxAttempts` is at least 1, and `backoffMs` and `maxBackoffMs` are positive;
12. `serial` is only set on a `SERIAL` tether, which needs one, and its `baudRate`, `dataBits`, `parity` and
    `stopBits` are in range; a `SERIAL` tether supports only `DROP`;
13. a `remote` is only set on a `MESSAGE`, `REQUEST_RESPONSE`, `STREAM` or `BYTE_STREAM` tether; its `address`, if
    given, is `host:port` with a port from 1 to 65535, its `fingerprint` is 64 lowercase hex characters, `fabric`, `block` and
    `port` follow the identifier grammar and `index` is not negative. The local port of such a tether is checked as
    for a local tether (existence, direction, tether type, `index`); the schema of the remote port cannot be checked
    here and is checked when the tether connects;
14. a `remote` with a `service` has a name in the package-name grammar and no other key; every `provides` entry has
    a valid service name that is unique in the blueprint and names an existing `IN` port of an existing block; its `type`
    is a type that can end on another engine and that the port supports, and is given if the port supports several.

Validation reports all of these together; reading a package reports the first violation it meets, because parsing
stops there.

## 7. Hash

The hash of a package is the SHA-256 of the bytes of the ZIP file, written as 64 lowercase hex characters
(comparison ignores case). It is stored by the repository and checked after every download; it is not part of the
package. Writers produce **deterministic** ZIPs (entries sorted by name, fixed timestamp 1980-01-01 00:00, no extra
fields), so rebuilding the same content gives the same hash.

## 8. Full example

Plugin `acme-orders` 1.2.0, with `schemas/orders.json`:

```json
{
  "namespace": "acme.orders",
  "types": {
    "Order": { "record": { "id": "cringle.std/String", "total": "cringle.std/Int" } },
    "SinkConfig": { "record": { "limit": "cringle.std/Int", "label": { "optional": "cringle.std/String" } } }
  }
}
```

`cringle-plugin.json`:

```json
{
  "format": 1,
  "kind": "plugin",
  "name": "acme-orders",
  "version": "1.2.0",
  "dependencies": { "cringle-core": "^1.0.0" },
  "providers": ["com.acme.orders.OrdersProvider"],
  "drivers": [],
  "blocks": [
    {
      "name": "order-source",
      "schemas": ["acme.orders/Order"],
      "ports": [
        { "name": "out", "direction": "OUT", "tetherTypes": ["MESSAGE", "STREAM"], "schema": "acme.orders/Order" }
      ],
      "requiredDrivers": []
    },
    {
      "name": "order-sink",
      "schemas": ["acme.orders/Order"],
      "ports": [
        { "name": "in", "direction": "IN", "tetherTypes": ["MESSAGE"], "schema": "acme.orders/Order" },
        { "name": "replicas", "direction": "IN", "tetherTypes": ["MESSAGE"], "schema": "acme.orders/Order", "varArg": true }
      ],
      "requiredDrivers": ["logging"],
      "configSchema": "acme.orders/SinkConfig"
    },
    {
      "name": "text-sink",
      "ports": [
        { "name": "in", "direction": "IN", "tetherTypes": ["MESSAGE"], "schema": "cringle.std/String" }
      ]
    }
  ],
  "libs": ["lib/acme-orders.jar"],
  "schemas": ["schemas/orders.json"],
  "processors": { "update": "com.acme.orders.Migrate", "downgrade": "com.acme.orders.Rollback" }
}
```

Project `shop` 0.3.1, `cringle-project.json`:

```json
{
  "format": 1,
  "kind": "project",
  "name": "shop",
  "version": "0.3.1",
  "dependencies": { "acme-orders": "~1.2.0" },
  "blueprints": ["blueprints/main.json"],
  "schemas": [],
  "fabrics": [
    { "blueprint": "main", "instances": 2, "roles": ["edge"], "labels": { "zone": "a" } }
  ]
}
```

`blueprints/main.json`:

```json
{
  "name": "main",
  "blocks": [
    { "id": "source", "block": "acme-orders/order-source" },
    {
      "id": "sink",
      "block": "acme-orders/order-sink",
      "config": { "limit": 10 },
      "isolation": "PROCESS",
      "varArgCounts": { "replicas": 2 }
    }
  ],
  "tethers": [
    { "type": "MESSAGE", "from": { "block": "source", "port": "out" }, "to": { "block": "sink", "port": "in" } },
    { "type": "MESSAGE", "from": { "block": "source", "port": "out" }, "to": { "block": "sink", "port": "replicas", "index": 1 } }
  ]
}
```

The blueprint is valid against the plugin. Typical errors, by rule of section 6: `"block": "acme-orders/nope"`
(1, `$.blocks[0].block`); removing `varArgCounts` (2); `"limit": "ten"` (3, `$.blocks[1].config.limit`);
`"port": "inn"` (4); `"type": "STREAM"` on the `in` port (5, `port 'in' does not support STREAM`); a tether into
`text-sink` (6); `"index": 2` with size 2 (7, `index 2 is outside 0 until 2`).
