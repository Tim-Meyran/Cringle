# Sample: a shared service used by a second project

The blueprints of two projects, `orders-service` (provides the service `orders`) and `shop` (uses it). The blocks come from
plugins `acme-orders` and `acme-shop` that are not part of this repository: a block `order-store` with an `IN` port `in`
(`MESSAGE`) and a block `checkout` with an `OUT` port `orders` (`MESSAGE`), both with the schema of an order.

How to deploy and bind them, and what happens, is in [`docs/shared-services.md`](../../docs/shared-services.md).
`SamplesTest` (module `packaging`) reads these files, so they stay valid with the format.
