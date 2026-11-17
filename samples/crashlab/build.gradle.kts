// Test-only app that crashes and leaks on purpose, to check BugAssistant's bug report analysis.
// Never ship it.
plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.reaj.crashlab"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.reaj.crashlab"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
