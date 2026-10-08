# Observability

What an operator can see of a running Cringle (Architecture chapter 16). This page grows with the M7 issues (#150).

## Metrics (#187)

Every engine answers `GetMetrics` (engine management API) with the numbers below; counters are cumulative since the fabric was
created, a reader computes rates.

| Level | Numbers |
|---|---|
| Engine | CPU load of the process (0 to 1, -1 if unknown), heap used and maximum, thread count |
| Fabric | CPU time of the fabric's thread in nanoseconds (-1 if unknown), errors (blocks and tethers) |
| Block | errors: crashes and failed starts, restarts included |
| Tether | messages (values that crossed it: messages, requests, responses, stream openings and items), bytes (tethers of bytes), errors (validation and delivery failures) |

Memory is measured per engine only, CPU per engine and per fabric: the JVM cannot attribute either to a block that shares the
thread and heap of its fabric. Per-block CPU and memory would need isolated blocks (#18). The ManagementServer reads the numbers
(`GetMetrics`, `cringle metrics`, #191) and the heartbeat carries a few of them to the router (#190).
