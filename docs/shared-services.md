# Shared services

A shared service is a blueprint that is deployed on its own and used by blueprints of other projects through tethers
(Architecture chapter 13). The service names the port it offers, a consumer names the service, and a ManagementServer binds
the two when it deploys. The tether is an ordinary tether between engines (`spec/tether.md`) over mutual TLS.

## In the blueprints

The service lists what it provides (`spec/package-format.md`, "Services"):

```json
"provides": [ { "service": "orders", "block": "store", "port": "in", "type": "MESSAGE" } ]
```

A consumer names the service instead of an engine:

```json
{ "type": "MESSAGE", "from": { "block": "checkout", "port": "orders" }, "remote": { "service": "orders" } }
```

The two projects of the sample are in [`samples/shared-service`](../samples/shared-service).

## Deploying and binding

```bash
cringle deploy orders-service                 # the service; its fabric is orders-service-service-1
cringle bind shop orders orders-service-service-1
cringle deploy shop                           # fails if "orders" is not bound
```

`cringle bind <project> <service> <fabric>...` binds the service dependency of a project to the fabrics that provide it, the
preferred one first. `cringle bindings [project]` lists, `cringle unbind <project> <service>` removes. The service is
deployed before its consumers; a consumer can be deployed before or after as long as the service fabric exists.

What the deploy does (`docs/management-server.md`):

- the consumer gets the fabric, port and **key fingerprint** of the engine that runs the service; the registry of the router
  finds the engine's address (also on another machine, if the routers trust each other, `docs/trust.md`);
- the service is told which engines may call it (the engines of the projects bound to it); everything else is refused at the
  TLS handshake;
- both lists are kept up to date when projects are deployed, undeployed or bound again, and restored by `recover`.

## Failover

Bind several fabrics for the same service, for example `cringle bind shop orders orders-service-service-1
orders-backup-service-1`. The consumer calls the first one that answers, moves to the next when it fails (a refused or lost
connection), and goes back to the best one when it answers again. A new binding reaches the running consumers without a
redeploy. Calls that were on the way to an instance when it failed are not repeated on the next one; use the delivery policy
`BUFFER` on the consumer's tether to keep messages while no instance answers. Selection rules other than the order of the list,
priorities and capabilities are not decided yet (Architecture chapter 13.3, `[Offen]`).

## Two machines

Each machine has a daemon with its router. The ManagementServer manages both machines (`cringle machine add`). For an engine on
one machine to find a service on the other, the routers add and trust each other (`cringle trust add`, `docs/trust.md`);
the engines of both machines then see each other's fabrics. The test `SharedServiceEndToEndTest` does exactly this: the service
and one consumer on the first machine, a backup service and a second consumer on the second; when the service fails both
consumers move to the backup.

## Limits

- A binding names fabric ids, not roles or labels, and a change to it applies to running consumers but a consumer that is
  deployed again takes the current binding.
- A caller can reach all service ports of a fabric.
- Messages in flight when an instance fails are lost unless the tether is `BUFFER` and the failure is noticed before the send.
