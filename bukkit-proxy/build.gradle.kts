plugins {
    `java-library`
    alias(libs.plugins.shadow)
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
    maven("https://jitpack.io/")
    maven("https://repo.catnies.top/releases/")
    maven("https://repo.momirealms.net/releases/")
    maven("https://repo.papermc.io/repository/maven-public/") // Paper 仓库
}

dependencies {
    compileOnly(libs.paper.api)
    compileOnly(libs.datafixerupper)
    compileOnly(libs.netty.transport)
    implementation(libs.asm)
    implementation(libs.sparrow.reflection)
}

tasks.shadowJar {
    archiveClassifier = ""
    archiveFileName = "sparrow-ui-proxy.jarinjar"
    relocate("net.momirealms.sparrow.reflection", "net.momirealms.sparrow.ui.libraries.reflection")
    relocate("org.objectweb.asm", "net.momirealms.sparrow.ui.libraries.asm")
}