plugins {
    `java-library`
    alias(libs.plugins.shadow)
    id("xyz.jpenilla.run-paper") version "3.0.2"
}

group = "net.momirealms"
version = "1.0.0"

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
        minecraftVersion("1.21.8")
    }
}

runPaper.folia.registerTask {
    minecraftVersion("1.21.8")
}
