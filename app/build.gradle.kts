import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Debug and release are signed with the key named by signing.file in local.properties (git-ignored), so no
// secret lives in the project. Without it, release falls back to the debug key.
val signing = rootProject.file("local.properties").takeIf { it.exists() }
    ?.let { f -> Properties().apply { f.inputStream().use(::load) } }
    ?.getProperty("signing.file")?.let { path -> file(path).takeIf { it.exists() } }
    ?.let { f -> Properties().apply { f.inputStream().use(::load) } }

// CI has no local.properties: it writes the key from secrets and names it in these variables.
val ciSigning = System.getenv("HONE_KEYSTORE")?.let { path ->
    Properties().apply {
        setProperty("keystore.path", path)
        setProperty("keystore.password", System.getenv("HONE_KEYSTORE_PASSWORD") ?: "")
        setProperty("key.alias", System.getenv("HONE_KEY_ALIAS") ?: "")
        setProperty("key.password", System.getenv("HONE_KEY_PASSWORD") ?: "")
    }
}

val sdkDir: String = Properties().apply { rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use(::load) }
    .getProperty("sdk.dir") ?: System.getenv("ANDROID_HOME") ?: ""

// The Rust engine is a plain executable. Packaged as lib*.so it is extracted next to the app's native
// libraries with execute permission, where the shell user started by Shizuku can run it.
val engineDir = rootProject.file("engine")
val engineOut = engineDir.resolve("target/aarch64-linux-android/release/hone")
val buildEngine by tasks.registering(Exec::class) {
    workingDir = engineDir
    environment("ANDROID_NDK_HOME", File(sdkDir, "ndk").listFiles()?.maxOrNull()?.path ?: "")
    commandLine("cargo", "ndk", "-t", "arm64-v8a", "-P", "26", "build", "--release")
    inputs.dir(engineDir.resolve("src"))
    inputs.file(engineDir.resolve("Cargo.toml"))
    outputs.file(engineOut)
}
val copyEngine by tasks.registering(Copy::class) {
    dependsOn(buildEngine)
    from(engineOut)
    into(layout.projectDirectory.dir("src/main/jniLibs/arm64-v8a"))
    rename { "libhone.so" }
}
tasks.named("preBuild") { dependsOn(copyEngine) }

android {
    namespace = "io.max.hone"
    compileSdk = 37
    buildFeatures { aidl = true }
    defaultConfig {
        applicationId = "io.max.hone"
        minSdk = 26
        targetSdk = 37
        // CI passes -PversionName / -PversionCode on every release build; local builds keep these defaults.
        versionCode = (findProperty("versionCode") as String?)?.toInt() ?: 1
        versionName = (findProperty("versionName") as String?) ?: "0.1.0"
        ndk { abiFilters += "arm64-v8a" }
    }
    signingConfigs {
        // Debug and release share one key, so either installs over the other.
        (ciSigning ?: signing)?.let { sg ->
            for (name in listOf("debug", "release")) {
                maybeCreate(name).apply {
                    storeFile = file(sg.getProperty("keystore.path"))
                    storePassword = sg.getProperty("keystore.password")
                    keyAlias = sg.getProperty("key.alias")
                    keyPassword = sg.getProperty("key.password")
                }
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging {
        jniLibs.useLegacyPackaging = true // extract libhone.so to disk so it can be executed
        resources.excludes += listOf("/META-INF/{AL2.0,LGPL2.1,LICENSE*,NOTICE*}", "/META-INF/*.version", "**/*.kotlin_builtins")
    }
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.material3:material3:1.5.0-rc01")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.12.0")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}
