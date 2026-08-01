plugins {
    id("maven-publish")
    id("common-conventions")
}

dependencies {
    compileOnly(project(":bukkit-proxy"))
    compileOnly(libs.datafixerupper)
    testImplementation(project(":bukkit-proxy"))
}

val bukkitProxyJar = project(":bukkit-proxy").tasks.named<Jar>("shadowJar")
tasks.processResources {
    dependsOn(bukkitProxyJar)
    from(bukkitProxyJar.flatMap { it.archiveFile })
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
            name = "Catnies"
            url = uri("https://repo.catnies.top/snapshots")
            credentials(PasswordCredentials::class)
            authentication {
                create<BasicAuthentication>("basic")
            }
        }
    }
}