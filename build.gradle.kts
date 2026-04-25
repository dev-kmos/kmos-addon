import org.gradle.api.tasks.Copy

plugins {
    alias(libs.plugins.fabric.loom)
}

base {
    archivesName = properties["archives_base_name"] as String
    version = libs.versions.mod.version.get()
    group = properties["maven_group"] as String
}

repositories {
    maven {
        name = "meteor-maven"
        url = uri("https://maven.meteordev.org/releases")
    }
    maven {
        name = "meteor-maven-snapshots"
        url = uri("https://maven.meteordev.org/snapshots")
    }
}

dependencies {
    // Fabric
    minecraft(libs.minecraft)
    mappings(variantOf(libs.yarn) { classifier("v2") })
    modImplementation(libs.fabric.loader)

    // Meteor
    modImplementation(libs.meteor.client)
}

tasks {
    val configuredReleaseBuildsDir = providers.gradleProperty("kmos.releaseBuildsDir").orNull
        ?: System.getenv("KMOS_RELEASE_BUILDS_DIR")
    val releaseBuildsDir = configuredReleaseBuildsDir?.let { file(it) } ?: rootDir.parentFile.resolve("release-builds")
    val releaseJarName = "${project.base.archivesName.get()}-${project.version}.jar"

    processResources {
        val propertyMap = mapOf(
            "version" to project.version,
            "mc_version" to libs.versions.minecraft.get()
        )

        inputs.properties(propertyMap)

        filteringCharset = "UTF-8"

        filesMatching("fabric.mod.json") {
            expand(propertyMap)
        }
    }

    jar {
        inputs.property("archivesName", project.base.archivesName.get())

        from("LICENSE") {
            rename { "${it}_${inputs.properties["archivesName"]}" }
        }
    }

    java {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    withType<JavaCompile> {
        options.encoding = "UTF-8"
        options.release = 21
        options.compilerArgs.add("-Xlint:deprecation")
        options.compilerArgs.add("-Xlint:unchecked")
    }

    register<Copy>("copyReleaseBuild") {
        group = "build"
        description = "Copies the remapped release jar into the shared release-builds directory."
        dependsOn("remapJar")
        from(layout.buildDirectory.dir("libs")) {
            include(releaseJarName)
        }
        into(releaseBuildsDir)
    }

    named("build") {
        finalizedBy("copyReleaseBuild")
    }
}
