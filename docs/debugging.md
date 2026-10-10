# Remote debugging (#323, Architecture 16.4)

The ManagementServer can set **breakpoints on tethers** of a running fabric and show what is held there. It is the answer to the open points of 16.4 (mechanics and buffering), proposed as the simplest reversible solution `[Zu bestätigen]`:

- A **breakpoint** is on a tether, named as the data warehouse names it (`c.out -> s.in`). Every value that is about to be delivered on it is held back **before the receiving block gets it**, after the schema check. A held value shows the *output of the sender* (`from`) and the *input of the receiver* (`to`), its kind (`message`, `request`, `response`, `stream-item`, `bytes`, ...) and its payload (JSON text, shortened to 4096 characters; bytes as a hex dump of the first 32).
- The fabric is **paused at that tether**, not frozen: the other blocks go on. The sender of the tether goes on until the bounded buffer of the tether (`bufferCapacity`, default 64) is full, then it waits; this buffer is the buffering of the messages of a paused fabric, so nothing is dropped and no second queue exists. A request waits for its response and may run into its timeout while it is held.
- **Resume** releases the held values: all, or with `--one` only the oldest (the breakpoint stays, so the next value is held again: **step**). Limited to one tether if one is named. **Removing** a breakpoint releases what it held. Stopping the fabric releases everything.
- Breakpoints live in the engine, per fabric: they are gone when the fabric is stopped or deployed again.

Use: `cringle debug break <fabric> <tether> on|off`, `cringle debug state <fabric>`, `cringle debug resume <fabric> [<tether>] [--one]`; the WebUI has the page *Debugger* (`webui.md`). All need `OPERATE` for the fabric (an operator, or a scoped role for the machine, project or fabric); the held values can contain data, so reading them needs the same right. gRPC: `SetBreakpoint`, `GetDebugState`, `ResumeFabric` on the ManagementServer and the engine (`engine_management.proto`, `management.proto`).

Not included: breakpoints on blocks (only tethers), conditions, changing a held value, and breakpoints that survive a redeploy.
