plugins {
    id("maven-publish")
    id("common-conventions")
}

// 与远程仓库的快照坐标一致, publishToMavenLocal 后可被本地项目优先解析
version = "beta.5-SNAPSHOT"

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
            name = "XiaoMoMi"
            url = uri("https://repo.momirealms.net/snapshots")
            credentials(PasswordCredentials::class)
            authentication {
                create<BasicAuthentication>("basic")
            }
        }
    }
}