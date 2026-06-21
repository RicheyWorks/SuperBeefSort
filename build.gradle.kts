import org.gradle.api.attributes.java.TargetJvmVersion

plugins {
    `java-library`
    application
    id("me.champeau.jmh") version "0.7.3" // JMH micro-benchmarks: ./gradlew jmh
}

group = "io.github.richeyworks"
version = "0.1.0"

java {
    withSourcesJar()
}

application {
    mainClass.set("io.github.richeyworks.superbeefsort.demo.Demo")
    // csrbt-core logs through log4j-api with no bundled backend; point at log4j's built-in
    // no-config SimpleLogger so the demo doesn't print "could not find a logging provider".
    applicationDefaultJvmArgs = listOf(
        "-Dlog4j2.loggerContextFactory=org.apache.logging.log4j.simple.SimpleLoggerContextFactory"
    )
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

    // sbs-kernels-rust is optional: only add it when the JVM running Gradle is 22+, because
    // that module is compiled for JDK 22 and won't load on older runtimes. When present, the
    // StrategyRegistry discovers radix.lsd.rust via ServiceLoader + ServiceConfigurationError guard.
    if (JavaVersion.current() >= JavaVersion.VERSION_22) {
        testRuntimeOnly(project(":sbs-kernels-rust"))
        "jmhRuntimeOnly"(project(":sbs-kernels-rust"))
        // Elevate testRuntimeClasspath to JVM 22 so Gradle's attribute matching accepts the
        // kernel module (which requires JVM 22). All existing JVM-17 deps are upward compatible.
        // Same attribute fix for both test and JMH classpaths.
        listOf("testRuntimeClasspath", "jmhRuntimeClasspath").forEach { cp ->
            configurations.named(cp) {
                attributes {
                    attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 22)
                }
            }
        }
    }
}

tasks.test {
    useJUnitPlatform() // picks up both the Jupiter and jqwik engines
    // Panama FFM native access for RustRadixDifferentialTest on JDK 22+
    if (JavaVersion.current() >= JavaVersion.VERSION_22) {
        jvmArgs("--enable-native-access=ALL-UNNAMED")
    }
}

// JMH rig (mirrors CSRBT's config). Benchmarks live in src/jmh/java. Run: ./gradlew jmh
jmh {
    jmhVersion = "1.37"
    fork = 1
    warmupIterations = 3
    iterations = 5
    resultFormat = "JSON"
    resultsFile = layout.buildDirectory.file("reports/jmh/results.json")
    // Optional filter so you can run a subset instead of the whole suite (~40 min). Examples:
    //   ./gradlew jmh -Pbench=SortStrategyBenchmark   (just the sort strategies)
    //   ./gradlew jmh -Pbench=RadixNativeBenchmark    (native vs Java radix across sizes)
    //   ./gradlew jmh -Pbench=wikiSort                (just the WikiSort method, across shapes)
    if (project.hasProperty("bench")) {
        includes.set(listOf(project.property("bench").toString()))
    }
    // Panama FFM requires native-access permission. On JDK 22+ with the kernel module this
    // enables radix.lsd.rust benchmarks; safe no-op on older JVMs (no native strategy registered).
    if (JavaVersion.current() >= JavaVersion.VERSION_22) {
        jvmArgs.add("--enable-native-access=ALL-UNNAMED")
    }
}

// Analytical report (not a wall-clock benchmark): prints the metered move/comparison growth curve for
// merge / merge.inplace / merge.wiki, normalised to n*log2(n). Shows merge.wiki's O(n log n) moves
// versus merge.inplace's O(n log^2 n). Run: ./gradlew moveCurve
tasks.register<JavaExec>("moveCurve") {
    group = "verification"
    description = "Print the move/comparison growth curve for merge / merge.inplace / merge.wiki."
    mainClass.set("io.github.richeyworks.superbeefsort.demo.MoveCurveReport")
    classpath = sourceSets["main"].runtimeClasspath
    jvmArgs("-Xmx2g")
}
