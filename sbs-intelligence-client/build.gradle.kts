import com.google.protobuf.gradle.id

// Optional Phase 4b module: the gRPC client for the SbsIntelligence service. Pulls in protobuf + grpc-java
// (+ netty), so settings.gradle.kts only includes it under -PwithIntelligence — the core build stays grpc-free.
// Generates Java stubs from the canonical proto in ../src/main/proto and implements the core
// select.IntelligenceClient seam (so RemoteStrategySelector, which is grpc-free, can use it).
//
// Build/verify on the host:  ./gradlew :sbs-intelligence-client:build -PwithIntelligence

plugins {
    `java-library`
    id("com.google.protobuf") version "0.9.4"
}

val grpcVersion = "1.62.2"
val protobufVersion = "3.25.3"

tasks.withType<JavaCompile>().configureEach {
    options.release = 17
}

dependencies {
    api(project(":")) // core: select.IntelligenceClient + intelligence.CircuitBreaker + profile/core types
    api("io.grpc:grpc-stub:$grpcVersion")
    api("io.grpc:grpc-protobuf:$grpcVersion")
    implementation("com.google.protobuf:protobuf-java:$protobufVersion")
    runtimeOnly("io.grpc:grpc-netty-shaded:$grpcVersion")
    // javax.annotation.Generated, referenced by the generated gRPC stubs, isn't in the JDK since 9.
    compileOnly("org.apache.tomcat:annotations-api:6.0.53")
}

sourceSets {
    main {
        proto {
            srcDir("../src/main/proto") // single source of truth, shared with tools/phase4/service
        }
    }
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:$protobufVersion"
    }
    plugins {
        id("grpc") {
            artifact = "io.grpc:protoc-gen-grpc-java:$grpcVersion"
        }
    }
    generateProtoTasks {
        all().forEach { task ->
            task.plugins {
                id("grpc")
            }
        }
    }
}
