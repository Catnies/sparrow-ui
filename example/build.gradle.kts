import net.minecrell.pluginyml.bukkit.BukkitPluginDescription
import xyz.jpenilla.runpaper.task.RunServer
import xyz.jpenilla.runtask.service.DownloadsAPIService

plugins {
    `java-library`
    alias(libs.plugins.shadow)
    id("de.eldoria.plugin-yml.paper") version "0.9.0"
    id("xyz.jpenilla.run-paper") version "3.0.2"
}

group = "net.momirealms"
version = "1.0.0"
description = "Reference examples for SparrowUI"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    implementation(project(":core"))
    compileOnly("io.papermc.paper:paper-api:26.2.build.+")
    compileOnly(libs.jetbrains.annotations)
    compileOnly(libs.jspecify)
}

tasks {
    assemble {
        dependsOn(shadowJar)
    }

    runServer {
        group = null
    }
}

paper {
    name = "SparrowUIExample"
    main = "net.momirealms.sparrow.ui.example.SparrowExample"
    apiVersion = "1.21.8"
    foliaSupported = true

    permissions {
        register("sparrowui.example") {
            description = "Allows using the SparrowUI example commands"
            default = BukkitPluginDescription.Permission.Default.OP
        }
    }
}

val exampleJar = tasks.shadowJar.flatMap { it.archiveFile }
val minecraftVersions = listOf("1.21.8", "1.21.11", "26.1.2", "26.2")
for (minecraftVersion in minecraftVersions) {
    tasks.register<RunServer>("runPaper_$minecraftVersion") {
        group = "run paper"
        description = "Run a Paper $minecraftVersion server with the example plugin."
        displayName.set("Paper $minecraftVersion")
        minecraftVersion(minecraftVersion)
        runDirectory.set(layout.projectDirectory.dir("run/paper/$minecraftVersion"))
        pluginJars.from(exampleJar)
    }

    tasks.register<RunServer>("runFolia_$minecraftVersion") {
        group = "run paper"
        description = "Run a Folia $minecraftVersion server with the example plugin."
        displayName.set("Folia $minecraftVersion")
        downloadsApiService.set(DownloadsAPIService.folia(project))
        minecraftVersion(minecraftVersion)
        runDirectory.set(layout.projectDirectory.dir("run/folia/$minecraftVersion"))
        pluginJars.from(exampleJar)
    }
}
