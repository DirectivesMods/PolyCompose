plugins {
    `java-library`
    `maven-publish`
}

apply(from = rootProject.file("gradle/skiko-remap.gradle.kts"))

@Suppress("UNCHECKED_CAST")
val skikoRemapped = extra["skikoRemappedAttribute"] as Attribute<Boolean>

fun dep(name: String): String = property("deps.$name") as String

group = property("mod.group") as String
version = property("mod.version") as String
base.archivesName = "polycompose-compose-ui"

val stockUi = "org.jetbrains.compose.ui:ui-desktop:${dep("compose")}"
val stockOnly = setOf("stockUiJar")

configurations.configureEach {
    exclude(group = "org.jetbrains.skiko")
    exclude(group = "org.jetbrains.compose.ui", module = "ui-skiko")
    exclude(group = "org.jetbrains.compose.ui", module = "ui-skiko-desktop")
    if (name !in stockOnly) {
        exclude(group = "org.jetbrains.compose.ui", module = "ui")
        exclude(group = "org.jetbrains.compose.ui", module = "ui-desktop")
    }
}

val stockUiJar: Configuration = configurations.create("stockUiJar") {
    isCanBeConsumed = false
    isTransitive = false
    attributes.attribute(skikoRemapped, true)
}

dependencies {
    stockUiJar(stockUi)
}

val stockPom = configurations.detachedConfiguration(dependencies.create("$stockUi@pom")).singleFile.readText()
    .replace(Regex("<dependencyManagement>.*?</dependencyManagement>", RegexOption.DOT_MATCHES_ALL), "")

fun pomTag(block: String, tag: String): String? = Regex("<$tag>(.*?)</$tag>").find(block)?.groupValues?.get(1)?.trim()

for (block in Regex("<dependency>(.*?)</dependency>", RegexOption.DOT_MATCHES_ALL).findAll(stockPom).map { it.groupValues[1] }) {
    val group = pomTag(block, "groupId") ?: continue
    val artifact = pomTag(block, "artifactId") ?: continue
    val version = pomTag(block, "version") ?: continue
    if (group == "org.jetbrains.skiko" || artifact.startsWith("ui-skiko")) continue
    val configuration = if (pomTag(block, "scope") == "runtime") "runtimeOnly" else "api"
    dependencies.add(configuration, "$group:$artifact:$version")
}

tasks.jar {
    dependsOn(stockUiJar)
    from(stockUiJar.elements.map { jars -> jars.map { zipTree(it) } }) {
        exclude("META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA", "META-INF/MANIFEST.MF")
    }
    manifest {
        attributes("Fabric-Loom-Remap" to false)
    }
}

configurations.matching { it.name == "apiElements" || it.name == "runtimeElements" }.configureEach {
    outgoing.variants.removeAll { true }
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
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
            artifactId = "polycompose-compose-ui"
            suppressAllPomMetadataWarnings()
        }
    }
}
