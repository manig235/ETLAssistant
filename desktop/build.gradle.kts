import org.gradle.api.artifacts.component.ModuleComponentIdentifier

plugins {
    kotlin("jvm") version "2.3.20"
    application
}

group = "kr.snu.youngmini"
version = "0.5.1"

kotlin {
    jvmToolchain(17)
    sourceSets.main {
        kotlin.srcDir("../app/src/main/java")
        kotlin.srcDir("src/portable/kotlin")
        kotlin.include("kr/snu/youngmini/portable/**")
        kotlin.include("kr/snu/youngmini/desktop/**")
        listOf("EtlClient", "EtlLogic", "EtlFiles", "Queries", "CourseAssignments", "LinkedFileAccess", "HwpText", "XlsxText", "Board").forEach {
            kotlin.include("kr/snu/etlassistant/etl/$it.kt")
        }
        listOf("AppServerClient", "ChatGptCredentials", "ChatGptOAuth", "ModelCatalog").forEach {
            kotlin.include("kr/snu/etlassistant/codex/$it.kt")
        }
        kotlin.include("kr/snu/etlassistant/mcp/McpServer.kt")
    }
    sourceSets.test {
        kotlin.srcDir("../app/src/test/java")
        kotlin.include("kr/snu/youngmini/desktop/**")
        listOf("EtlClient", "EtlFiles", "LinkedFileAccess", "XlsxText", "HwpText").forEach {
            kotlin.include("kr/snu/etlassistant/etl/${it}Test.kt")
        }
        kotlin.include("kr/snu/etlassistant/mcp/McpServerTest.kt")
        kotlin.include("kr/snu/etlassistant/codex/ModelCatalogTest.kt")
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
    implementation("org.json:json:20240303")
    implementation("net.java.dev.jna:jna-platform:5.17.0")
    implementation("org.apache.pdfbox:pdfbox:3.0.5")
    implementation("org.apache.poi:poi-ooxml:6.0.0")
    testImplementation(kotlin("test-junit"))
}

application { mainClass.set("kr.snu.youngmini.desktop.BridgeMainKt") }

// Resolve on Linux too: the portable Windows ZIP is assembled without jpackage.
val runtimeArtifactGroups = providers.provider {
    configurations.runtimeClasspath.get().incoming.artifacts.artifacts.associate {
        it.file to (it.id.componentIdentifier as? ModuleComponentIdentifier)?.group
    }
}
tasks.register<Sync>("stageWindows") {
    dependsOn(tasks.jar)
    into(layout.buildDirectory.dir("windows-stage/lib"))
    from(tasks.jar)
    from(configurations.runtimeClasspath) {
        // JetBrains' relocation JAR and AndroidX's implementation can have the same
        // filename. Preserve both, independent of the Gradle cache layout.
        eachFile { runtimeArtifactGroups.get()[file]?.let { group -> name = "$group-$name" } }
    }
}

tasks.test {
    systemProperty("java.awt.headless", "true")
    testLogging { events("failed", "skipped"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
