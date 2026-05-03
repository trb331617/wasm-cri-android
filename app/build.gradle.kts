import com.google.protobuf.gradle.id

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.protobuf")
}

android {
    namespace = "io.kubeedge.wasmcri"
    compileSdk = 34

    defaultConfig {
        applicationId = "io.kubeedge.wasmcri"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
        debug {
            isMinifyEnabled = false
        }
    }

    packaging {
        // Important: Android extracts ELF binaries from jniLibs and chmod +x them
        // automatically only when extractNativeLibs / useLegacyPackaging is true.
        jniLibs {
            useLegacyPackaging = true
            // Avoid duplicate netty native libs across grpc-netty-shaded artifacts
            pickFirsts += listOf("**/libnetty_*.so")
        }
        resources {
            excludes += listOf(
                "META-INF/INDEX.LIST",
                "META-INF/io.netty.versions.properties",
                "META-INF/native-image/**",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    sourceSets {
        named("main") {
            // Keep AGP defaults (java/, java/'s auto-added generated dirs).
            // Add our Kotlin tree on top; do NOT reset java.srcDirs.
            java.srcDirs("src/main/kotlin")
            proto.srcDir("src/main/proto")
        }
    }

    // Make absolutely sure the kotlin compile task sees the generated
    // Java sources from protoc.  AGP+kotlin-android usually does this
    // automatically, but proto generation happens during configuration
    // of *another* task and the wiring sometimes misses on the first run.
    afterEvaluate {
        tasks.matching { it.name.startsWith("compile") && it.name.endsWith("Kotlin") }
            .configureEach {
                dependsOn(tasks.matching { it.name.startsWith("generate") && it.name.endsWith("Proto") })
            }
    }
}

dependencies {
    // ---- gRPC ----
    implementation("io.grpc:grpc-netty-shaded:1.64.0")
    implementation("io.grpc:grpc-protobuf:1.64.0")
    implementation("io.grpc:grpc-stub:1.64.0")
    implementation("io.grpc:grpc-services:1.64.0")
    implementation("io.grpc:grpc-kotlin-stub:1.4.1")

    // ---- Protobuf ----
    implementation("com.google.protobuf:protobuf-kotlin:3.25.3")
    implementation("com.google.protobuf:protobuf-java:3.25.3")

    // ---- Netty native epoll for AF_UNIX gRPC ----
    // grpc-netty-shaded already shades netty; we add the *unshaded* native classifier
    // because shaded netty can dynamically load it via the standard classpath.
    // For Android arm64-v8a hosts, this jar contains libnetty_transport_native_epoll_aarch_64.so.
    implementation("io.netty:netty-transport-native-epoll:4.1.110.Final:linux-aarch_64")

    // ---- Misc ----
    implementation("javax.annotation:javax.annotation-api:1.3.2")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")

    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.core:core-ktx:1.13.1")
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:3.25.3"
    }
    plugins {
        id("grpc") {
            artifact = "io.grpc:protoc-gen-grpc-java:1.64.0"
        }
        id("grpckt") {
            artifact = "io.grpc:protoc-gen-grpc-kotlin:1.4.1:jdk8@jar"
        }
    }
    generateProtoTasks {
        all().forEach { task ->
            task.plugins {
                id("grpc") { }
                id("grpckt") { }
            }
            task.builtins {
                // Java builtin is enabled by default, but some plugin/AGP
                // version combos lose it once we add a kotlin builtin.
                // Re-declaring here is a defensive no-op when it's already on.
                id("java") { }
                id("kotlin") { }
            }
        }
    }
}
