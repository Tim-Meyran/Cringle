// SPDX-License-Identifier: Apache-2.0

import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.zip.ZipFile
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.spotless)
    alias(libs.plugins.protobuf) apply false
}

val licenseHeaderPattern = "(package|import|syntax|plugins|pluginManagement|dependencyResolutionManagement|rootProject|dependencies|val|tasks|configure|subprojects|sourceSets|include)"

spotless {
    lineEndings = com.diffplug.spotless.LineEnding.PLATFORM_NATIVE
    kotlin {
        target("**/*.kt")
        targetExclude("**/build/**", "**/generated/**")
        licenseHeader("// SPDX-License-Identifier: Apache-2.0\n\n")
    }
    kotlinGradle {
        target("*.gradle.kts", "**/*.gradle.kts")
        targetExclude("**/build/**")
        licenseHeader("// SPDX-License-Identifier: Apache-2.0\n\n", licenseHeaderPattern)
    }
    format("proto") {
        target("proto/**/*.proto")
        licenseHeader("// SPDX-License-Identifier: Apache-2.0\n\n", licenseHeaderPattern)
    }
}

val versionCatalog = extensions.getByType<VersionCatalogsExtension>().named("libs")

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")

    configure<KotlinJvmProjectExtension> {
        jvmToolchain(21)
    }

    dependencies {
        add("implementation", versionCatalog.findLibrary("kotlin-stdlib").get())
        add("testImplementation", versionCatalog.findLibrary("junit-jupiter").get())
        add("testRuntimeOnly", "org.junit.platform:junit-platform-launcher:1.11.4")
    }

    tasks.withType<Test> {
        useJUnitPlatform()
    }
}

// The Cringle Gradle plugin and the TestKit sample projects need contract, schema and packaging as ordinary
// dependencies. A local Maven repository inside build/ keeps them resolvable without publishing anywhere and
// without touching ~/.m2. The same publications go into a second folder, this time in the layout of Maven Local and
// including the plugin itself, which is what projects outside this build resolve.
val localPublishVersion = versionCatalog.findVersion("localPublish").get().requiredVersion
val localPublishRepo = layout.buildDirectory.dir("cringle-repo")
val testMavenLocalRepo = layout.buildDirectory.dir("cringle-test-maven-local")
val localPublishModules = listOf(":contract", ":schema", ":packaging", ":common", ":testkit")

localPublishModules.forEach { path ->
    val module = project(path)
    module.group = "cringle"
    module.version = localPublishVersion
    module.apply(plugin = "maven-publish")
    module.extensions.configure<PublishingExtension> {
        publications { register<MavenPublication>("maven") { from(module.components["java"]) } }
        repositories {
            maven { name = "cringleLocal"; url = localPublishRepo.get().asFile.toURI() }
            maven { name = "cringleTestMavenLocal"; url = testMavenLocalRepo.get().asFile.toURI() }
        }
    }
}

// The task `cringlePublish` uploads with `RepositoryClient` (#51), so a project outside this build that applies the
// plugin needs the repository module and the module it needs at runtime (`:router` for `AuthInterceptor`). They are
// no sample dependency, so they go into the test folder and into Maven Local (the standard `publishToMavenLocal` of
// every module that applies `maven-publish`) and not into `build/cringle-repo`, which stays the way it is: contract,
// schema and packaging, and nothing else. `:common` is in `localPublishModules` because the samples need its gRPC
// types, so it goes into both repos.
val pluginRuntimeModules = listOf(":router", ":repository")

pluginRuntimeModules.forEach { path ->
    val module = project(path)
    module.group = "cringle"
    module.version = localPublishVersion
    module.apply(plugin = "maven-publish")
    module.extensions.configure<PublishingExtension> {
        publications { register<MavenPublication>("maven") { from(module.components["java"]) } }
        repositories {
            maven { name = "cringleTestMavenLocal"; url = testMavenLocalRepo.get().asFile.toURI() }
        }
    }
}

// `java-gradle-plugin` creates the publications of the Cringle Gradle plugin: the plugin JAR and one marker per
// plugin id in `gradlePlugin { }`. The build script of the module sets its own coordinates, the folder its
// publications are written to and the marker it leaves out; `build/cringle-repo` stays the way it is for the samples.

