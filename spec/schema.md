# Cringle Schema Specification

This document specifies the schema system of Cringle: the format in which types are defined, how they are named and resolved, what values of these types look like, their canonical form, and when one type may be used where another is expected. It is language-independent; every engine implementation must behave as described here (Architecture, chapter 12 and principle 1).

> **Status: proposal.** Everything in this document is `[Zu bestätigen]`, except the decisions recorded in Architecture chapter 12 and `docs/decisions.md`: Cringle uses its **own** schema format (no reuse of JSON Schema, Protobuf or similar as the definition language), types are resolved by unique namespace IDs, and values are checked at runtime. The concrete syntax below is a proposal to be reviewed. The binary wire format across process boundaries is an open point and **not** part of this document.

## 1. Overview

- A **schema document** defines named types in one **namespace**.
- Types are **records** (fields), **enums** and the **standard types** of the reserved namespace `cringle.std`.
- Every type is addressed by a **reference** `namespace/Name`. Ports of blocks refer to their type this way.
- A **registry** collects documents from any number of sources (plugins, projects) and resolves references across them.
- **Values** of a type are JSON values. The **canonical form** of a value is RFC 8785.

## 2. Schema documents

A schema document is a JSON text whose root is an object with exactly the keys `namespace` and `types`. Unknown keys are errors. Object keys must be unique everywhere in the document.

```json
{
  "namespace": "acme.orders",
  "types": {
    "Status": { "enum": ["NEW", "PAID", "SHIPPED"] },
    "Item":   { "record": { "sku": "cringle.std/String", "quantity": "cringle.std/Int" } },
    "Order":  { "record": {
      "id": "cringle.std/String",
      "status": "Status",
      "items": { "list": "Item" },
      "labels": { "map": "cringle.std/String" },
      "comment": { "optional": "cringle.std/String" }
    } }
  }
}
```

### 2.1 Names

| What | Pattern |
|---|---|
| Namespace | one or more segments separated by `.`, each `[a-z][a-z0-9]*` |
| Type name | `[A-Z][A-Za-z0-9]*` |
| Field name | `[a-z][A-Za-z0-9]*` |
| Enum value | `[A-Z][A-Z0-9_]*` |

The namespace `cringle.std` is reserved for the standard types and cannot be used by a document. `types` may be empty.

### 2.2 Type definitions

A type definition is an object with exactly one of:

- `enum`: a non-empty array of unique enum values.
- `record`: an object from field name to type expression. A record may have no fields.

### 2.3 Type expressions

A type expression is one of:

- a **reference string**: `Name` (a type of the same namespace) or `namespace/Name` (split at the last `/`);
- `{"list": E}`: a list of values of type expression `E`;
- `{"map": E}`: a map from string keys to values of type expression `E`;
- `{"optional": E}`: the field may be absent. `optional` is only allowed as the **outermost** expression of a record field; it cannot appear inside `list`, `map` or another `optional`.

The wrapper objects have exactly one key. There are no imports and no shadowing: an unqualified name always means the same namespace.

## 3. Registry

A registry holds documents and resolves references.

- It always contains the standard types (section 4).
- A namespace exists **once** per registry. Adding a second document with the same namespace is a **conflict** and is rejected; documents are never merged. The error names the namespace and the origins of both documents.
- A reference `namespace/Name` resolves if a document with that namespace defines `Name`. Resolution works across documents from different sources.
- The registry reports **problems**, each with the origin and the JSON path of the offending element:
  - a reference that cannot be resolved;
  - a cycle through **required** fields: records that require each other (or themselves) through fields whose type is a plain reference can never be constructed. Cycles through `list`, `map` or `optional` are allowed, so recursive types such as trees are possible.

## 4. Standard types `cringle.std`

Primitives are ordinary named types, so a port can refer to them like to any other type. They cannot be defined in documents.

| Type | Meaning |
|---|---|
| `cringle.std/String` | a string |
| `cringle.std/Boolean` | `true` or `false` |
| `cringle.std/Int` | a signed 64-bit integer |
| `cringle.std/Double` | a finite binary64 number |
| `cringle.std/Bytes` | bytes |
| `cringle.std/Timestamp` | a point in time, UTC, millisecond precision |
| `cringle.std/Empty` | a record without fields, for messages that carry no data |
| `cringle.std/Error` | a record: `code: String`, `message: String`, `details: optional map of String` |

## 5. Values

Values are JSON values.

| Type | JSON value |
|---|---|
| `String` | a string |
| `Boolean` | `true` or `false` |
| `Int` | a JSON integer within the signed 64-bit range: no fraction, no exponent (`1.0` and `1e2` are not integers) |
| `Double` | a JSON number that is finite as a binary64 value (`1e999` is invalid) |
| `Bytes` | a string in base64 (RFC 4648 section 4, standard alphabet, **with** padding, no whitespace, no non-zero padding bits) |
| `Timestamp` | a string `YYYY-MM-DDThh:mm:ss.mmmZ`: UTC (`Z`, no offsets), exactly three fractional digits, a valid calendar date and time (no `24:00`, no leap seconds) |
| enum | a string, one of the declared values |
| record | an object with exactly the declared fields; optional fields may be absent; unknown fields are errors |
| `list` | an array |
| `map` | an object; the keys are the map keys |

