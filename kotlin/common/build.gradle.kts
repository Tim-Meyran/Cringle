// SPDX-License-Identifier: Apache-2.0

import com.google.protobuf.gradle.id

plugins {
    alias(libs.plugins.protobuf)
    `java-test-fixtures`
}

val versionCatalog = extensions.getByType<VersionCatalogsExtension>().named("libs")
val protobufVersion: String = versionCatalog.findVersion("protobuf").get().requiredVersion
val grpcVersion: String = versionCatalog.findVersion("grpc").get().requiredVersion
val grpcKotlinVersion: String = versionCatalog.findVersion("grpc-kotlin").get().requiredVersion

dependencies {
    api(libs.protobuf.java)
    api(libs.protobuf.kotlin)
    api(libs.grpc.protobuf)
    api(libs.grpc.stub)
    api(libs.grpc.kotlin.stub)
    api(libs.kotlinx.coroutines.core)
    api(libs.grpc.netty.shaded)
    api(libs.slf4j.api)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.bouncycastle.prov)
    implementation(libs.bouncycastle.pkix)
    implementation(libs.logback.classic)
    testImplementation(libs.kotlinx.coroutines.test)
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:$protobufVersion"
    }
    plugins {
        id("grpc") {
            artifact = "io.grpc:protoc-gen-grpc-java:$grpcVersion"
        }
        id("grpckt") {
            artifact = "io.grpc:protoc-gen-grpc-kotlin:$grpcKotlinVersion:jdk8@jar"
        }
    }
    generateProtoTasks {
        all().forEach { task ->
            task.plugins {
                id("grpc")
                id("grpckt")
            }
            task.builtins {
                id("kotlin")
            }
        }
    }
}

sourceSets {
    main {
        proto {
            srcDir(rootProject.file("proto"))
        }
    }
}
