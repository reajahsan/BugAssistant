plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.reaj.bugassistant.llama"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        minSdk = 28
        consumerProguardFiles("consumer-rules.pro")

        ndk {
            abiFilters += "arm64-v8a" // real phones only; llama.cpp on x86 emulators is not representative
        }
        externalNativeBuild {
            cmake {
                // Always build llama.cpp optimized: a Debug native build is 10x+ slower.
                arguments += listOf(
                    "-DCMAKE_BUILD_TYPE=Release",
                    "-DLLAMA_DIR=${rootProject.file("third_party/llama.cpp").invariantSeparatorsPath}",
                )
                cppFlags += "-std=c++17"
            }
        }
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.kotlinx.coroutines.android)
}
