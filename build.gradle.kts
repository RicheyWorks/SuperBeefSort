import org.gradle.api.attributes.java.TargetJvmVersion

plugins {
    `java-library`
    application
    `maven-publish`   // Phase 9: local repo today; Central rides csrbt-core's release
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
    // 3 forks (fresh JVMs) so the ParallelRadixBenchmark seq-vs-par crossover averages out per-fork JIT/layout
    // luck, on top of its in-fork seq/par adjacency. Filtered runs (-Pbench=...) stay quick; the full unfiltered
    // suite is ~3x longer at this setting -- drop back to 1 if you need the whole ~40 min suite fast.
    fork = 3
    warmupIterations = 3
    iterations = 5
    resultFormat = "JSON"
    resultsFile = layout.buildDirectory.file("reports/jmh/results.json")
    // Optional filter so you can run a subset instead of the whole suite (~40 min). Examples:
    //   ./gradlew jmh -Pbench=SortStrategyBenchmark   (just the sort strategies)
    //   ./gradlew jmh -Pbench=RadixNativeBenchmark    (native vs Java radix across sizes)
    //   ./gradlew jmh -Pbench=wikiSort                (just the WikiSort method, across shapes)
    //   ./gradlew jmh -Pbench=SelectorInferenceLatencyBenchmark  (cost-model vs bandit vs learned select() latency)
    if (project.hasProperty("bench")) {
        includes.set(listOf(project.property("bench").toString()))
    }
    // Panama FFM requires native-access permission. On JDK 22+ with the kernel module this
    // enables radix.lsd.rust benchmarks; safe no-op on older JVMs (no native strategy registered).
    if (JavaVersion.current() >= JavaVersion.VERSION_22) {
        jvmArgs.add("--enable-native-access=ALL-UNNAMED")
    }
    // Allow running despite a stale lock file (safe on a single-developer machine).
    jvmArgs.add("-Djmh.ignoreLock=true")
    // Heap headroom for the large-n benchmarks (e.g. ParallelRadixBenchmark at 2M/5M) so GC churn from the
    // per-op buffer allocation doesn't inflate measurement variance.
    jvmArgs.add("-Xmx4g")
}

// The jmh plugin doesn't hook the jmh source set into `build`/`check`, so a compile
// break in a benchmark would only surface at the next manual jmh run. Feed it in.
// (Mirrors csrbt-benchmarks.)
tasks.named("check") { dependsOn(tasks.named("compileJmhJava")) }

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

// Phase 4 decision gate (ADR action item 2): measure selector optimality vs brute-force oracle
// across sizes / shapes / key modes. Writes build/reports/phase4-gate.csv. Run: ./gradlew phase4Gate
tasks.register<JavaExec>("phase4Gate") {
    group = "verification"
    description = "Phase 4 decision gate: selector optimality vs oracle. Writes build/reports/phase4-gate.csv."
    mainClass.set("io.github.richeyworks.superbeefsort.demo.Phase4DecisionGate")
    classpath = sourceSets["main"].runtimeClasspath
    jvmArgs("-Xmx512m")
    if (JavaVersion.current() >= JavaVersion.VERSION_22) {
        jvmArgs("--enable-native-access=ALL-UNNAMED")
    }
}

// Phase 4 training-corpus dumper (ADR action item 3): emit {DataProfile features, oracle label,
// per-strategy costs} CSVs over a workload grid, using the real profiler features. Feeds the offline
// Python trainer in tools/phase4/. Writes build/reports/phase4-corpus-{train,gate}.csv.
// Run: ./gradlew phase4Corpus   (optionally --args="<trainTrials>")
tasks.register<JavaExec>("phase4Corpus") {
    group = "verification"
    description = "Dump the Phase 4 training corpus (features+label+costs) for the offline trainer."
    mainClass.set("io.github.richeyworks.superbeefsort.demo.Phase4CorpusDump")
    classpath = sourceSets["main"].runtimeClasspath
    jvmArgs("-Xmx512m")
    if (JavaVersion.current() >= JavaVersion.VERSION_22) {
        jvmArgs("--enable-native-access=ALL-UNNAMED")
    }
}

// Phase 9 (outer-ring ADR): make the ring locally installable — ./gradlew publishToMavenLocal.
publishing {
    publications {
        create<MavenPublication>("maven") {
            artifactId = "superbeefsort"
            from(components["java"])
            pom {
                name = "SuperBeefSort"
                description = "A pluggable Java sorting engine: profile, select, sort, feed — the intake tract of the CSRBT ecosystem."
                url = "https://github.com/RicheyWorks/SuperBeefSort"
                licenses {
                    license {
                        name = "MIT License"
                        url = "https://opensource.org/licenses/MIT"
                    }
                }
                developers {
                    developer {
                        id = "RicheyWorks"
                        name = "Richmond"
                    }
                }
                scm {
                    url = "https://github.com/RicheyWorks/SuperBeefSort"
                    connection = "scm:git:https://github.com/RicheyWorks/SuperBeefSort.git"
                }
            }
        }
    }
}