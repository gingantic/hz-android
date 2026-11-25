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

// versionCode is derived from the commit count, which a history rewrite lowers.
// This offset keeps it above every already-published build (259 before the CI
// history squash). Raise it when squashing, never lower it.
val VERSION_CODE_OFFSET = 3

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
        versionCode = commitCount + VERSION_CODE_OFFSET
        versionName = "0.9.6-build.$commitCount+$commitHash"

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
            // Emulator ABI for debug only; release stays arm64-v8a so the OTA APK stays small.
            ndk { abiFilters += "x86_64" }
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

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

// Room schema export destination (referenced by @Database(exportSchema = true)).
// Generated RoomSchema files are committed so versioned Migrations can be authored.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

// Robolectric fetches its android-all runtime jars from Maven Central at test
// time, outside Gradle, so they never land in the Gradle cache and CI re-fetched
// ~250 MB every run. Resolve them as ordinary dependencies and run Robolectric
// offline against the resolved files instead (wiring at the bottom of the file).
// Two configurations: both jars share group:artifact, so one configuration would
// resolve them to a single version.
val robolectricRuntimeNewest by configurations.creating {
    isCanBeConsumed = false
}
val robolectricRuntimeOldest by configurations.creating {
    isCanBeConsumed = false
}

dependencies {
    // Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    // WebView compat (algorithmic darkening, Safe Browsing compat, feature checks)
    implementation(libs.androidx.webkit)

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

    // Country flag ImageVectors (subtitle language rows)
    implementation(libs.flagkit)

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

    // WebDAV (uses OkHttp)
    implementation(libs.okhttp)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)

    // Robolectric runtime jars. Coordinates come from Robolectric's
    // DefaultSdkProvider: 35 is the newest SDK it ships (what targetSdk 36
    // resolves to), 21 the oldest it still supports. Bump these when the
    // Robolectric version or compileSdk changes, or tests fail with
    // "Unable to locate dependency".
    robolectricRuntimeNewest("org.robolectric:android-all-instrumented:15-robolectric-12650502-i7")
    robolectricRuntimeOldest("org.robolectric:android-all-instrumented:5.0.2_r3-robolectric-r0-i7")

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}

// Robolectric's LocalDependencyResolver looks for a flat directory of
// <artifactId>-<version>.jar, which is exactly what these artifacts are called.
val robolectricRuntimeDir = layout.buildDirectory.dir("robolectric-runtime")
val syncRobolectricRuntime by tasks.registering(Sync::class) {
    from(robolectricRuntimeNewest, robolectricRuntimeOldest)
    into(robolectricRuntimeDir)
}

tasks.withType<Test>().configureEach {
    dependsOn(syncRobolectricRuntime)
    systemProperty("robolectric.offline", "true")
    systemProperty("robolectric.dependency.dir", robolectricRuntimeDir.get().asFile.absolutePath)
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