`null` is never a valid value, not even for an optional field: an absent optional field is expressed by leaving it out.

### 5.1 Validation errors

Validation returns every problem found. Each error has a **path** into the value (`$` is the root, `.name` a field or map key, `[i]` a list index; keys that are not simple identifiers are written `["a key"]`) and a message. Validating against a type that does not exist yields a single error at `$`. A missing required field is reported at the path of the record that lacks it.

## 6. Canonical form

The canonical form of a value is its serialization according to **RFC 8785** (JSON Canonicalization Scheme): UTF-8, no insignificant whitespace, object keys sorted by UTF-16 code units at every level, numbers as ECMAScript prints them.

One extension: an **integer literal that fits into a signed 64-bit number is written exactly**. RFC 8785 would round integers beyond 2^53 to a double (`9007199254740993` would become `9007199254740992`); for values of type `Int` that must not happen. Integers outside the 64-bit range and all numbers with a fraction or an exponent follow RFC 8785 (for example `-0` becomes `0`, `4.50` becomes `4.5`, `1E30` becomes `1e+30`).

Because valid `Bytes` and `Timestamp` values already have exactly one spelling, canonicalization does not need the schema. Two values are equal if and only if their canonical forms are equal.

## 7. Assignability

A value of type `A` may be used where type `B` is expected if and only if both references resolve and they are the **same namespace ID** (`A == B`). The check is nominal: two record types with identical fields but different IDs are **not** assignable to each other. Structural rules are deliberately not part of this version and can be added later without changing the meaning of the nominal rule.

## 8. Worked examples

### 8.1 A valid document

The document in section 2 is valid. It defines the types `acme.orders/Status`, `acme.orders/Item` and `acme.orders/Order`. Inside the document, `Status` and `Item` are unqualified references to its own namespace; `cringle.std/String` is qualified.

### 8.2 Values and their canonical form

Valid `acme.orders/Order` value (the field `comment` is optional and absent):

```json
{ "labels": {"vip": "yes"}, "id": "o-1", "status": "NEW",
  "items": [ {"sku": "a", "quantity": 1}, {"quantity": 9007199254740993, "sku": "b"} ] }
```

Its canonical form:

```json
{"id":"o-1","items":[{"quantity":1,"sku":"a"},{"quantity":9007199254740993,"sku":"b"}],"labels":{"vip":"yes"},"status":"NEW"}
```

Invalid values for `acme.orders/Order`, with the errors an implementation reports:

```json
{ "id": "o-1", "status": "PAYED",
  "items": [ {"sku": "a", "quantity": 1}, {"sku": "b", "quantity": 2}, {"sku": 7, "quantity": "3"} ],
  "labels": {"vip": 1} }
```

| Path | Message (wording may differ) |
|---|---|
| `$.status` | unknown enum value `PAYED`, expected one of NEW, PAID, SHIPPED |
| `$.items[2].sku` | expected a string, got a number |
| `$.items[2].quantity` | expected an integer, got a string |
| `$.labels.vip` | expected a string, got a number |

Further examples: `{"id":"o"}` is missing required fields (reported at `$`); `"comment": null` is invalid (`$.comment`); `{"code":"E1","message":"boom"}` is a valid `cringle.std/Error`; `"2026-09-29T10:15:30Z"` is not a valid `Timestamp` (no fractional digits), `"2026-09-29T10:15:30.123Z"` is.

### 8.3 An invalid document and its errors

```json
{
  "namespace": "Acme",
  "types": {
    "order": { "record": {} },
    "Level": { "enum": ["LOW", "HIGH", "LOW"] },
    "Task":  { "record": { "tags": { "list": { "optional": "cringle.std/String" } } } }
  }
}
```

An implementation stops at the first problem it finds and reports its path. Fixing them one after the other gives these errors:

| Path | Problem |
|---|---|
| `$.namespace` | invalid namespace `Acme` (must be lower case) |
| `$.types.order` | invalid type name `order` (must start with an upper-case letter) |
| `$.types.Level.enum[2]` | duplicate enum value `LOW` |
| `$.types.Task.record.tags.list` | `optional` is only allowed as the outermost type of a record field |

Other invalid documents: malformed JSON (`$`), a missing `namespace` or `types` key (`$`), an unknown top-level key (`$.extra`), neither or both of `enum` and `record` in a definition, an empty `enum`, an object type expression with two keys, the namespace `cringle.std`, and duplicate object keys (reported at the path of the object that contains them).
