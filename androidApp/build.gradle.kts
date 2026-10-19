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

val releaseKeystore = signingSetting("KEGINKER_KEYSTORE", "keginker.keystore")

/** Version code from the release workflow (see docs/operations/signing.md); dev builds use 1. */
val releaseVersionCode = providers.gradleProperty("keginker.versionCode").orNull?.let {
    it.toIntOrNull() ?: error("keginker.versionCode must be an integer, got '$it'")
}

val buildCommit: String by rootProject.extra
val buildVersionName: String by rootProject.extra

dependencies {
    implementation(project(":sharedUI"))

    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.compose.uiToolingPreview)
    debugImplementation(libs.compose.uiTooling)

    testImplementation(libs.kotlin.testJunit)
}

android {
    namespace = "io.github.cacack.keginker"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "io.github.cacack.keginker"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = releaseVersionCode ?: 1
        versionName = buildVersionName
        buildConfigField("String", "COMMIT", "\"$buildCommit\"")
    }
    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = rootProject.file(releaseKeystore).also {
                    require(it.isFile) { "Release keystore not found: $it" }
                }
                storePassword = requiredSigningSetting("KEGINKER_KEYSTORE_PASSWORD", "keginker.keystorePassword")
                keyAlias = requiredSigningSetting("KEGINKER_KEY_ALIAS", "keginker.keyAlias")
                keyPassword = requiredSigningSetting("KEGINKER_KEY_PASSWORD", "keginker.keyPassword")
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
        buildConfig = true
    }
}