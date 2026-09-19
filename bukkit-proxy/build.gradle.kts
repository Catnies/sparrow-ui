import com.github.jengelman.gradle.plugins.shadow.transformers.DontIncludeResourceTransformer

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
    maven("https://libraries.minecraft.net/")
    maven("https://repo.catnies.top/releases/")
    maven("https://repo.momirealms.net/releases/")
    maven("https://repo.papermc.io/repository/maven-public/") // Paper 仓库
}

dependencies {
    compileOnly(libs.paper.api)
    compileOnly(libs.datafixerupper)
    compileOnly(libs.netty.transport)
    implementation(libs.asm)
    implementation(libs.asm.commons)
    implementation(libs.jar.relocator)
    implementation(libs.sparrow.reflection)
}

tasks {
    shadowJar {
        transform<DontIncludeResourceTransformer> {
            resource = "META-INF/MANIFEST.MF"
        }
        archiveClassifier = ""
        archiveFileName = "sparrow-ui-proxy.jar"
        relocate("net.momirealms.sparrow.reflection", "net.momirealms.sparrow.ui.libraries.reflection")
        relocate("org.objectweb.asm", "net.momirealms.sparrow.ui.libraries.asm")
        relocate("me.lucko.jarrelocator", "net.momirealms.sparrow.ui.libraries.jarrelocator")
    }
}