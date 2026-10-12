plugins {
    alias(libs.plugins.android.application)
}

val dieselDevKeystorePath =
    providers.environmentVariable("DIESEL_DEV_KEYSTORE_PATH").orNull

android {
    namespace = "org.aaustralian.dieselbridge.reference"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.bloom11.dieselbridge.reference"
        minSdk = 28
        targetSdk = 36
        versionCode = 3
        versionName = "0.1.0-dev.3"
    }

    signingConfigs {
        if (dieselDevKeystorePath != null) {
            create("dieselDev") {
                storeFile = file(dieselDevKeystorePath)
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false

            if (dieselDevKeystorePath != null) {
                signingConfig =
                    signingConfigs.getByName(
                        "dieselDev",
                    )
            }
        }
    }

    buildFeatures {
        aidl = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget =
            org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
