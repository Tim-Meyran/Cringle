---
id: 004
title: Schema system
milestone: M1
status: open
assignee:
depends_on: [001, 003]
architecture: ["12", "9.3"]
---

# 004 – Schema system

## Context
Schemas describe the complex data types transferred over tethers. They are defined per Project and Plugin, resolved by unique namespace IDs, and validated at runtime (chapter 12). The schema definition language and wire format are open points (chapter 25) and must be specified here as a proposal.

## Scope
- Write `spec/schema.md`: Cringle's **own schema definition format** (decided: no reuse of JSON Schema, Protobuf or similar as the definition language; the concrete syntax and carrier are proposed here), primitive and composite types, optional fields, lists, maps, enums, references by namespace ID (`namespace/Name`).
- Kotlin module `schema`: parser, in-memory model, registry with namespace resolution across plugin/project boundaries, runtime validator, and (de)serialization of values to a canonical JSON form.
- Cringle standard schemas (`cringle.std`): primitives, timestamp, bytes, error type.
- Compatibility checks: is schema A assignable to port type B (used later by blueprint validation).

## Out of scope
Binary wire format, cross-process transport (issue 018), WebUI editor.

## Design notes
- Backward compatibility is **not** required (chapter 12); a schema change may require redeploy, so no migration logic here.
- The registry must be usable without an engine (pure library), since Repository and ManagementServer also validate.
- The concrete syntax is a proposal to be reviewed; the decision for an own Cringle format is final.

## Acceptance criteria
- [ ] `spec/schema.md` written with examples.
- [ ] Parser handles valid and invalid definitions with clear error messages.
- [ ] Namespace resolution works across multiple sources; conflicts are reported.
- [ ] Validator has unit tests covering each type and failure mode.

## Notes / Findings

## Result
