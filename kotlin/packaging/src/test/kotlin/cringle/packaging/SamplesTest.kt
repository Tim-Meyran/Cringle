// SPDX-License-Identifier: Apache-2.0

package cringle.packaging

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** The blueprints under `samples/` (#174) are valid files of the format. */
class SamplesTest {
    private val samples = Path.of("..", "..", "samples", "shared-service")

    private fun blueprint(path: String): Blueprint = ManifestJson.parseBlueprint(Files.readString(samples.resolve(path)), path)

    @Test
    fun theServiceBlueprintProvidesOrders() {
        val service = blueprint("orders-service/blueprints/service.json")
        assertEquals(listOf(ProvidedService("orders", "store", "in", cringle.contract.TetherType.MESSAGE)), service.provides)
        assertEquals(service, ManifestJson.parseBlueprint(ManifestJson.encode(service), "service.json"))
    }

    @Test
    fun theShopBlueprintUsesTheServiceByName() {
        val shop = blueprint("shop/blueprints/app.json")
        val tether = shop.tethers.single()
        assertEquals("orders", tether.service)
        assertEquals(null, tether.remote)
        assertEquals(DeliveryPolicy.BUFFER, tether.delivery)
        assertEquals(shop, ManifestJson.parseBlueprint(ManifestJson.encode(shop), "app.json"))
    }
}
