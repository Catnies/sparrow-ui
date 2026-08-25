import org.gradle.accessors.dm.LibrariesForLibs

plugins {
    `java-library`
}

val libs = the<LibrariesForLibs>()
group = "net.momirealms"
version = libs.versions.project.version.get()

java {
    withSourcesJar()
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
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
    compileOnly(libs.paper.api)
    compileOnly(libs.netty.transport)
    compileOnly(libs.netty.codec.base)
    implementation(libs.jetbrains.annotations)
    implementation(libs.jspecify)
    implementation(libs.gson)
    
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platformLauncher)
    testImplementation(libs.mockbukkit)
    testImplementation(libs.test.paper.api)
    testImplementation(libs.netty.codec.base)
//    testImplementation(libs.logback.classic)
}

tasks {
    test {
        useJUnitPlatform()
    }
}
