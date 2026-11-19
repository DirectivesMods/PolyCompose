@file:OptIn(ExperimentalAbiValidation::class)

import dev.detekt.gradle.Detekt
import net.ltgt.gradle.errorprone.errorprone
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    `java-library`
    `maven-publish`
    kotlin("jvm")
    kotlin("plugin.compose")
    id("dev.detekt")
    id("net.ltgt.errorprone")
    checkstyle
}

apply(from = rootProject.file("gradle/skiko-remap.gradle.kts"))

fun dep(name: String): String = property("deps.$name") as String

group = property("mod.group") as String
version = property("mod.version") as String
base.archivesName = "polycompose-core"

val compose = dep("compose")
val twelvemonkeys = dep("twelvemonkeys")

configurations.configureEach {
    exclude(group = "org.jetbrains.skiko")
    exclude(group = "org.jetbrains.compose.ui", module = "ui-skiko")
    exclude(group = "org.jetbrains.compose.ui", module = "ui-skiko-desktop")
}

dependencies {
    implementation("org.jetbrains:annotations:${dep("annotations")}")
    compileOnly("org.apache.logging.log4j:log4j-api:${dep("log4j_api")}")

    compileOnly("org.jetbrains.compose.runtime:runtime-desktop:$compose")
    compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-core:${dep("kotlinx_coroutines")}")
    compileOnly("com.ibm.icu:icu4j:${dep("icu4j")}")
    api("org.jetbrains.compose.foundation:foundation-desktop:$compose")
    api("org.jetbrains.compose.material:material-desktop:$compose")
    api("org.jetbrains.compose.ui:ui-desktop:$compose")
    api("org.jetbrains.compose.ui:ui-util-desktop:$compose")
    for (module in listOf("imageio:imageio-webp", "imageio:imageio-bmp", "imageio:imageio-core", "imageio:imageio-metadata", "common:common-lang", "common:common-io", "common:common-image")) {
        implementation("com.twelvemonkeys.$module:$twelvemonkeys")
    }

    testImplementation(platform("org.junit:junit-bom:${dep("junit")}"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.apache.logging.log4j:log4j-core:${dep("log4j_core")}")
    testImplementation("org.jetbrains.compose.runtime:runtime-desktop:$compose")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:${dep("kotlinx_coroutines")}")
    testImplementation("com.ibm.icu:icu4j:${dep("icu4j")}")

    errorprone("com.google.errorprone:error_prone_core:${dep("errorprone")}")
}

java {
    withSourcesJar()
    withJavadocJar()
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        moduleName = "polycompose"
        allWarningsAsErrors = true
        extraWarnings = true
        progressiveMode = true
        freeCompilerArgs.add("-Xwarning-level=REDUNDANT_VISIBILITY_MODIFIER:disabled")
    }
    abiValidation {
        filters.exclude {
            annotatedWith.add("org.jetbrains.annotations.ApiStatus.Internal")
        }
    }
}

tasks.named<KotlinCompile>("compileTestKotlin") {
    compilerOptions.freeCompilerArgs.add("-Xjspecify-annotations=ignore")
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.addAll(listOf("-Xlint:all,-serial,-classfile,-processing", "-Werror"))
    options.errorprone {
        disableAllChecks = true
        error("UnnecessarilyFullyQualified")
    }
}

tasks.withType<Javadoc>().configureEach {
    (options as CoreJavadocOptions).addBooleanOption("Xdoclint:none", true)
}

tasks.named<Detekt>("detekt") {
    enabled = false
    dependsOn(tasks.withType<Detekt>().matching { it.name != "detekt" && !it.name.endsWith("SourceSet") })
}

tasks.named("check") {
    dependsOn("checkKotlinAbi")
}

tasks.named("checkKotlinAbi") {
    mustRunAfter("updateKotlinAbi")
}

tasks.test {
    useJUnitPlatform()
    outputs.upToDateWhen { false }
    testLogging {
        showStackTraces = true
        exceptionFormat = TestExceptionFormat.FULL
    }
}

tasks.jar {
    manifest {
        attributes("Fabric-Loom-Remap" to false)
    }
}

publishing {
    repositories {
        listOf("releases", "snapshots").forEach { type ->
            maven("https://repo.polyfrost.org/$type") {
                name = type
                credentials {
                    username = providers.gradleProperty("polyfrostRepoUsername").orNull
                    password = providers.gradleProperty("polyfrostRepoToken").orNull
                }
                authentication { create<BasicAuthentication>("basic") }
            }
        }
    }
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            artifactId = "polycompose-core"
            suppressAllPomMetadataWarnings()
        }
    }
}
