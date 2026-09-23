import net.minecrell.pluginyml.bukkit.BukkitPluginDescription
import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    `java-library`
    alias(libs.plugins.shadow)
    id("de.eldoria.plugin-yml.bukkit") version "0.9.0"
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

sourceSets.main {
    java.setSrcDirs(emptyList<String>())
}
val paperExample = sourceSets.create("paper") {
    java.setSrcDirs(listOf("src/main/java", "src/paper/java"))
}
configurations[paperExample.implementationConfigurationName].extendsFrom(configurations.implementation.get())
configurations[paperExample.compileOnlyConfigurationName].extendsFrom(configurations.compileOnly.get())
val bukkitExample = sourceSets.create("bukkit") {
    java.setSrcDirs(listOf("src/main/java", "src/bukkit/java"))
    resources.srcDir("src/main/resources")
}
configurations[bukkitExample.implementationConfigurationName].extendsFrom(configurations.implementation.get())

val spigotClasspathDirectory = layout.buildDirectory.dir("spigot-classpath")
val extractSpigotClasspath = tasks.register<Sync>("extractSpigotClasspath") {
    from(zipTree(rootProject.file("buildSrc/server-jars/spigot-26.2.jar"))) {
        include("META-INF/versions/*.jar", "META-INF/libraries/*.jar")
    }
    into(spigotClasspathDirectory)
}
val spigotClasspath = files(fileTree(spigotClasspathDirectory) { include("**/*.jar") }).builtBy(extractSpigotClasspath)
val adventureVersion = "5.2.0"
val cloudVersion = "2.0.0-beta.15"

dependencies {
    implementation(project(":core"))
    implementation("org.incendo:cloud-paper:$cloudVersion")
    paperweight.paperDevBundle(libs.versions.paper.api.get())
    compileOnly(libs.jetbrains.annotations)
    compileOnly(libs.jspecify)
    add(bukkitExample.compileOnlyConfigurationName, spigotClasspath)
    add(bukkitExample.compileOnlyConfigurationName, project(":bukkit-proxy"))
    add(bukkitExample.compileOnlyConfigurationName, libs.jetbrains.annotations)
    add(bukkitExample.compileOnlyConfigurationName, libs.jspecify)
    add(bukkitExample.implementationConfigurationName, platform("net.kyori:adventure-bom:$adventureVersion"))
    add(bukkitExample.implementationConfigurationName, "net.kyori:adventure-api")
    add(bukkitExample.implementationConfigurationName, "net.kyori:adventure-text-serializer-gson")
    add(bukkitExample.implementationConfigurationName, "net.kyori:adventure-text-serializer-legacy")
}

tasks.named<JavaCompile>(bukkitExample.compileJavaTaskName) {
    javaCompiler.set(javaToolchains.compilerFor { languageVersion = JavaLanguageVersion.of(25) })
}
tasks.named<ProcessResources>(bukkitExample.processResourcesTaskName) {
    from(tasks.named("generateBukkitPluginDescription"))
}

tasks.withType<ShadowJar>().configureEach {
    mergeServiceFiles()
    relocate("org.incendo.cloud", "net.momirealms.sparrow.ui.example.libraries.cloud")
    relocate("io.leangen.geantyref", "net.momirealms.sparrow.ui.example.libraries.geantyref")
}
val bukkitJar = tasks.register<ShadowJar>("bukkitJar") {
    group = "build"
    description = "Builds the Spigot 26.2 example plugin with Adventure 5.2.0."
    archiveClassifier.set("bukkit")
    from(bukkitExample.output)
    configurations = listOf(project.configurations[bukkitExample.runtimeClasspathConfigurationName])
    // Bukkit 使用插件私有 Adventure, 文本通过 core 的 JSON 边界传入 NMS.
    relocate("net.kyori", "net.momirealms.sparrow.ui.example.libraries.kyori")
}
tasks.named<ShadowJar>("shadowJar") {
    archiveClassifier.set("paper")
    from(paperExample.output)
    configurations = listOf(project.configurations[paperExample.runtimeClasspathConfigurationName])
}
tasks.register("paperJar") {
    group = "build"
    description = "Builds the Paper/Folia example plugin."
    dependsOn(tasks.shadowJar)
}
tasks {
    jar {
        enabled = false
    }
    assemble {
        dependsOn(shadowJar, bukkitJar)
    }
}

bukkit {
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
