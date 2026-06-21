rootProject.name = "superbeefsort"

// Composite build: SuperBeefSort feeds the sibling CSRBT engine. Including CSRBT's build lets
// Gradle substitute the published coordinate `io.github.richeyworks:csrbt-core` with the local
// project automatically — no `mavenLocal()` publish step, always builds against the live source.
includeBuild("../CSRBT")

// Optional native-kernel module: compiled with a JDK 22 toolchain (Panama FFM finalised in
// JEP 454). Only included when the Gradle JVM is 22+ so the main module builds cleanly on
// any JDK 17+. Install JDK 22 and run Gradle with it to activate: ./gradlew :sbs-kernels-rust:build
if (JavaVersion.current() >= JavaVersion.VERSION_22) {
    include("sbs-kernels-rust")
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}
