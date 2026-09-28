# Cringle Protocol Buffer Style Guide

This document defines the conventions, formatting rules, and backward-compatibility guidelines for all Protocol Buffer definitions (`.proto`) in the Cringle project.

## 1. File Structure and Organization

### 1.1 Directory Layout
All `.proto` files reside in the root `proto/` directory. The directory hierarchy strictly matches the package name:

```
proto/
└── cringle/
    ├── <domain>/
    │   └── <version>/
    │       └── <filename>.proto
```

Example: `proto/cringle/common/v1/identifiers.proto` matches package `cringle.common.v1`.

### 1.2 File Header & Options
Every `.proto` file MUST start with the standard Apache-2.0 SPDX header and use `proto3` syntax:

```proto
// SPDX-License-Identifier: Apache-2.0
syntax = "proto3";

package cringle.<domain>.<version>;

option java_multiple_files = true;
option java_package = "cringle.<domain>.<version>";
```

## 2. Package Versioning

1. Every package MUST contain a major version component as its final segment (e.g., `v1`, `v2`).
2. Experimental or preview APIs should use alpha/beta versions (e.g., `v1alpha1`, `v1beta1`).
3. Breaking changes require incrementing the major version in a new package (e.g., `v2`).

## 3. Naming Conventions

| Element | Format | Example | Notes |
|---|---|---|---|
| **Files** | `lower_snake_case.proto` | `identifiers.proto`, `heartbeat.proto` | Short, descriptive noun phrase |
| **Packages** | `lower_snake_case` | `cringle.common.v1`, `cringle.registry.v1` | Domain + version |
| **Messages** | `PascalCase` | `EngineHeartbeat`, `CertificateInfo` | Nouns |
| **Fields** | `lower_snake_case` | `engine_id`, `cpu_usage_percent` | Avoid field names repeating message name |
| **Enums** | `PascalCase` | `FabricLifecycleState`, `HashAlgorithm` | Singular noun |
| **Enum Values** | `UPPER_SNAKE_CASE` | `FABRIC_LIFECYCLE_STATE_RUNNING` | Must start with enum type name |
| **Services** | `PascalCase` | `RegistryService`, `ManagementService` | Verb or Noun + Service |
| **RPC Methods** | `PascalCase` | `RegisterEngine`, `SendHeartbeat` | Verb + Noun |

### 3.1 Enum Zero Values
Every enum MUST define a `0` value named `<ENUM_NAME>_UNSPECIFIED = 0`.

```proto
enum HashAlgorithm {
  HASH_ALGORITHM_UNSPECIFIED = 0;
  HASH_ALGORITHM_SHA256 = 1;
  HASH_ALGORITHM_SHA512 = 2;
}
```

## 4. Backward Compatibility Rules

1. **Never change existing field tags (numbers):** Field tags are permanent identifiers on the wire.
2. **Never change field data types:** Changing a type breaks wire compatibility.
3. **Use `reserved` when deleting fields or enum values:**
   ```proto
   message Example {
     reserved 2, 5, 8 to 11;
     reserved "old_field", "deprecated_field";
   }
   ```
4. **New fields must be optional (default proto3 semantics):** The system must gracefully handle missing new fields in old versions.
5. **No enum value reordering or reuse:** Never change integer values of existing enum variants.

## 5. Security and Sensitive Data

1. **No Private Key Material:** Messages representing certificates (`CertificateInfo`) or identity structures must **never** contain private key material, secrets, or unhashed credentials.
2. **Token Handling:** Any authentication tokens sent over gRPC should pass through gRPC metadata interceptors (headers) rather than message payloads where possible, and must never be logged.
