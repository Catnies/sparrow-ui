plugins {
    `java-library`
    alias(libs.plugins.shadow)
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
    implementation(libs.asm)
    implementation(libs.nyana.reflection)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.paper.api)
    testRuntimeOnly(libs.junit.platformLauncher)
}

tasks.shadowJar {
    archiveClassifier = ""
    archiveFileName = "proxy.jarinjar"
    relocate("net.nyana.reflection", "net.nyana.sparrow.ui.libraries.reflection")
    relocate("org.objectweb.asm", "net.nyana.sparrow.ui.libraries.asm")
}

tasks.test {
    useJUnitPlatform()
}
