plugins {
    id("gradlebuild.distribution.implementation-kotlin")
}

description = "Kotlin DSL Provider Plugins"

dependencies {
    api(project(":base-services"))
    api(project(":core"))
    api(project(":core-api"))
    api(project(":kotlin-dsl"))
    api(project(":logging"))
    api(project(":service-provider"))
    api(project(":stdlib-java-extensions"))

    api(libs.kotlinStdlib)
    api(libs.inject)

    implementation(project(":concurrent"))
    implementation(project(":file-collections"))
    implementation(project(":functional"))
    implementation(project(":hashing"))
    implementation(project(":plugin-development"))
    implementation(project(":plugins-java-base"))
    implementation(project(":platform-jvm"))
    implementation(project(":resources"))
    implementation(project(":snapshots"))
    implementation(project(":tooling-api"))
    implementation(project(":toolchains-jvm"))
    implementation(project(":toolchains-jvm-shared"))

    implementation(libs.futureKotlin("scripting-compiler-impl-embeddable")) {
        isTransitive = false
    }
    implementation(libs.kotlinCompilerEmbeddable)
    implementation(libs.slf4jApi)

    testImplementation(testFixtures(project(":kotlin-dsl")))
    testImplementation(libs.mockitoKotlin2)
}

packageCycles {
    excludePatterns.add("org/gradle/kotlin/dsl/provider/plugins/precompiled/tasks/**")
}

dependencyAnalysis {
    issues {
        onUsedTransitiveDependencies() {
            exclude(":logging-api")
        }
    }
}
