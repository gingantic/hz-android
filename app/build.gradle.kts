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

// Shared signing config for the `release` variant so local builds and CI-published
// OTA APKs carry the same certificate (otherwise INSTALL_FAILED_UPDATE_INCOMPATIBLE).
// CI passes values via env vars; local dev reads them from gitignored local.properties.
fun readLocalProp(key: String): String? {
    val f = rootProject.file("local.properties")
    if (!f.exists()) return null
    return f.readLines()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains("=") }
        .map { it.split("=", limit = 2) }
        .firstOrNull { it[0].trim() == key }
        ?.get(1)?.trim()
}
fun signingValue(propKey: String, envKey: String): String? =
    System.getenv(envKey) ?: readLocalProp(propKey)

fun hasCcache(): Boolean {
    val path = System.getenv("PATH") ?: return false
    val isWindows = System.getProperty("os.name").lowercase(Locale.US).contains("win")
    val separator = if (isWindows) ";" else ":"
    val exts = if (isWindows) listOf(".exe", ".bat", ".cmd", "") else listOf("")
    for (dir in path.split(separator)) {
        val cleanDir = dir.trim()
        if (cleanDir.isEmpty()) continue
        try {
            val folder = File(cleanDir)
            if (folder.isDirectory) {
                for (ext in exts) {
                    val ccacheFile = File(folder, "ccache$ext")
                    if (ccacheFile.isFile && ccacheFile.canExecute()) {
                        return true
                    }
                }
            }
        } catch (_: Exception) {}
    }
    return false
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    ndkVersion = "27.0.12077973"   // PIN. Keep in sync with NDK_VER in build.yml; changing invalidates .cxx + native caches.

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

        externalNativeBuild {
            cmake {
                if (hasCcache()) {
                    arguments += listOf(
                        "-DCMAKE_C_COMPILER_LAUNCHER=ccache",
                        "-DCMAKE_CXX_COMPILER_LAUNCHER=ccache"
                    )
                }

            }
        }

    }

    signingConfigs {
        create("release") {
            val keystorePath = signingValue("SIGNING_STORE_PATH", "KEYSTORE_PATH")
            storeFile = if (keystorePath != null) {
                file(keystorePath)
            } else {
                rootProject.file(".signing/release.keystore.jks")
            }
            storePassword = signingValue("SIGNING_STORE_PASSWORD", "KEYSTORE_PASS") ?: ""
            keyAlias = signingValue("SIGNING_KEY_ALIAS", "KEY_ALIAS") ?: "hzplayer"
            keyPassword = signingValue("SIGNING_KEY_PASSWORD", "KEY_PASS") ?: ""
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            val releaseSigning = signingConfigs.getByName("release")
            if (releaseSigning.storeFile?.exists() == true) {
                signingConfig = releaseSigning
            } else {
                signingConfig = signingConfigs.getByName("debug")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            applicationIdSuffix = ".debug"
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
    implementation(libs.coil.network.okhttp)

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
