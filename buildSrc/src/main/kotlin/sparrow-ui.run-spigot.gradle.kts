import net.momirealms.sparrow.ui.gradle.InitializeRunDirectory
import org.gradle.api.tasks.bundling.Jar
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.jvm.toolchain.JvmVendorSpec
import xyz.jpenilla.runpaper.task.RunServer

plugins {
    id("xyz.jpenilla.run-paper")
}

val runTemplatesDirectory = rootProject.layout.projectDirectory.dir("buildSrc/run-templates")
val javaToolchains = extensions.getByType<JavaToolchainService>()
val java21 = javaToolchains.launcherFor {
    vendor = JvmVendorSpec.JETBRAINS
    languageVersion = JavaLanguageVersion.of(21)
}
val java25 = javaToolchains.launcherFor {
    vendor = JvmVendorSpec.JETBRAINS
    languageVersion = JavaLanguageVersion.of(25)
}
val bukkitPlugins = rootProject.fileTree("buildSrc/bukkit-plugins") {
    include("*.jar")
}
val spigotJars = rootProject.fileTree("buildSrc/server-jars") {
    include("spigot-*.jar")
}

for (spigotJar in spigotJars.sortedBy { it.name }) {
    val minecraftVersion = spigotJar.name.removePrefix("spigot-").removeSuffix(".jar")
    val directory = "run/spigot/$minecraftVersion"
    val prepare = tasks.register<InitializeRunDirectory>("prepareSpigot_$minecraftVersion") {
        templateDirectories.from(
            runTemplatesDirectory.dir("backend/common"),
            runTemplatesDirectory.dir("backend/spigot")
        )
        targetDirectory.set(layout.projectDirectory.dir(directory))
    }
    tasks.register<RunServer>("runSpigot_$minecraftVersion") {
        group = "run paper"
        displayName.set("Spigot $minecraftVersion")
        description = "Run a Spigot $minecraftVersion server with the example plugin."
        minecraftVersion(minecraftVersion)
        runDirectory.set(layout.projectDirectory.dir(directory))
        pluginJars.from(tasks.named<Jar>("bukkitJar").flatMap { it.archiveFile }, bukkitPlugins)
        legacyPluginLoading()
        serverJar(spigotJar)
        javaLauncher.set(if (minecraftVersion.startsWith("26.")) java25 else java21)

        systemProperties["Paper.IgnoreJavaVersion"] = true
        systemProperties["net.momirealms.plugin.dev"] = true
        jvmArgs(
            "-Dfile.encoding=UTF-8",
            "-Dsun.stdout.encoding=UTF-8",
            "-Dsun.stderr.encoding=UTF-8"
        )
        dependsOn(prepare)
    }
}
