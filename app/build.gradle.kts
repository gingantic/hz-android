import java.text.SimpleDateFormat
import java.util.Locale
import java.util.Date

fun getGitCommitCount(): Int {
    return try {
        providers.exec {
            commandLine("git", "rev-list", "--count", "HEAD")
        }.standardOutput.asText.get().trim().toIntOrNull() ?: 1
    } catch (e: java.lang.Exception) {
        1
    }
}

fun getGitCommitHash(): String {
    return try {
        providers.exec {
            commandLine("git", "rev-parse", "--short", "HEAD")
        }.standardOutput.asText.get().trim()
    } catch (e: java.lang.Exception) {
        "unknown"
    }
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.rhnxdev.hzplayer"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.rhnxdev.hzplayer"
        minSdk = 28
        targetSdk = 36

        val commitCount = getGitCommitCount()
        val commitHash = getGitCommitHash()
        versionCode = commitCount
        versionName = "0.9.1-build.$commitCount+$commitHash"

        val r2BaseUrl = (project.findProperty("R2_UPDATE_BASE_URL") as? String)
            ?: System.getenv("R2_UPDATE_BASE_URL")
            ?: "http://localhost"
        buildConfigField("String", "R2_UPDATE_BASE_URL", "\"$r2BaseUrl\"")

        buildConfigField("String", "BUILD_DATE", "\"${SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())}\"")
        buildConfigField("String", "BUILD_TIME", "\"${SimpleDateFormat("HH:mm", Locale.US).format(Date())}\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }


    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }
}

// Room schema export destination (referenced by @Database(exportSchema = true)).
// Generated RoomSchema files are committed so versioned Migrations can be authored.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}
dependencies {
    // Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.adaptive.navigation.suite)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    // Navigation
    implementation(libs.androidx.navigation.compose)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    // Room
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // Media3
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.exoplayer.dash)
    implementation(libs.media3.exoplayer.rtsp)
    implementation(libs.media3.session)
    implementation(libs.media3.ui)

    // Material Icons Extended (for media-player-specific icons)
    implementation("androidx.compose.material:material-icons-extended")

    // Coil
    implementation(libs.coil.core)
    implementation(libs.coil.compose)

    // DataStore
    implementation(libs.androidx.datastore.preferences)

    // Network browsing
    implementation(libs.commons.net)
    implementation(libs.sshj)
    implementation(libs.jcifs.ng)

    // mDNS service discovery
    implementation(libs.jmDNS)

    // WebDAV (uses OkHttp)
    implementation(libs.okhttp)

    // Security
    implementation(libs.security.crypto)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}

tasks.register("printVersionName") {
    doLast {
        println(android.defaultConfig.versionName)
    }
}

tasks.register("printVersionCode") {
    doLast {
        println(android.defaultConfig.versionCode)
    }
}
