GOAL: Implement the delivery policy `BUFFER` for tethers in `TetherNetwork`: when the receiving block is not running, a buffered tether keeps the value and retries until the receiver runs again, instead of dropping it.

CONTEXT: Module `engine` (Kotlin, JDK 21, Gradle, coroutines). The file `kotlin/engine/src/main/kotlin/cringle/engine/tether/TetherNetwork.kt` delivers values from a bounded queue per connection to a `TetherDeliverer`. Today a failed delivery is always reported: a request is completed exceptionally with `TetherDeliveryException`, and `onDeliveryFailure(info, failure)` is called; the value is lost ("drop"). The blueprint model (module `packaging`, already done, do not change it) now has `enum class DeliveryPolicy { DROP, BUFFER }` and `TetherDef(type, from, to, delivery: DeliveryPolicy = DROP)`. When the receiving block is not running, the deliverer throws `cringle.engine.fabric.FabricException`.

FILES (touch only these):
- `kotlin/engine/src/main/kotlin/cringle/engine/tether/TetherNetwork.kt`
- `kotlin/engine/src/test/kotlin/cringle/engine/tether/DeliveryPolicyTest.kt` (create)

SPEC (look at the existing delivery loop first; keep everything else as it is):
1. Each `Connection` keeps the policy of its tether (`TetherDef.delivery`), set where the connection is created in `create(...)`.
2. `TetherNetwork` implements `AutoCloseable` (`override fun close()`, same behaviour as now), so tests can use `.use { }`.
3. Delivery loop, per value taken from the queue:
   - A request whose response was already cancelled is skipped (not delivered).
   - Try to deliver. If delivery throws a `CancellationException`, rethrow. If it throws anything else and the policy is `BUFFER` and the exception is a `FabricException`: wait 50 ms (`delay`, constant `RETRY_DELAY_MS = 50L` in the companion object), skip the value if it is a request whose response was cancelled meanwhile, and try the same value again. While it waits, the queue behind it fills up and the sender suspends once the bounded queue is full.
   - Any other failure (policy `DROP`, or an exception that is not a `FabricException`): exactly as today (`TetherDeliveryException("tether ${c.info.id}: delivery to '${to.block}' failed: ${e.message}", e)`, complete a request exceptionally, call `onDeliveryFailure`), then continue with the next value.
   - The observer and interceptor hooks (`hook(...)`) run only on the first attempt of a value, not on retries. Moving the per-envelope `when (env)` into a private suspend function `handle(c, env, target, port, first: Boolean)` is the easiest way.
4. In the request path, when `withTimeout(config.requestTimeout...)` throws `TimeoutCancellationException`, call `response.cancel()` before throwing `TetherTimeoutException`, so that an abandoned buffered request is never delivered later.

TESTS: create `DeliveryPolicyTest` (JUnit 5, `runBlocking`) with a receiver whose `running` flag you can switch, a deliverer that throws `FabricException("block 'd' is not running")` while it is off, and a blueprint with one tether `s.out -> d.in`. Tests: (1) `DROP`: a value sent while the receiver is down is dropped and reported through `onDeliveryFailure`, a value sent after the receiver runs arrives; (2) `BUFFER`: 5 messages sent while the receiver is down are delivered in order after it starts, nothing is reported before; (3) `BUFFER` with queue capacity 2: the sender is suspended when the buffer is full, and finishes after the receiver starts; (4) a `BUFFER` request is answered after the receiver starts; (5) a `DROP` request fails at once with `TetherDeliveryException`; a `BUFFER` request that times out is not delivered later. Take the setup of the existing tether tests in the same package as a model.

CHECK: `./gradlew :engine:test --tests 'cringle.engine.tether.*' -q --console=plain` must pass (all existing tether tests too).

OUT OF SCOPE: `packaging` module, `spec/` documents, any other engine class, changing public signatures other than `AutoCloseable`.
