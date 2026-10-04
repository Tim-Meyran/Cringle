GOAL: Add a per-tether delivery policy to the blueprint model and its JSON format.

CONTEXT: Module `packaging` (Kotlin, JDK 21, Gradle). A tether in a blueprint is a `TetherDef(type, from, to)` (in `Model.kt`) parsed and written by `ManifestJson` (in `ManifestJson.kt`; look at how `type` is read with `enumValue<...>` and how `tetherKeys` lists the allowed JSON keys of a tether). The engine will later use the new policy to decide what happens when the receiving block is not running; that is NOT part of this task.

FILES (touch only these):
- `kotlin/packaging/src/main/kotlin/cringle/packaging/Model.kt`
- `kotlin/packaging/src/main/kotlin/cringle/packaging/ManifestJson.kt`
- `kotlin/packaging/src/test/kotlin/cringle/packaging/ManifestJsonTest.kt` (add tests, keep existing ones)

SPEC:
1. In `Model.kt` add `public enum class DeliveryPolicy { DROP, BUFFER }` with KDoc: `DROP` drops the value and logs it when the receiver is not running (default); `BUFFER` keeps it in the tether's bounded buffer until the receiver runs again.
2. `TetherDef` gets a fourth property `public val delivery: DeliveryPolicy = DeliveryPolicy.DROP` (default value, so existing constructor calls keep working). Update its KDoc.
3. `ManifestJson`: the JSON key `"delivery"` is allowed in a tether (add it to `tetherKeys`). It is optional; when present it is a string that must be `"DROP"` or `"BUFFER"`; read it with the same `enumValue<DeliveryPolicy>(JsonReading.string(o, "delivery", path), "$path.delivery")` pattern as `type` so that an unknown value like `"RETRY"` fails with a `PackageFormatException` whose message contains `unknown value 'RETRY'` and whose `path` ends with `delivery`. Missing means `DROP`.
4. When a blueprint is encoded, write `"delivery"` only if it is not `DROP`, so that old files stay byte-identical. Parsing the encoded text gives back an equal blueprint.

TESTS: add to `ManifestJsonTest`: (a) a blueprint with two tethers, one without and one with `"delivery":"BUFFER"`: parsed policies are `[DROP, BUFFER]`, the encoded text contains `"delivery"` exactly once, and parsing it again gives an equal blueprint; (b) an unknown policy `"RETRY"` is rejected with the message and path described above.

CHECK: `./gradlew :packaging:test --console=plain` must pass (all existing tests too).

OUT OF SCOPE: the engine module, `spec/` documents, any behaviour of the delivery itself.
