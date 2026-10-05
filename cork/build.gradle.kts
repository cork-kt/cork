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

            pom {
                name = "Cork"
                description = "Cork is a High-performance Android-first compression and archive library with a Kotlin API backed by optimized native Rust codes."
                url = "https://github.com/cork-kt/cork"

                licenses {
                    license {
                        name = "The Apache License, Version 2.0"
                        url = "http://www.apache.org/licenses/"
                    }
                }

                developers {
                    developer {
                        id = "Ishan09811"
                        name = "Ishan"
                        email = "ishanbasaki7@gmail.com"
                    }
                }

                scm {
                    connection = "scm:git:https://github.com/cork-kt/cork.git"
                    developerConnection = "scm:git:ssh://git@github.com/cork-kt/cork.git"
                    url = "https://github.com/cork-kt/cork"
                }
            }
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