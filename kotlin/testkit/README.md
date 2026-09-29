# Cringle testkit

Test blocks and plugins without a running engine. Add it as `testImplementation(project(":testkit"))`; it exposes
the contract, `packaging`, coroutines-test and JUnit as `api` dependencies.

## Testing a block

`BlockTestHarness` creates a block (directly or through its `BlockProvider`), drives its lifecycle
(`init`, `start`, `stop`, `destroy`, order enforced) and delivers messages, requests and streams. Ports are
`InMemoryTether`s: they record what the block sends and answer requests with a scripted handler. Use it inside
`runTest`.

```kotlin
class ShoutBlockTest {
    @Test
    fun uppercasesAndLogs() = runTest {
        val dwh = InMemoryDwhDriver()
        val drivers = TestDriverSet().add(DwhDriver::class, dwh)
        val harness = BlockTestHarness.forProvider(SampleProvider, "shout", drivers)

        harness.runLifecycle {
            sendMessage("in", "hello")
            assertEquals(listOf<Any>("HELLO"), ports.tether("out").sentMessages)
        }
        assertEquals(listOf<Any>("HELLO"), dwh.entries.map { it.value })
    }
}
```

The sample blocks and more tests (requests, streams, byte streams, VarArg ports) are in
`src/test/kotlin/cringle/testkit`.

- Fakes: `InMemoryTether`, `InMemoryTetherStream`, `InMemoryTetherByteStream`, `TestBlockPorts`,
  `TestDriverSet`, `RecordingDriver`, `InMemoryDwhDriver`. Fake logging and filesystem drivers follow with the
  built-in drivers (issue #11).
- Streams: `harness.withStream(port) { stream -> ... }` runs the block's handler concurrently; feed items with
  `stream.feed(...)` and call `testScheduler.advanceUntilIdle()` before asserting on `stream.sent`.

## Building packages

`TestPluginBuilder` and `TestProjectBuilder` write plugin and project ZIPs with `packaging`, read them back and
validate them (turn off with `validate = false`). `TestJar.fromJavaSources` compiles tiny Java classes with the
JDK compiler into a JAR (needs a JDK), `TestJar.fromEntries` packs given bytes.

```kotlin
val plugin = TestPluginBuilder("acme-samples", "2.0.0")
    .provider("com.acme.Provider")
    .block(definition)
    .lib("acme.jar", TestJar.fromJavaSources(mapOf("com.acme.Hello" to source)))
    .build(tempDir)            // BuiltPlugin(file, hash, pkg)

val project = TestProjectBuilder("demo", "0.1.0")
    .blueprint(blueprint)
    .fabric(FabricConfig("main", 1, emptyList(), emptyMap()))
    .build(tempDir, plugins = listOf(plugin.pkg))
```
