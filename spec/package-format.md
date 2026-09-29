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
`name` (unique per block), `direction` (`IN` or `OUT`), `tetherTypes` (non-empty, values `REQUEST_RESPONSE`,
`MESSAGE`, `STREAM`, `BYTE_STREAM`), `schema` and optional `varArg` (boolean, default `false`). This is exactly
the model of `cringle.contract.BlockDefinition`. Within a project a block is addressed as
`<pluginName>/<blockName>`.

## 5. Fabric config and blueprint

A fabric config entry is `{"blueprint": <name>, "instances": <n>, "roles": [...], "labels": {...}}`: run `n`
(default 1, at least 1) copies of the blueprint on engines that have all listed logical roles and labels.
Fabric configs never name concrete engines. This is the **only** place where placement is expressed.

A blueprint is `{"name", "blocks", "tethers"}`. It has no version of its own and no engine, role or label
fields, so it always runs entirely inside one engine (chapter 9.1); any such key is rejected as unknown.

- Block: `id` (unique within the blueprint), `block` (`pluginName/blockName`), optional `config` (object,
  default `{}`), optional `isolation` (`SHARED` default, or `PROCESS`), and `varArgCounts` (object from VarArg
  port name to a fixed size of at least 0).
- Tether: `type` (a tether type), `from` and `to`. An endpoint is `{"block": <id>, "port": <name>}` plus `"index"`
  for VarArg ports. The type is fixed here and never changes at runtime. Optional `delivery` (`DROP` default, or
  `BUFFER`) says what happens when the receiving block is not running: `DROP` drops and logs, `BUFFER` keeps the
  value in the tether's bounded buffer until the receiver runs again (see `tether.md`).

## 6. Semantic validation

Validators return a list of problems, each with a path and a message, and never stop at the first one. Plugin
validation checks that every schema reference of every block resolves in the plugin's own schemas, the schemas of its
dependencies and `cringle.std`, that schema documents parse and do not conflict, that block, provider and driver
entries are unique, and that a plugin with blocks lists a provider. Project validation is given the already
resolved plugins and checks, per blueprint:

1. every `block` exists; block ids are unique;
2. `varArgCounts` has an entry for every VarArg port of the block and no entry for any other name;
3. `config` is valid for the block's `configSchema` (paths like `$.blocks[1].config.limit`); a block without a
   `configSchema` takes no configuration;
4. tether endpoints exist; `from` is an OUT port and `to` an IN port;
5. the tether `type` is in the `tetherTypes` of both ports;
6. the `schema` of the OUT port is assignable to the `schema` of the IN port (`schema.md`, section 7);
7. `index` is present and below the `varArgCounts` size for a VarArg port, and absent for any other port;
8. every fabric config names an existing blueprint.

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
