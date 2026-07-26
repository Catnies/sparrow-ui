plugins {
    id("common-conventions")
}

dependencies {
    compileOnly(project(":bukkit-proxy"))
    compileOnly(libs.datafixerupper)
    testImplementation(project(":bukkit-proxy"))
}

tasks.processResources {
    val bukkitProxyJar = project(":bukkit-proxy").tasks.named<Jar>("shadowJar")
    dependsOn(bukkitProxyJar)
    from(bukkitProxyJar.flatMap { it.archiveFile })
}