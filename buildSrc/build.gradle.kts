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
}