tasks.register("publishToLocalRepo") {
    group = "build"
    description = "Publishes contract, schema, packaging, common and testkit into build/cringle-repo for the Gradle plugin samples."
    dependsOn(localPublishModules.map { "$it:publishAllPublicationsToCringleLocalRepository" })
}

// The tests read the folder of the publications, so it holds what one publication writes and nothing of an earlier
// one. A Gradle repository gives a snapshot a new timestamp in the name of its files on every publication, and the
// tests would otherwise see the publications of every build that ever ran.
val deleteTestMavenLocal = tasks.register<Delete>("deleteTestMavenLocal") {
    group = "build"
    description = "Empties build/cringle-test-maven-local before the next publication into it."
    delete(testMavenLocalRepo)
}

// The plugin publishes itself, `java-gradle-plugin` creates its publications and its marker. Since #51 it publishes
// with `RepositoryClient`, so the modules it needs at runtime go with it.
val testMavenLocalModules = localPublishModules + pluginRuntimeModules + ":gradle-plugin"

tasks.register("publishToTestMavenLocal") {
    group = "build"
    description = "Publishes contract, schema, packaging, the repository modules and the Cringle Gradle plugin in the " +
        "layout of Maven Local into build/cringle-test-maven-local, for the tests of " +
        "samples/external-plugin-example."
    dependsOn(testMavenLocalModules.map { "$it:publishAllPublicationsToCringleTestMavenLocalRepository" })
    dependsOn(deleteTestMavenLocal)
}

testMavenLocalModules.forEach { path ->
    project(path).tasks.matching { it.name == "publishAllPublicationsToCringleTestMavenLocalRepository" }
        .configureEach { mustRunAfter(deleteTestMavenLocal) }
}

// --- The release distribution (#57, docs/releasing.md) ---
//
// `./gradlew cringleDist` writes build/dist/cringle-<version>-linux.tar.gz, cringle-<version>-windows.zip, SHA256SUMS and
// manifest.json. Both archives hold the same JARs (they run on any JVM); they differ in the start scripts and in the
// archive format. The version comes from the tag in the release workflow (`-PreleaseVersion=1.2.3`); local builds are
// 0.0.0-SNAPSHOT.
val releaseVersion: String = providers.gradleProperty("releaseVersion").orElse("0.0.0-SNAPSHOT").get()
extra["releaseVersion"] = releaseVersion
val distMinJava = 21
val distDir = layout.buildDirectory.dir("dist")

// Start script name to main class. The JARs of the other modules (engine, router, repository, ...) are on the class path
// of all of them: the daemon starts engine processes with its own class path.
val distLaunchers = linkedMapOf(
    "cringle" to "cringle.cli.MainKt",
    "cringle-daemon" to "cringle.daemon.MainKt",
    "cringle-management-server" to "cringle.management.MainKt",
)
val distModules = listOf(":cli", ":daemon", ":management-server", ":engine", ":router", ":repository")

// Everything the distribution ships: the JAR of each of these modules and everything it needs at runtime, taken from
// the runtime classpath that the module resolves for itself (so the versions are the ones the tests run with).
fun distRuntimeFiles(): Set<File> = distModules.flatMap { path ->
    val module = project(path)
    module.configurations.getByName("runtimeClasspath").files + module.tasks.getByName("jar").outputs.files.files
}.toSet()

val semanticVersion = Regex("[0-9]+\\.[0-9]+\\.[0-9]+(-[0-9A-Za-z.-]+)?")

