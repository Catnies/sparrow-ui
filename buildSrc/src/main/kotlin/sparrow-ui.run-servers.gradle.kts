import net.momirealms.sparrow.ui.gradle.InitializeRunDirectory
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.bundling.Jar
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaLauncher
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.jvm.toolchain.JvmVendorSpec
import xyz.jpenilla.runpaper.task.RunServer
import xyz.jpenilla.runtask.service.DownloadsAPIService

// 插件
plugins {
    id("io.papermc.paperweight.userdev")
    id("xyz.jpenilla.run-paper")
}

// 运行环境
val javaToolchains = extensions.getByType<JavaToolchainService>()
val java21 = javaToolchains.launcherFor {
    vendor = JvmVendorSpec.JETBRAINS
    languageVersion = JavaLanguageVersion.of(21)
}
val java25 = javaToolchains.launcherFor {
    vendor = JvmVendorSpec.JETBRAINS
    languageVersion = JavaLanguageVersion.of(25)
}

// 按版本挑 JDK
fun javaLauncherFor(minecraftVersion: String): Provider<JavaLauncher> =
        if (minecraftVersion.startsWith("26.")) java25 else java21

// 版本表
val paperVersions = listOf("1.21.4", "1.21.5", "1.21.6", "1.21.8", "1.21.10", "1.21.11", "26.1.2", "26.2", "26.3")
val foliaVersions = listOf("1.21.4", "1.21.5", "1.21.6", "1.21.8", "1.21.11", "26.1.2", "26.2")

// 运行目录模板
// 只补运行目录里缺的文件, 已经存在的配置和世界原样留下
val runTemplatesDirectory = rootProject.layout.projectDirectory.dir("buildSrc/run-templates")

// 插件自带的那两个任务没有对应的运行目录, 关掉免得误启动
tasks.withType<RunServer>().configureEach {
    if (name == "runServer" || name == "runDevBundleServer") {
        group = null
        enabled = false
    }
}

// 服务端公共配置
fun RunServer.configureServer(display: String, minecraftVersion: String, directory: String, jarTask: String = "shadowJar") {
    group = "run paper"
    displayName.set(display)
    minecraftVersion(minecraftVersion)
    runDirectory.set(layout.projectDirectory.dir(directory))
    pluginJars.from(tasks.named<Jar>(jarTask).flatMap { it.archiveFile })
    // 启动前复制到各自的运行目录, 服务端因此不必一直握着共享的 shadowJar
    legacyPluginLoading()
    javaLauncher.set(javaLauncherFor(minecraftVersion))

    systemProperties["Paper.IgnoreJavaVersion"] = true
    jvmArgs(
        "-Dfile.encoding=UTF-8",
        "-Dsun.stdout.encoding=UTF-8",
        "-Dsun.stderr.encoding=UTF-8"
    )
}

// 任务注册
for (minecraftVersion in paperVersions) {
    val directory = "run/paper/$minecraftVersion"
    val prepare = tasks.register<InitializeRunDirectory>("preparePaper_$minecraftVersion") {
        templateDirectories.from(
            runTemplatesDirectory.dir("backend/common"),
            runTemplatesDirectory.dir("backend/paper")
        )
        targetDirectory.set(layout.projectDirectory.dir(directory))
    }
    tasks.register<RunServer>("runPaper_$minecraftVersion") {
        description = "Run a Paper $minecraftVersion server with the example plugin."
        configureServer("Paper $minecraftVersion", minecraftVersion, directory)
        dependsOn(prepare)
    }
}

for (minecraftVersion in foliaVersions) {
    val directory = "run/folia/$minecraftVersion"
    val prepare = tasks.register<InitializeRunDirectory>("prepareFolia_$minecraftVersion") {
        templateDirectories.from(
            runTemplatesDirectory.dir("backend/common"),
            runTemplatesDirectory.dir("backend/folia")
        )
        targetDirectory.set(layout.projectDirectory.dir(directory))
    }
    tasks.register<RunServer>("runFolia_$minecraftVersion") {
        description = "Run a Folia $minecraftVersion server with the example plugin."
        configureServer("Folia $minecraftVersion", minecraftVersion, directory)
        downloadsApiService.set(DownloadsAPIService.folia(project))
        dependsOn(prepare)
    }
}

// Spigot 使用本地服务端 Jar 和独立的 Bukkit 插件目录.
val spigotJar = rootProject.layout.projectDirectory.file("buildSrc/server-jars/spigot-26.2.jar")
if (spigotJar.asFile.isFile) {
    val directory = "run/spigot/26.2"
    val prepare = tasks.register<InitializeRunDirectory>("prepareSpigot_26.2") {
        templateDirectories.from(
            runTemplatesDirectory.dir("backend/common"),
            runTemplatesDirectory.dir("backend/spigot")
        )
        targetDirectory.set(layout.projectDirectory.dir(directory))
    }
    tasks.register<RunServer>("runSpigot_26.2") {
        description = "Run a Spigot 26.2 server with the example plugin."
        configureServer("Spigot 26.2", "26.2", directory, "bukkitJar")
        pluginJars.from(rootProject.fileTree("buildSrc/bukkit-plugins") {
            include("*.jar")
        })
        serverJar(spigotJar.asFile)
        dependsOn(prepare)
    }
}
