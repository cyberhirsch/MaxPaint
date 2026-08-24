plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/**
 * Versions come from git rather than being hand-edited, so every build is
 * identifiable and versionCode only ever goes up.
 *
 *   versionCode  commit count       monotonic, which Android requires
 *   versionName  0.<minor>.<count>-<sha>[-dirty]
 *
 * CI must check out with fetch-depth 0, or a shallow clone reports one commit
 * and every build claims versionCode 1.
 */
fun git(vararg args: String): String = try {
    val process = ProcessBuilder(listOf("git") + args)
        .directory(rootDir)
        .redirectErrorStream(true)
        .start()
    val text = process.inputStream.bufferedReader().use { it.readText() }.trim()
    process.waitFor()
    if (process.exitValue() == 0) text else ""
} catch (e: Exception) {
    ""
}

val gitCount = git("rev-list", "--count", "HEAD").toIntOrNull() ?: 1
val gitSha = git("rev-parse", "--short=7", "HEAD").ifBlank { "nogit" }
val isDirty = git("status", "--porcelain").isNotBlank()

val minorVersion = 3
val appVersionName = buildString {
    append("0.$minorVersion.$gitCount-$gitSha")
    if (isDirty) append("-dirty")
}

android {
    namespace = "com.maxpaint.spike"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.maxpaint.spike"
        minSdk = 26
        targetSdk = 34
        versionCode = gitCount
        versionName = appVersionName
    }

    // APK filenames carry the version, so downloaded builds do not all collide
    // in the same folder as app-debug.apk
    base.archivesName.set("maxpaint-$appVersionName")

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
}
