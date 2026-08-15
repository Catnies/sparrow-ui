plugins {
    id("maven-publish")
    id("common-conventions")
}

val mapColorGenerator = sourceSets.create("mapColorGenerator")
val mapColorProfileResources = layout.buildDirectory.dir("generated/resources/mapColorProfile")
val mapColorProfileFile = mapColorProfileResources.map { it.file("map-color-profile.bin") }

dependencies {
    compileOnly(project(":bukkit-proxy"))
    compileOnly(libs.datafixerupper)
    testImplementation(project(":bukkit-proxy"))
    testImplementation(libs.datafixerupper)
    add(mapColorGenerator.implementationConfigurationName, sourceSets.main.get().output.classesDirs)
    add(mapColorGenerator.implementationConfigurationName, libs.paper.api)
}

val bukkitProxyJar = project(":bukkit-proxy").tasks.named<Jar>("shadowJar")
val generateMapColorProfile = tasks.register<JavaExec>("generateMapColorProfile") {
    group = "build setup"
    description = "Generates the compact map color profile resource"
    dependsOn(mapColorGenerator.classesTaskName)
    classpath = mapColorGenerator.runtimeClasspath
    mainClass = "net.momirealms.sparrow.ui.internal.map.MapColorProfileGenerator"
    args(mapColorProfileResources.get().asFile.absolutePath)
    outputs.file(mapColorProfileFile)
}

tasks.processResources {
    dependsOn(bukkitProxyJar, generateMapColorProfile)
    from(bukkitProxyJar.flatMap { it.archiveFile })
    from(mapColorProfileFile)
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            artifactId = "sparrow-ui"
            from(components["java"])
            version = project.version.toString()
            pom {
                name = "Sparrow UI"
                url = "https://github.com/Catnies/sparrow-ui"
            }
        }
    }

    repositories {
        maven {
            name = "XiaoMoMi"
            url = uri("https://repo.momirealms.net/snapshots")
            credentials(PasswordCredentials::class)
            authentication {
                create<BasicAuthentication>("basic")
            }
        }
    }
}
