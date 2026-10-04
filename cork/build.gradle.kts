plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "cork"
    compileSdk = 37
    ndkVersion = "30.0.16248370"

    defaultConfig {
        minSdk = 23
        consumerProguardFiles("consumer-rules.pro")

        ndk {
            abiFilters += listOf(
                "arm64-v8a",
                "armeabi-v7a",
                "x86_64"
            )
        }
    }

    sourceSets {
        named("main") {
            jniLibs.srcDirs("src/main/jniLibs")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    publishing {
        singleVariant("release")
    }
}

kotlin {
    explicitApi()
}

tasks.named("preBuild") {
    dependsOn(rootProject.tasks.named("buildRustRelease"))
}
dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.startup.runtime)
}