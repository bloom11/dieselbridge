import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
}

val dieselDevKeystorePath =
    providers.environmentVariable("DIESEL_DEV_KEYSTORE_PATH").orNull

val buildGitSha =
    providers.environmentVariable("DIESEL_BUILD_GIT_SHA").orNull
        ?: providers.environmentVariable("GITHUB_SHA").orNull
        ?: "unknown"

val buildCiRunId =
    providers.environmentVariable("DIESEL_BUILD_CI_RUN_ID").orNull
        ?: providers.environmentVariable("GITHUB_RUN_ID").orNull
        ?: "unknown"

val buildTimestampUtc =
    providers.environmentVariable("DIESEL_BUILD_TIMESTAMP_UTC").orNull
        ?: DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'")
            .withZone(ZoneOffset.UTC)
            .format(Instant.now())

android {
    namespace = "org.aaustralian.dieselbridge.phone"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.bloom11.dieselbridge.phone"
        minSdk = 28
        targetSdk = 36
        versionCode = 2
        versionName = "0.1.0-dev.2"

        buildConfigField(
            "String",
            "BUILD_GIT_SHA",
            "\"$buildGitSha\"",
        )
        buildConfigField(
            "String",
            "BUILD_TIMESTAMP_UTC",
            "\"$buildTimestampUtc\"",
        )
        buildConfigField(
            "String",
            "BUILD_CI_RUN_ID",
            "\"$buildCiRunId\"",
        )
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
                signingConfig = signingConfigs.getByName("dieselDev")
            }
        }
    }

    buildFeatures {
        buildConfig = true
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
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    testImplementation(libs.junit)
    testImplementation(libs.json)
}
