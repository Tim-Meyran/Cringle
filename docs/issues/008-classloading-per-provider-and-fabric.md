---
id: 008
title: Classloading per provider and fabric instance
milestone: M1
status: open
assignee:
depends_on: [003, 005, 007, 023]
architecture: ["20", "17"]
---

# 008 – Classloading per provider and fabric instance

## Context
The Kotlin engine isolates fabrics and provider dependencies by giving each provider in each fabric instance its own classloader under a narrow parent that contains only the contract interfaces.

## Scope
- `ContractClassLoader` (parent) exposing only contract packages.
- `ProviderClassLoader` per (fabric instance, provider), loading the plugin's JARs, child-first for everything except contract packages (**decided**).
- Lifecycle: create and close loaders with the fabric; ensure no leaks after fabric stop (test with weak references).
- Instantiation of a `BlockProvider` through its loader.

## Out of scope
Untrusted block processes (018), block lifecycle handling (009).

## Design notes
- Isolation guarantees to test: two fabric instances of one blueprint do not share static state; two providers in one blueprint can use different versions of the same library (e.g. commons-collections).
- Document memory and startup cost as noted in chapter 20.

## Acceptance criteria
- [ ] Tests with test-plugin JARs prove both isolation guarantees.
- [ ] Contract classes are identical across loaders (no `ClassCastException`).
- [ ] After closing a fabric, its loaders become collectable.

## Notes / Findings

## Result
