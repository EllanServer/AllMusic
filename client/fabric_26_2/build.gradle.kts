plugins {
    id("net.fabricmc.fabric-loom") version Versions.fabricLoom
}

repositories {
    maven("https://maven.caffeinemc.net/releases")
}

java.sourceCompatibility = JavaVersion.VERSION_25
java.targetCompatibility = JavaVersion.VERSION_25

dependencies {
    minecraft("com.mojang:minecraft:26.2")
    implementation("net.fabricmc:fabric-loader:0.19.3")

    implementation("net.fabricmc.fabric-api:fabric-api:0.152.1+26.2")

    compileOnly("net.caffeinemc:sodium-fabric-api:0.9.1+mc26.2")

    compileOnly("de.maxhenkel.voicechat:voicechat-api:2.6.0")
}

tasks {
    processResources {
        from(rootProject.file("LICENSE")) {
            into("META-INF")
            rename { "LICENSE-AllMusic.txt" }
        }
        filesMatching("fabric.mod.json") {
            expand(
                "version" to project.version
            )
        }
    }

    shadowJar {
        archiveFileName.set("[fabric-26.2]AllMusic_Client-${project.version}.jar")
        destinationDirectory.set(file("${parent!!.projectDir}/../build"))
    }

    build {
        dependsOn(shadowJar)
    }
}
