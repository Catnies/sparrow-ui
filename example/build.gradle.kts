import net.minecrell.pluginyml.bukkit.BukkitPluginDescription

plugins {
    `java-library`
    alias(libs.plugins.shadow)
    id("de.eldoria.plugin-yml.paper") version "0.9.0"
    id("sparrow-ui.run-servers")
}

group = "net.momirealms"
version = "1.0.0"
description = "Reference examples for SparrowUI"
paperweight.reobfArtifactConfiguration = io.papermc.paperweight.userdev.ReobfArtifactConfiguration.MOJANG_PRODUCTION

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    implementation(project(":core"))
    paperweight.paperDevBundle(libs.versions.paper.api.get())
    compileOnly(libs.jetbrains.annotations)
    compileOnly(libs.jspecify)
}

tasks {
    assemble {
        dependsOn(shadowJar)
    }
}

paper {
    name = "SparrowUIExample"
    main = "net.momirealms.sparrow.ui.example.SparrowExample"
    apiVersion = "1.21.4"
    foliaSupported = true

    permissions {
        register("sparrowui.example") {
            description = "Allows using the SparrowUI example commands"
            default = BukkitPluginDescription.Permission.Default.OP
        }
    }
}