/** One staged distribution: `build/dist-stage/<platform>/cringle-<version>/` with the start scripts of that platform. */
fun registerStage(platform: String) = tasks.register("cringleDistStage" + platform.replaceFirstChar { it.uppercase() }) {
    group = "distribution"
    description = "Assembles the $platform distribution directory of cringleDist."
    val stage = layout.buildDirectory.dir("dist-stage/$platform")
    val windows = platform == "windows"
    distModules.forEach { path ->
        inputs.files(project(path).configurations.named("runtimeClasspath"))
        inputs.files(project(path).tasks.named("jar"))
    }
    inputs.dir("dist")
    inputs.files("LICENSE", "NOTICE")
    inputs.property("version", releaseVersion)
    outputs.dir(stage)
    doLast {
        check(semanticVersion.matches(releaseVersion)) { "releaseVersion '$releaseVersion' is not a version like 1.2.3 or 1.2.3-rc.1" }
        val root = stage.get().asFile.toPath()
        root.toFile().deleteRecursively()
        val home = Files.createDirectories(root.resolve("cringle-$releaseVersion"))
        val lib = Files.createDirectories(home.resolve("lib"))
        for (jar in distRuntimeFiles()) {
            val target = lib.resolve(jar.name)
            check(!Files.exists(target)) { "two JARs of the distribution are called ${jar.name}" }
            Files.copy(jar.toPath(), target, StandardCopyOption.COPY_ATTRIBUTES)
        }
        val templates = projectDir.toPath().resolve("dist/templates")
        val template = Files.readString(templates.resolve(if (windows) "launcher.bat" else "launcher.sh")).replace("\r\n", "\n")
        val eol = if (windows) "\r\n" else "\n"
        val bin = Files.createDirectories(home.resolve("bin"))
        for ((command, mainClass) in distLaunchers) {
            val script = template.replace("@COMMAND@", command).replace("@MAIN_CLASS@", mainClass).replace("\n", eol)
            Files.write(bin.resolve(if (windows) "$command.bat" else command), script.toByteArray(Charsets.UTF_8))
        }
        val conf = Files.createDirectories(home.resolve("conf"))
        Files.writeString(conf.resolve("README.txt"), Files.readString(projectDir.toPath().resolve("dist/conf/README.txt")).replace("\r\n", "\n").replace("\n", eol))
        Files.copy(projectDir.toPath().resolve("LICENSE"), home.resolve("LICENSE"))
        Files.copy(projectDir.toPath().resolve("NOTICE"), home.resolve("NOTICE"))
        Files.write(home.resolve("VERSION"), (releaseVersion + "\n").toByteArray(Charsets.UTF_8))
    }
}

val stageLinux = registerStage("linux")
val stageWindows = registerStage("windows")

val distLinux = tasks.register<Tar>("cringleDistLinux") {
    group = "distribution"
    description = "Writes build/dist/cringle-<version>-linux.tar.gz."
    dependsOn(stageLinux)
    from(layout.buildDirectory.dir("dist-stage/linux"))
    archiveFileName.set("cringle-$releaseVersion-linux.tar.gz")
    destinationDirectory.set(distDir)
    compression = Compression.GZIP
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    // the start scripts are executable, everything else is not; a stage directory on Windows has no such bits
    dirPermissions { unix("rwxr-xr-x") }
    filePermissions { unix("rw-r--r--") }
    filesMatching("*/bin/*") { permissions { unix("rwxr-xr-x") } }
}

