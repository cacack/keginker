import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
    }
}
/**
 * The release key, from environment variables (CI) or the gitignored
 * local.properties. Without one, debug builds use the default debug key and
 * release builds are unsigned. See docs/operations/signing.md.
 */
val localProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
}

fun signingSetting(envName: String, localName: String): String? =
    providers.environmentVariable(envName).orNull?.takeIf { it.isNotEmpty() } ?: localProperties.getProperty(localName)

fun requiredSigningSetting(envName: String, localName: String): String = signingSetting(envName, localName)
    ?: error("A release keystore is set, but $envName (env) / $localName (local.properties) isn't")

val releaseKeystore = signingSetting("TAGPAPER_KEYSTORE", "tagpaper.keystore")

/** Version from the release workflow (see docs/operations/signing.md); dev builds use the defaults. */
val releaseVersionCode = providers.gradleProperty("tagpaper.versionCode").orNull?.let {
    it.toIntOrNull() ?: error("tagpaper.versionCode must be an integer, got '$it'")
}
val releaseVersionName = providers.gradleProperty("tagpaper.versionName").orNull

dependencies {
    implementation(project(":sharedUI"))

    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.compose.uiToolingPreview)
    debugImplementation(libs.compose.uiTooling)
}

android {
    namespace = "io.github.cacack.tagpaper"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "io.github.cacack.tagpaper"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = releaseVersionCode ?: 1
        versionName = releaseVersionName ?: "0.0.0-dev"
    }
    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = rootProject.file(releaseKeystore).also {
                    require(it.isFile) { "Release keystore not found: $it" }
                }
                storePassword = requiredSigningSetting("TAGPAPER_KEYSTORE_PASSWORD", "tagpaper.keystorePassword")
                keyAlias = requiredSigningSetting("TAGPAPER_KEY_ALIAS", "tagpaper.keyAlias")
                keyPassword = requiredSigningSetting("TAGPAPER_KEY_PASSWORD", "tagpaper.keyPassword")
            }
        }
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    buildTypes {
        // One key for every build, so CI and local builds install over each other.
        val releaseSigning = signingConfigs.findByName("release")
        debug {
            releaseSigning?.let { signingConfig = it }
        }
        release {
            releaseSigning?.let { signingConfig = it }
            isMinifyEnabled = false
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
    buildFeatures {
        compose = true
    }
}