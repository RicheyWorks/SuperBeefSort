rootProject.name = "superbeefsort"

// Composite build: SuperBeefSort feeds the sibling CSRBT engine. Including CSRBT's build lets
// Gradle substitute the published coordinate `io.github.richeyworks:csrbt-core` with the local
// project automatically — no `mavenLocal()` publish step, always builds against the live source.
includeBuild("../CSRBT")

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}
