plugins {
    `maven-publish`
    signing
    alias(libs.plugins.fabric.loom) apply false
    alias(libs.plugins.fabric.loom.remap) apply false

    // https://github.com/ReplayMod/preprocessor
    // https://github.com/Fallen-Breath/preprocessor
    alias(libs.plugins.preprocess)

    alias(libs.plugins.yamlang) apply false
    alias(libs.plugins.spotless)
}

repositories {
    maven {
        name = "AliyunMavenCentral"
        url = uri("https://maven.aliyun.com/repository/central")
    }
    mavenCentral()
}

val rootProjectRef: Project = project

preprocess {
    strictExtraMappings = false

    val mc1201 = createNode("1.20.1", 1_20_01, "")
    val mc2602 = createNode("26.2", 26_02_00, "")
    val mc26011 = createNode("26.1.1", 26_01_01, "")

    mc1201.link(mc2602)
    mc2602.link(mc26011)

    // See https://github.com/Fallen-Breath/fabric-mod-template/blob/1d72d77a1c5ce0bf060c2501270298a12adab679/build.gradle#L55-L63
    for (node in getNodes()) {
        val nodeProject =
            requireNotNull(rootProjectRef.findProject(node.project)) {
                "Project ${node.project} not found"
            }
        nodeProject.extensions.extraProperties["mcVersion"] = node.mcVersion
    }
}

tasks.register("buildAndGather") {
    group = "build"
    description = "Builds all subprojects and gathers their output jars into the root build/libs directory."
    dependsOn(project.subprojects.map { it.tasks.named("build") })
    doFirst {
        println("Gathering builds")
        val buildLibs: (Project) -> File = { p ->
            p.layout.buildDirectory
                .dir("libs")
                .get()
                .asFile
        }
        project.delete(project.fileTree(buildLibs(rootProject)) { include("*") })
        project.subprojects.forEach { subproject ->
            project.copy {
                from(buildLibs(subproject)) {
                    include("*.jar")
                    exclude("*-dev.jar", "*-sources.jar", "*-shadow.jar", "*-javadoc.jar")
                }
                into(buildLibs(rootProject))
                duplicatesStrategy = DuplicatesStrategy.INCLUDE
            }
        }
    }
}

spotless {
    val licenseHeaderFile = rootProject.file("copyright.txt")
    kotlinGradle {
        target("*.gradle.kts")
        ktlint(libs.versions.ktlint.get())
    }
    java {
        target(
            "src/main/java/**/*.java",
            "versions/*/src/main/java/**/*.java",
            "src/client/java/**/*.java",
            "versions/*/src/client/java/**/*.java",
        )
        toggleOffOn()
        removeUnusedImports()
        forbidWildcardImports()
        forbidModuleImports()
        importOrderFile(rootProject.file("eclipse-importorder.txt"))
        cleanthat()
        val eclipseRelease = libs.versions.eclipse.get()
        val eclipseVersion = eclipseRelease.removePrefix("R-").substringBeforeLast("-")
        eclipse(eclipseVersion)
            .withP2Mirrors(
                mapOf(
                    "https://download.eclipse.org/eclipse/updates/$eclipseVersion/" to
                        "https://download.eclipse.org/eclipse/updates/$eclipseVersion/$eclipseRelease/",
                ),
            ).configFile(rootProject.file("eclipse-formatter.xml"))
        licenseHeaderFile(licenseHeaderFile)
    }
    format("styling") {
        target(
            "gradle/libs.versions.toml",
            "*.md",
            "*.json",
            "*.yml",
            "*.xml",
            ".github/**/*.yml",
        )

        prettier(
            mapOf(
                // "nodeExecutable" to "/Users/cjkim/.nvm/versions/node/v22.17.1/bin/node",
                "npmExecutable" to "/Users/cjkim/.nvm/versions/node/v22.17.1/bin/npm",
                "prettier" to libs.versions.prettier.get(),
                "prettier-plugin-toml" to
                    libs.versions.prettierPlugin.toml
                        .get(),
                "@prettier/plugin-xml" to
                    libs.versions.prettierPlugin.xml
                        .get(),
            ),
        ).config(
            mapOf(
                "plugins" to
                    listOf(
                        "prettier-plugin-toml",
                        "@prettier/plugin-xml",
                    ),
            ),
        )
    }
    format("text") {
        target(
            "LICENSE",
            "gradle.properties",
            "gradle/wrapper/gradle-wrapper.properties",
            "versions/*/gradle.properties",
            "copyright.txt",
            "mappings/*.txt",
            "eclipse-importorder.txt",
        )
        trimTrailingWhitespace()
        endWithNewline()
    }
}
