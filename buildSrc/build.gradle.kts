plugins {
    `kotlin-dsl`
}

repositories {
    gradlePluginPortal()
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

dependencies {
    // https://github.com/gradle/gradle/issues/15383#issuecomment-779893192
    implementation(files(libs.javaClass.superclass.protectionDomain.codeSource.location))
    implementation("xyz.jpenilla.run-paper:xyz.jpenilla.run-paper.gradle.plugin:${libs.versions.run.task.get()}")
    implementation("io.papermc.paperweight.userdev:io.papermc.paperweight.userdev.gradle.plugin:${libs.versions.paperweight.get()}")
}
