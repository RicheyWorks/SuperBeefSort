plugins {
    `java-library`
}

group = "io.github.richeyworks"
version = "0.1.0"

java {
    withSourcesJar()
}

// Mirror CSRBT: 17-target bytecode from whatever JDK runs Gradle (Gradle 9 needs 17+). No
// toolchain pin. Phase 0 is deliberately Java 17 to match the engine it feeds; the Phase 2
// Rust kernels (Panama FFM) live in a separate module that can target a newer JDK.
tasks.withType<JavaCompile>().configureEach {
    options.release = 17
}

dependencies {
    // Resolved to the local CSRBT project via the composite build in settings.gradle.kts.
    api("io.github.richeyworks:csrbt-core:0.1.0")

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.jqwik) // property tests with shrinking, same stack as CSRBT
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform() // picks up both the Jupiter and jqwik engines
}
