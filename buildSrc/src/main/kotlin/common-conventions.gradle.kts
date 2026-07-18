import org.gradle.accessors.dm.LibrariesForLibs

plugins {
    `java-library`
    id("io.papermc.paperweight.userdev")
}

val libs = the<LibrariesForLibs>()
group = "net.momirealms"
version = "1.0.0"

java {
    withSourcesJar()
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
    maven("https://jitpack.io/")
    maven("https://libraries.minecraft.net/")
    maven("https://repo.catnies.top/releases/")
    maven("https://repo.momirealms.net/releases/")
    maven("https://repo.papermc.io/repository/maven-public/") // Paper 仓库
}

dependencies {
    paperweight.paperDevBundle(libs.versions.paper.api.get())
    implementation(libs.jetbrains.annotations)
    implementation(libs.jspecify)
    implementation(libs.gson)
    compileOnly(libs.nyana.reflection)
    
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platformLauncher)
    testImplementation(libs.mockbukkit)
    testImplementation(libs.test.paper.api)
//    testImplementation(libs.logback.classic)
}

paperweight {
    addServerDependencyTo.add(configurations.named(JavaPlugin.COMPILE_ONLY_CONFIGURATION_NAME))
}

tasks {
    test {
        useJUnitPlatform()
    }
}
