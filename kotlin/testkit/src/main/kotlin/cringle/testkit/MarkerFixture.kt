// SPDX-License-Identifier: Apache-2.0

package cringle.testkit

import cringle.contract.BlockDefinition
import cringle.contract.SchemaRef
import cringle.packaging.BlueprintBlock
import java.nio.file.Path
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A small real plugin for end-to-end tests: its block type `marker` writes `started` (and later `stopped`) into the
 * file named by its config value `marker`, so a test can see that a block really ran in an engine process.
 * The plugin is `acme-demo` by default and its block is referenced as `acme-demo/marker`.
 */
public object MarkerFixture {
    private const val SCHEMA = """{"namespace":"acme.demo","types":{"MarkerConfig":{"record":{"marker":"cringle.std/String"}}}}"""

    private val sources = mapOf(
        "com.acme.MarkerProvider" to """
            package com.acme;
            import cringle.contract.*;
            import java.util.List;
            public class MarkerProvider implements BlockProvider {
                public List<BlockDefinition> getDefinitions() {
                    return List.of(new BlockDefinition("marker", List.of(), List.of(), List.of(), new SchemaRef("acme.demo", "MarkerConfig")));
                }
                public Block createBlock(String name, DriverSet drivers) { return new MarkerBlock(); }
            }
        """.trimIndent(),
        "com.acme.MarkerBlock" to """
            package com.acme;
            import cringle.contract.*;
            import kotlin.Unit;
            import kotlin.coroutines.Continuation;
            import java.nio.file.*;
            public class MarkerBlock implements Block {
                private String marker;
                public Object init(BlockContext c, Continuation<? super Unit> k) { marker = (String) c.getConfig().get("marker"); return Unit.INSTANCE; }
                public Object start(Continuation<? super Unit> k) { write("started"); return Unit.INSTANCE; }
                public Object stop(Continuation<? super Unit> k) { write("stopped"); return Unit.INSTANCE; }
                public Object destroy(Continuation<? super Unit> k) { return Unit.INSTANCE; }
                public Object onTetherEvent(TetherEvent e, Continuation<? super Unit> k) { return Unit.INSTANCE; }
                private void write(String s) {
                    try { Files.writeString(Path.of(marker), s); } catch (java.io.IOException e) { throw new RuntimeException(e); }
                }
            }
        """.trimIndent(),
    )

    /** Builds the plugin package into [dir]. */
    public fun plugin(dir: Path, version: String = "1.0.0"): BuiltPlugin = TestPluginBuilder("acme-demo", version)
        .provider("com.acme.MarkerProvider")
        .block(BlockDefinition("marker", emptyList(), emptyList(), emptyList(), SchemaRef("acme.demo", "MarkerConfig")))
        .schema("marker.json", SCHEMA)
        .lib("marker.jar", TestJar.fromJavaSources(sources))
        .build(dir)

    /** A blueprint block [id] of the marker type that writes to [marker]. */
    public fun block(id: String, marker: Path): BlueprintBlock =
        BlueprintBlock(id, "acme-demo/marker", config = JsonObject(mapOf("marker" to JsonPrimitive(marker.toString()))))
}
