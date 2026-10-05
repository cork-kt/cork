plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.maven.publish)
    alias(libs.plugins.gradleup.nmcp)
    alias(libs.plugins.signing)
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
        singleVariant("release") {
            withSourcesJar()
            withJavadocJar()
        }
    }
}

publishing {
    publications {
        register<MavenPublication>("release") {
            afterEvaluate {
                from(components["release"])
            }

            groupId = "io.github.cork-kt"
            artifactId = "cork"
            version = "1.0.0-alpha1"
        }
    }
}

nmcp {
    publishAllPublicationsToCentralPortal {
        username = System.getenv("MAVEN_USERNAME")
        password = System.getenv("MAVEN_PASSWORD")
        publishingType = "AUTOMATIC"
    }
}

signing {
    val signingKey = System.getenv("SIGNING_SECRET_KEY")
    val signingPassword = System.getenv("SIGNING_PASSWORD")
    useInMemoryPgpKeys(signingKey, signingPassword)
    sign(publishing.publications["release"])
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