val distWindows = tasks.register<Zip>("cringleDistWindows") {
    group = "distribution"
    description = "Writes build/dist/cringle-<version>-windows.zip."
    dependsOn(stageWindows)
    from(layout.buildDirectory.dir("dist-stage/windows"))
    archiveFileName.set("cringle-$releaseVersion-windows.zip")
    destinationDirectory.set(distDir)
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

val distManifest = tasks.register("cringleDistManifest") {
    group = "distribution"
    description = "Writes SHA256SUMS and manifest.json for the archives of cringleDist."
    val archives = listOf(distLinux, distWindows).map { task -> task.flatMap { it.archiveFile } }
    inputs.files(archives)
    inputs.property("version", releaseVersion)
    val sums = distDir.map { it.file("SHA256SUMS") }
    val manifest = distDir.map { it.file("manifest.json") }
    outputs.files(sums, manifest)
    doLast {
        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
        val files = archives.map { it.get().asFile }.sortedBy { it.name }.map { Triple(it.name, it.length(), sha256(it)) }
        sums.get().asFile.writeText(files.joinToString("") { (name, _, hash) -> "$hash  $name\n" })
        manifest.get().asFile.writeText(
            buildString {
                append("{\n  \"version\": \"$releaseVersion\",\n  \"files\": [\n")
                append(files.joinToString(",\n") { (name, size, hash) -> "    { \"name\": \"$name\", \"size\": $size, \"sha256\": \"$hash\", \"minJava\": $distMinJava }" })
                append("\n  ]\n}\n")
            },
        )
    }
}

tasks.register("cringleDist") {
    group = "distribution"
    description = "Builds the release archives, SHA256SUMS and manifest.json into build/dist (see docs/releasing.md)."
    dependsOn(distManifest)
}

// --- The Windows distribution with an embedded JRE (#155, docs/releasing.md) ---
//
// `./gradlew cringleWindowsRuntime -PreleaseVersion=1.2.3` writes build/dist-stage/windows-jre/cringle-<version>/: the
// Windows distribution plus `jre/` (Eclipse Temurin 21, the whole JRE) and THIRD-PARTY.txt. The MSI (#156) installs that
// tree. It is not part of `build` or `cringleDist`: the JRE is downloaded, and the download is checked against the
// SHA-256 below. The four constants are the one place to change for a new Temurin version; -PcringleJreUrl and
// -PcringleJreSha256 replace the URL and the checksum for one run (a file: URL works).
val windowsJreVersion = "21.0.12+8"
val windowsJreFile = "OpenJDK21U-jre_x64_windows_hotspot_21.0.12_8.zip"
val windowsJreUrl = "https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.12%2B8/$windowsJreFile"
val windowsJreSha256 = "b8aa18fef5edb69bee8618f99677d66d0873d22cb40d974c15ac9ffcdecf73ba"

fun fileSha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            digest.update(buffer, 0, n)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

tasks.register("cringleWindowsRuntime") {
    group = "distribution"
    description = "Writes build/dist-stage/windows-jre/cringle-<version>/: the Windows distribution with an embedded Temurin 21 JRE."
    val stage = layout.buildDirectory.dir("dist-stage/windows")
    val output = layout.buildDirectory.dir("dist-stage/windows-jre")
    val downloads = layout.buildDirectory.dir("downloads")
    val url = providers.gradleProperty("cringleJreUrl").orElse(windowsJreUrl)
    val sha256 = providers.gradleProperty("cringleJreSha256").orElse(windowsJreSha256)
    dependsOn(stageWindows)
    inputs.dir(stage)
    inputs.property("jreUrl", url)
    inputs.property("jreSha256", sha256)
    outputs.dir(output)
    doLast {
        val expected = sha256.get().lowercase()
        val fileName = url.get().substringAfterLast('/').substringBefore('?').ifBlank { windowsJreFile }
        val cached = Files.createDirectories(downloads.get().asFile.toPath()).resolve(fileName).toFile()
        if (cached.exists() && fileSha256(cached) != expected) cached.delete()
        if (!cached.exists()) {
            logger.lifecycle("downloading ${url.get()}")
            val connection = URI(url.get()).toURL().openConnection().apply { connectTimeout = 30_000; readTimeout = 120_000 }
            val partial = File(cached.path + ".part")
            connection.getInputStream().use { input -> partial.outputStream().use { input.copyTo(it) } }
            val actual = fileSha256(partial)
            if (actual != expected) {
                partial.delete()
                throw GradleException("the JRE download ${url.get()} has the wrong SHA-256: expected $expected, found $actual; the file was deleted")
            }
            check(partial.renameTo(cached)) { "cannot move $partial to $cached" }
        }
        val home = output.get().asFile.toPath().resolve("cringle-$releaseVersion")
        output.get().asFile.deleteRecursively()
        stage.get().asFile.resolve("cringle-$releaseVersion").copyRecursively(home.toFile())
        // the archive has one top directory (jdk-21.0.12+8-jre/); its content becomes jre/
        val jre = Files.createDirectories(home.resolve("jre"))
        ZipFile(cached).use { zip ->
            for (entry in zip.entries()) {
                val relative = entry.name.substringAfter('/', "")
                if (relative.isEmpty()) continue
                val target = jre.resolve(relative).normalize()
                check(target.startsWith(jre)) { "the JRE archive has an entry outside of jre/: ${entry.name}" }
                if (entry.isDirectory) {
                    Files.createDirectories(target)
                } else {
                    Files.createDirectories(target.parent)
                    zip.getInputStream(entry).use { Files.copy(it, target, StandardCopyOption.REPLACE_EXISTING) }
                }
            }
        }
        check(Files.isRegularFile(jre.resolve("bin/java.exe"))) { "the JRE archive $fileName has no bin/java.exe" }
        Files.writeString(
            home.resolve("THIRD-PARTY.txt"),
            """
            |Third-party software in this distribution
            |
            |jre/  Eclipse Temurin $windowsJreVersion JRE for Windows x64 (OpenJDK 21), https://adoptium.net/
            |      License: GNU General Public License, version 2, with the Classpath Exception.
            |      Source: ${url.get()}
            |      SHA-256: $expected
            |      The license texts of the JRE are in jre/legal/.
            |
            """.trimMargin().replace("\n", "\r\n"),
        )
    }
}

// --- The Windows MSI (#156, installer/msi/Package.wxs) ---
//
// `./gradlew cringleMsi -PreleaseVersion=1.2.3` writes build/dist/cringle-<version>-x64.msi from the tree that
// cringleWindowsRuntime stages. It needs the WiX Toolset v5 (`wix`, MS-RL, a build tool that is not shipped) with its UI
// extension. It is not part of `build`.
val wixVersion = "5.0.2"

fun msiProductVersion(): String {
    // MSI versions are MAJOR.MINOR.PATCH with major and minor below 256 and patch below 65536; a pre-release suffix cannot be carried
    val parts = releaseVersion.substringBefore('-').split('.').map { it.toIntOrNull() ?: -1 }
    check(semanticVersion.matches(releaseVersion) && parts.size == 3) { "releaseVersion '$releaseVersion' is not a version like 1.2.3 or 1.2.3-rc.1" }
    check(parts[0] in 0..255 && parts[1] in 0..255 && parts[2] in 0..65535) { "the version $releaseVersion does not fit in an MSI version (major and minor below 256, patch below 65536)" }
    return parts.joinToString(".")
}

tasks.register("cringleMsiSources") {
    group = "distribution"
    description = "Generates build/msi/ (Files.wxs, License.rtf, installed-by-msi) for cringleMsi; runs on any system."
    val msiDir = layout.buildDirectory.dir("msi")
    dependsOn(tasks.named("cringleWindowsRuntime"))
    inputs.dir(layout.buildDirectory.dir("dist-stage/windows-jre"))
    inputs.file("LICENSE")
    inputs.property("version", releaseVersion)
    outputs.dir(msiDir)
    doLast {
        msiProductVersion()
        val tree = layout.buildDirectory.dir("dist-stage/windows-jre/cringle-$releaseVersion").get().asFile.toPath()
        check(Files.isDirectory(tree)) { "$tree does not exist" }
        val out = msiDir.get().asFile.toPath()
        out.toFile().deleteRecursively()
        Files.createDirectories(out)
        fun xml(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

        // one component with one file each; the folders are explicit Directory elements (the Subdirectory attribute of a
        // Component is not reliable when WiX runs on Linux). Guid="*" derives the component id from the key path.
        val files = Files.walk(tree).use { stream -> stream.filter { Files.isRegularFile(it) }.toList().sortedBy { tree.relativize(it).toString().replace('\\', '/') } }
        val relatives = files.map { tree.relativize(it).toString().replace('\\', '/') }
        val folders = sortedSetOf<String>()
        for (relative in relatives) {
            var folder = relative.substringBeforeLast('/', "")
            while (folder.isNotEmpty()) { folders += folder; folder = folder.substringBeforeLast('/', "") }
        }
        val folderIds = folders.withIndex().associate { (i, folder) -> folder to "d$i" }
        fun directories(parent: String, indent: String): String = buildString {
            for (folder in folders) {
                if (folder.substringBeforeLast('/', "") != parent) continue
                append("$indent<Directory Id=\"${folderIds.getValue(folder)}\" Name=\"${xml(folder.substringAfterLast('/'))}\">\n")
                append(directories(folder, "$indent  "))
                append("$indent</Directory>\n")
            }
        }
        Files.writeString(
            out.resolve("Files.wxs"),
            buildString {
                append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
                append("<!-- generated by cringleMsi from the files of the staged tree -->\n")
                append("<Wix xmlns=\"http://wixtoolset.org/schemas/v4/wxs\">\n")
                append("  <Fragment>\n    <DirectoryRef Id=\"INSTALLFOLDER\">\n${directories("", "      ")}    </DirectoryRef>\n  </Fragment>\n")
                append("  <Fragment>\n    <ComponentGroup Id=\"CringleFiles\" Directory=\"INSTALLFOLDER\">\n")
                relatives.forEachIndexed { i, relative ->
                    val folder = relative.substringBeforeLast('/', "")
                    val directory = if (folder.isEmpty()) "" else " Directory=\"${folderIds.getValue(folder)}\""
                    append("      <Component Id=\"c$i\"$directory><File Id=\"f$i\" Source=\"\$(var.StageDir)/${xml(relative)}\" KeyPath=\"yes\" /></Component>\n")
                }
                append("    </ComponentGroup>\n  </Fragment>\n</Wix>\n")
            },
        )
        Files.write(out.resolve("installed-by-msi"), ByteArray(0))

        // the licence dialog needs RTF: the text of LICENSE in a fixed-width font
        val rtf = StringBuilder("{\\rtf1\\ansi\\deff0{\\fonttbl{\\f0\\fmodern Courier New;}}\\f0\\fs18\n")
        for (line in Files.readAllLines(projectDir.toPath().resolve("LICENSE"))) {
            for (c in line) {
                when {
                    c == '\\' || c == '{' || c == '}' -> rtf.append('\\').append(c)
                    c.code > 126 -> rtf.append("\\u").append(c.code.toShort().toInt()).append('?')
                    else -> rtf.append(c)
                }
            }
            rtf.append("\\par\n")
        }
        rtf.append("}\n")
        Files.writeString(out.resolve("License.rtf"), rtf.toString(), Charsets.US_ASCII)
    }
}

tasks.register("cringleMsi") {
    group = "distribution"
    description = "Writes build/dist/cringle-<version>-x64.msi (needs the WiX Toolset v5 on Windows, see docs/releasing.md)."
    val msiDir = layout.buildDirectory.dir("msi")
    val msi = distDir.map { it.file("cringle-$releaseVersion-x64.msi") }
    dependsOn(tasks.named("cringleMsiSources"))
    inputs.dir(msiDir)
    inputs.dir(layout.buildDirectory.dir("dist-stage/windows-jre"))
    inputs.file("installer/msi/Package.wxs")
    inputs.property("version", releaseVersion)
    outputs.file(msi)
    doLast {
        val installHint = "dotnet tool install --global wix --version $wixVersion && wix extension add --global WixToolset.UI.wixext/$wixVersion"
        val found = try {
            val probe = ProcessBuilder("wix", "--version").redirectErrorStream(true).start()
            probe.inputStream.readAllBytes()
            probe.waitFor() == 0
        } catch (e: java.io.IOException) {
            false
        }
        if (!found) throw GradleException("the program 'wix' (WiX Toolset $wixVersion) was not found on the PATH; install it with: $installHint")

        val productVersion = msiProductVersion()
        val tree = layout.buildDirectory.dir("dist-stage/windows-jre/cringle-$releaseVersion").get().asFile.toPath()
        val out = msiDir.get().asFile.toPath()
        distDir.get().asFile.mkdirs()
        val command = listOf(
            "wix", "build", "-arch", "x64", "-pdbtype", "none", "-ext", "WixToolset.UI.wixext",
            "-d", "StageDir=$tree", "-d", "MsiDir=$out", "-d", "ProductVersion=$productVersion",
            "-o", msi.get().asFile.path,
            projectDir.toPath().resolve("installer/msi/Package.wxs").toString(), out.resolve("Files.wxs").toString(),
        )
        val build = ProcessBuilder(command).redirectErrorStream(true).start()
        val log = build.inputStream.bufferedReader().readText()
        if (build.waitFor() != 0) throw GradleException("wix build failed (extension missing? $installHint). The WiX Toolset only works on Windows${if (System.getProperty("os.name").lowercase().contains("win")) "" else " and this is not Windows"}:\n$log")
        if (log.isNotBlank()) logger.lifecycle(log.trim())
    }
}
