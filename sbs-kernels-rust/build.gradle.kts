plugins {
    `java-library`
}

group = "io.github.richeyworks"
version = "0.1.0"

// JDK 22 toolchain: FFM API is finalized in JEP 454 (JDK 22). The root module stays on 17.
java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(22))
    }
}

dependencies {
    // Interfaces (SortStrategy, SortBuffer, StrategyProvider, …) live in the root module.
    compileOnly(project(":"))
    testImplementation(project(":"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// ── Cargo build ──────────────────────────────────────────────────────────────────────────────

val rustDir = layout.projectDirectory.dir("rust")
val cargoReleaseDir = rustDir.dir("target/release")

val cargoBuild by tasks.registering(Exec::class) {
    group = "build"
    description = "Compile the Rust radix kernel cdylib via Cargo."
    workingDir = rustDir.asFile
    commandLine("cargo", "build", "--release")
    inputs.dir(rustDir.dir("src"))
    inputs.file(rustDir.file("Cargo.toml"))
    // Outputs: the whole release dir so incremental tracking works
    outputs.dir(cargoReleaseDir)
}

// Detect host platform and derive the native library filename
val osName = System.getProperty("os.name", "").lowercase()
val (platformDir, nativeLibName) = when {
    osName.contains("windows") -> "windows-x64" to "sbsradix.dll"
    osName.contains("linux")   -> "linux-x64"   to "libsbsradix.so"
    osName.contains("mac")     -> "macos-x64"   to "libsbsradix.dylib"
    else                       -> "unknown"      to ""
}

val generatedNativeDir = layout.buildDirectory.dir("generated/resources/native/$platformDir")

val copyNativeLib by tasks.registering(Copy::class) {
    dependsOn(cargoBuild)
    from(cargoReleaseDir) {
        // Only grab the cdylib for the current platform
        include(nativeLibName)
    }
    into(generatedNativeDir)
    // No-op when nativeLibName is empty (unrecognised OS)
    onlyIf { nativeLibName.isNotEmpty() }
}

sourceSets {
    main {
        resources {
            srcDir(layout.buildDirectory.dir("generated/resources"))
        }
    }
}

tasks.processResources {
    dependsOn(copyNativeLib)
}

// ── Tests ─────────────────────────────────────────────────────────────────────────────────────

tasks.test {
    useJUnitPlatform()
    // Panama FFM requires native-access permission on JDK 22+
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

// Off-heap long radix prototype timing (ADR docs/adr-phase2-offheap-sortbuffer.md). The off-heap path
// (bulk copy + in-place native sort, no per-element marshaling) vs an inline Java radix baseline.
// Run: ./gradlew :sbs-kernels-rust:offHeapBench   (Gradle JVM must be 22+; cargo build runs via processResources)
tasks.register<JavaExec>("offHeapBench") {
    group = "verification"
    description = "Time off-heap native long radix vs an inline Java radix baseline."
    dependsOn(tasks.processResources)
    mainClass.set("io.github.richeyworks.sbskernels.rust.OffHeapRadixBenchmark")
    classpath = sourceSets["main"].runtimeClasspath
    jvmArgs("--enable-native-access=ALL-UNNAMED", "-Xmx2g")
}
