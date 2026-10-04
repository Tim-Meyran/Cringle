// SPDX-License-Identifier: Apache-2.0

package cringle.cli

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.Duration
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Where the updater gets the release files from. */
internal interface ReleaseSource {
    /** The latest released version, or null if it cannot be determined. */
    fun latestVersion(): String?
    /** The manifest of [version]. */
    fun manifest(version: String): ReleaseManifest
    /** The lines of `SHA256SUMS` of [version] as file name -> lower-case SHA-256. */
    fun checksums(version: String): Map<String, String>
    /** Downloads [name] of [version] to [target]. */
    fun download(version: String, name: String, target: Path)
}

/**
 * Releases over HTTP. [baseUrl] is the release base (`https://github.com/Tim-Meyran/Cringle/releases/download` by
 * default, `CRINGLE_RELEASE_BASE_URL` otherwise). With the default base the latest version comes from the GitHub API;
 * with a custom base it is read from `<base>/latest/manifest.json`. Every other file is `<base>/v<version>/<name>`.
 */
internal class HttpReleaseSource(
    baseUrl: String,
    private val defaultBase: Boolean,
    private val client: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(30))
        .build(),
) : ReleaseSource {
    private val base = baseUrl.trimEnd('/')

    override fun latestVersion(): String? {
        if (!defaultBase) {
            return parseManifest(get("$base/latest/manifest.json")).version
        }
        val text = get("https://api.github.com/repos/Tim-Meyran/Cringle/releases/latest")
        return Regex("\"tag_name\"\\s*:\\s*\"([^\"]+)\"").find(text)?.groupValues?.get(1)?.removePrefix("v")
    }

    override fun manifest(version: String): ReleaseManifest = parseManifest(get("$base/v$version/manifest.json"))

    override fun checksums(version: String): Map<String, String> =
        get("$base/v$version/SHA256SUMS").lineSequence()
            .mapNotNull { line ->
                val m = Regex("^([0-9a-fA-F]{64})\\s+\\*?(.+?)\\s*$").find(line) ?: return@mapNotNull null
                m.groupValues[2] to m.groupValues[1].lowercase()
            }
            .toMap()

    override fun download(version: String, name: String, target: Path) {
        val request = HttpRequest.newBuilder(URI("$base/v$version/$name")).timeout(Duration.ofMinutes(10)).GET().build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofFile(target))
        if (response.statusCode() != 200) throw IllegalStateException("download of $name failed with HTTP ${response.statusCode()}")
    }

    private fun parseManifest(text: String): ReleaseManifest {
        val root = Json.parseToJsonElement(text).jsonObject
        val files = root.getValue("files").jsonArray.map { element ->
            val file = element.jsonObject
            ReleaseFile(
                name = file.getValue("name").jsonPrimitive.content,
                size = file.getValue("size").jsonPrimitive.content.toLong(),
                sha256 = file.getValue("sha256").jsonPrimitive.content,
                minJava = file["minJava"]?.jsonPrimitive?.content?.toInt() ?: 21,
            )
        }
        return ReleaseManifest(root.getValue("version").jsonPrimitive.content, files)
    }

    private fun get(url: String): String {
        val request = HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(60)).GET().build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) throw IllegalStateException("cannot read $url: HTTP ${response.statusCode()}")
        return response.body()
    }
}
