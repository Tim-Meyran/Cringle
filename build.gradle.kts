// SPDX-License-Identifier: Apache-2.0

import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.zip.ZipFile
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
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

// The modules build in parallel, but the integration tests of two modules never run at the same time: they start processes and write files,
// and under the load of several modules at once a stopped process can still hold its log file on Windows. Inside a module the test classes
// run in several JVMs (`maxParallelForks`).
abstract class IntegrationTestLock : BuildService<BuildServiceParameters.None>

val integrationTestLock = gradle.sharedServices.registerIfAbsent("integrationTestLock", IntegrationTestLock::class) {
    maxParallelUsages.set(1)
}

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

    // The default `test` task is the fast suite; the tests tagged "integration" (real processes, ports, nested Gradle builds) run in `integrationTest`.
    // Test classes run in several JVMs at once; `-PtestForks=1` turns it off. Tests use ports and directories of their own (port 0, temp dirs), so forks do not meet.
    val testForks = providers.gradleProperty("testForks").map { it.toInt() }.orElse((Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 4))

    // The tests and the processes they start write to a temporary folder of their own, emptied before every run: on Windows netty leaves a
    // 3 MB DLL (netty_tcnative) per JVM in the temp folder that nobody deletes (4000 of them were 11 GB), and the tests leave folders.
    // The folder is below the system temp folder, not in build/, to keep the paths short (nested Gradle builds run in it).
    fun Test.ownTempDir() {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "cringle-tests/${project.name}-$name")
        doFirst {
            delete(tempDir)
            tempDir.mkdirs()
        }
        systemProperty("java.io.tmpdir", tempDir.absolutePath)
        for (variable in listOf("TMP", "TEMP", "TMPDIR")) environment(variable, tempDir.absolutePath)
    }

    tasks.named<Test>("test") {
        useJUnitPlatform { excludeTags("integration") }
        maxParallelForks = testForks.get()
        ownTempDir()
    }

    tasks.register<Test>("integrationTest") {
        description = "Runs the tests tagged \"integration\" (real processes, ports, nested Gradle builds)."
        group = "verification"
        val test = project.the<SourceSetContainer>()["test"]
        testClassesDirs = test.output.classesDirs
        classpath = test.runtimeClasspath
        useJUnitPlatform { includeTags("integration") }
        maxParallelForks = testForks.get()
        usesService(integrationTestLock)
        ownTempDir()
        // the processes that these tests start (engines, daemons) live for seconds: a quick start matters more than peak speed.
        // Not for cli and gradle-plugin: their tests compare the output of a JVM ("Picked up JAVA_TOOL_OPTIONS") and run nested Gradle builds.
        if (project.name in setOf("management-server", "daemon", "engine")) environment("JAVA_TOOL_OPTIONS", "-XX:TieredStopAtLevel=1 -XX:+UseSerialGC")
        shouldRunAfter(tasks.named("test"))
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
    // every task that writes into the folder (`publishMavenPublicationTo...`, `publishPluginMavenPublicationTo...`, the markers, and the
    // `publishAllPublicationsTo...` that bundles them) runs after the folder was emptied; the tasks of one publication are
    // not all dependencies of the bundle in the order the graph runs them, so a clean checkout lost the first ones
    project(path).tasks.matching { it.name.startsWith("publish") && it.name.endsWith("ToCringleTestMavenLocalRepository") }
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

// --- A release in one command (#276, docs/releasing.md) ---
//
// `./gradlew cringleRelease -PreleaseVersion=1.2.3` builds the distribution, adds WinSW and the installers, writes SHA256SUMS
// for everything and prints the commands that publish the release. It tags (`-PreleaseTag`, local only) but never pushes and
// never creates the release. The four constants are the one place to change for a new WinSW version; -PwinswUrl and
// -PwinswSha256 replace the URL and the checksum for one run (a file: URL works).
val winswVersion = "2.12.0"
val winswUrl = "https://github.com/winsw/winsw/releases/download/v$winswVersion/WinSW.NET461.exe"
val winswSha256 = "b5066b7bbdfba1293e5d15cda3caaea88fbeab35bd5b38c41c913d492aadfc4f"

// fails before anything is built if the version is missing
val releaseGuard = tasks.register("cringleReleaseCheck") {
    group = "distribution"
    description = "Fails unless -PreleaseVersion names a real version."
    doLast {
        if (!providers.gradleProperty("releaseVersion").isPresent || releaseVersion == "0.0.0-SNAPSHOT") {
            throw GradleException("cringleRelease needs the version of the release: -PreleaseVersion=1.2.3 (or 1.2.3-rc.1); it is the Git tag without the leading v")
        }
    }
}
tasks.named("cringleDist") { mustRunAfter(releaseGuard) }

tasks.register("cringleRelease") {
    group = "distribution"
    description = "Prepares build/dist for a GitHub release (needs -PreleaseVersion): archives, WinSW, installers, SHA256SUMS; -PreleaseTag tags locally."
    dependsOn(releaseGuard, "cringleDist")
    val url = providers.gradleProperty("winswUrl").orElse(winswUrl)
    val sha256 = providers.gradleProperty("winswSha256").orElse(winswSha256)
    val downloads = layout.buildDirectory.dir("downloads")
    doLast {
        val dist = distDir.get().asFile
        // WinSW: download once, check, keep in build/downloads
        val expected = sha256.get().lowercase()
        val cached = Files.createDirectories(downloads.get().asFile.toPath()).resolve("WinSW-$winswVersion.exe").toFile()
        if (cached.exists() && fileSha256(cached) != expected) cached.delete()
        if (!cached.exists()) {
            logger.lifecycle("downloading ${url.get()}")
            val connection = URI(url.get()).toURL().openConnection().apply { connectTimeout = 30_000; readTimeout = 120_000 }
            val partial = File(cached.path + ".part")
            connection.getInputStream().use { input -> partial.outputStream().use { input.copyTo(it) } }
            val actual = fileSha256(partial)
            if (actual != expected) {
                partial.delete()
                throw GradleException("the WinSW download ${url.get()} has the wrong SHA-256: expected $expected, found $actual; the file was deleted")
            }
            check(partial.renameTo(cached)) { "cannot move $partial to $cached" }
        }
        Files.copy(cached.toPath(), dist.toPath().resolve("winsw.exe"), StandardCopyOption.REPLACE_EXISTING)
        for (installer in listOf("install.sh", "install.ps1")) {
            Files.copy(projectDir.toPath().resolve("installer/$installer"), dist.toPath().resolve(installer), StandardCopyOption.REPLACE_EXISTING)
        }
        // SHA256SUMS: the archives (as cringleDist wrote them) and winsw.exe
        val sums = dist.resolve("SHA256SUMS")
        val lines = sums.readLines().filter { it.isNotBlank() && !it.endsWith("  winsw.exe") } + "${fileSha256(dist.resolve("winsw.exe"))}  winsw.exe"
        sums.writeText(lines.joinToString("\n", postfix = "\n"))
        // every line has to match its file, and both archives of this version have to be there
        for (line in lines) {
            val (hash, name) = line.split("  ", limit = 2)
            val file = dist.resolve(name)
            check(file.isFile) { "SHA256SUMS names $name, which is not in $dist" }
            check(fileSha256(file) == hash) { "SHA256SUMS has the wrong checksum for $name" }
        }
        val archives = listOf("cringle-$releaseVersion-linux.tar.gz", "cringle-$releaseVersion-windows.zip")
        archives.forEach { check(lines.any { l -> l.endsWith("  $it") }) { "SHA256SUMS does not list $it" } }
        val tag = "v$releaseVersion"
        if (providers.gradleProperty("releaseTag").isPresent) {
            fun git(vararg args: String): Pair<Int, String> {
                val process = ProcessBuilder(listOf("git") + args).directory(projectDir).redirectErrorStream(true).start()
                val output = process.inputStream.bufferedReader().readText().trim()
                return process.waitFor() to output
            }
            val (_, dirty) = git("status", "--porcelain")
            if (dirty.isNotEmpty()) throw GradleException("the work tree is not clean, so no tag is made:\n$dirty")
            if (git("rev-parse", "-q", "--verify", "refs/tags/$tag").first == 0) throw GradleException("the tag $tag exists already")
            val (code, output) = git("tag", tag)
            if (code != 0) throw GradleException("git tag $tag failed: $output")
            logger.lifecycle("created the local tag $tag")
        }
        val files = archives + listOf("SHA256SUMS", "manifest.json", "winsw.exe", "install.sh", "install.ps1")
        val prerelease = if ('-' in releaseVersion) " --prerelease" else ""
        logger.lifecycle(
            buildString {
                appendLine("Release $releaseVersion is ready in $dist:")
                files.forEach { appendLine("  $it") }
                appendLine()
                appendLine("Next (tests: ./gradlew build integrationTest -PreleaseVersion=$releaseVersion must have passed):")
                if (!providers.gradleProperty("releaseTag").isPresent) appendLine("  git tag $tag")
                appendLine("  git push origin $tag")
                appendLine("  (cd build/dist && gh release create $tag ${files.joinToString(" ")} --title \"Cringle $releaseVersion\" --generate-notes$prerelease)")
            },
        )
    }
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

// --- Install, update and remove a locally built Cringle on Windows (installer/install.ps1 -FromBuild) ---
//
// `.\gradlew.bat cringleInstallLocal` builds the distribution (cringleDist) and installs it like a release: program
// files, the services and the PATH entry; install.ps1 asks for administrative rights (the Windows dialog) unless -PnoService is given.
// `cringleUpdateLocal` builds and installs over an existing installation (it fails if there is none; the services that were
// running are started again). `cringleUninstallLocal` removes it (data stays; -Ppurge removes it too).
// Options (-P...): releaseVersion (default 0.0.0-SNAPSHOT), installRoot, dataRoot, daemonOnly, noStart, noService, purge.
fun registerLocalInstaller(taskName: String, text: String, mode: String) = tasks.register(taskName) {
    group = "distribution"
    description = text
    if (mode != "uninstall") dependsOn("cringleDist")
    doLast {
        if (!System.getProperty("os.name").lowercase().contains("win")) {
            throw GradleException("$taskName runs the PowerShell installer and works on Windows only; on Linux use `sudo installer/install.sh` (docs/daemon-service.md)")
        }
        fun option(name: String): String? = providers.gradleProperty(name).orNull
        fun flag(name: String): Boolean = providers.gradleProperty(name).isPresent
        val installRoot = option("installRoot") ?: File(System.getenv("ProgramFiles") ?: "C:\\Program Files", "Cringle").path
        if (mode == "update" && !File(installRoot, "current").exists()) {
            throw GradleException("nothing is installed in $installRoot (no 'current' link): install it with `.\\gradlew.bat cringleInstallLocal`")
        }
        val command = mutableListOf("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", projectDir.toPath().resolve("installer/install.ps1").toString())
        if (mode == "uninstall") {
            command += "-Uninstall"
            if (flag("purge")) command += "-Purge"
        } else {
            command += listOf("-FromBuild", distDir.get().asFile.path, "-Version", releaseVersion)
            if (flag("daemonOnly")) command += "-DaemonOnly"
            if (!flag("noStart")) command += "-Start"
        }
        if (flag("noService")) command += "-NoService"
        command += listOf("-InstallRoot", installRoot)
        option("dataRoot")?.let { command += listOf("-DataRoot", it) }
        logger.lifecycle(command.joinToString(" "))
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        val result = process.waitFor()
        if (output.isNotBlank()) logger.lifecycle(output.trim())
        if (result != 0) throw GradleException("install.ps1 ended with exit code $result:\n${output.trim()}")
    }
}

registerLocalInstaller("cringleInstallLocal", "Builds the distribution and installs it on this Windows machine (installer/install.ps1 -FromBuild).", "install")
registerLocalInstaller("cringleUpdateLocal", "Builds the distribution and installs it over the existing local installation (fails if there is none).", "update")
registerLocalInstaller("cringleUninstallLocal", "Removes the Cringle that is installed on this Windows machine (the data stays unless -Ppurge).", "uninstall")

// --- Start the programs from the IDE -------------------------------------------------------------------------------------
// One JavaExec task per program, group "cringle", so that the IDE can run and debug each of them from the Gradle tool window
// (`./gradlew runDaemon --console=plain --no-daemon` works as well). The programs read CRINGLE_HOME: the tasks point it at
// build/dev-home, never at ~/.cringle; -PcringleHome=<dir> chooses another folder. The default arguments are replaced by
// `--args="..."`, e.g. `./gradlew runManagementServer --args="--port 7501 --auth"`. The standard input is connected, so
// `cringle login` can read a token from it. Each program is its own process: start the daemon and the management server in
// two run configurations; they find each other by the trust entries that docs/trust.md describes.
val devHome = providers.gradleProperty("cringleHome").orElse(layout.buildDirectory.dir("dev-home").map { it.asFile.absolutePath })

fun registerProgram(module: String, taskName: String, mainClassName: String, summary: String, vararg defaultArgs: String) {
    project(":$module").tasks.register<JavaExec>(taskName) {
        group = "cringle"
        description = summary
        classpath = project(":$module").extensions.getByType<SourceSetContainer>().getByName("main").runtimeClasspath
        mainClass.set(mainClassName)
        args(*defaultArgs)
        environment("CRINGLE_HOME", devHome.get())
        // the programs of build/dev-home trust each other (docs/trust.md, "Local trust") and the command line finds the management server
        environment("CRINGLE_TRUST_LOCAL", "1")
        environment("CRINGLE_SERVER", "127.0.0.1:7500")
        standardInput = System.`in`
    }
}

registerProgram("daemon", "runDaemon", "cringle.daemon.MainKt", "Runs the daemon with its own router (combined mode) on port 7400; it trusts the management server of build/dev-home.", "--port", "7400", "--combined")
registerProgram("management-server", "runManagementServer", "cringle.management.MainKt", "Runs the management server on port 7500 and the WebUI on https://127.0.0.1:8443, without logins (everybody on this machine is administrator); trusts the daemon and the engines of build/dev-home. Add --auth with --args for logins.", "--port", "7500", "--web-port", "8443", "--machine", "local=127.0.0.1:7400", "--repository", "127.0.0.1:7600")
registerProgram("engine", "runEngine", "cringle.engine.MainKt", "Runs one engine by itself, to debug its start. The daemon and the management server do not know it, so it is not in engine list: create engines with `engine create` and `engine start` (the daemon starts them).", "--id", "dev-engine")
registerProgram("cli", "runCli", "cringle.cli.MainKt", "Runs the cringle command line; pass the command with --args, e.g. --args=\"login --server 127.0.0.1:7500 --fingerprint <sha256>\".", "--help")

registerProgram("cli", "runShell", "cringle.cli.MainKt", "Runs the cringle command line in interactive mode (`cringle shell`): type commands, `exit` leaves; the standard input is the console. Global options (--server, --home, --json) go before `shell` with --args.", "shell")

registerProgram("repository", "runRepository", "cringle.repository.MainKt", "Runs the package repository on port 7600, without logins; trusts the management server, the engines and the Gradle plugin of build/dev-home. Start it before the engines are created: they trust it from their start on. Add --auth with --args for logins.", "--port", "7600")
