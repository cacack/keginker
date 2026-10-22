import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    
    android {
       namespace = "io.github.cacack.keginker.sharedUI"
       compileSdk {
           version = release(libs.versions.android.compileSdk.get().toInt()) {
               minorApiLevel = libs.versions.android.compileSdkMinor.get().toInt()
           }
       }
       minSdk = libs.versions.android.minSdk.get().toInt()
    
       compilerOptions {
           jvmTarget = JvmTarget.JVM_11
       }
       androidResources {
           enable = true
       }
       withHostTest {
           isIncludeAndroidResources = true
       }
       withDeviceTestBuilder {
           sourceSetTreeName = "test"
       }.configure {
           instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
       }
    }
    
    sourceSets {
        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.compose.uiTooling)
        }
        commonMain.dependencies {
            api(project(":sharedLogic"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
        // Compose UI tests of the Android screens, run on the JVM by Robolectric.
        getByName("androidHostTest").dependencies {
            implementation(libs.junit)
            implementation(libs.robolectric)
            implementation(libs.androidx.testCore)
            implementation(libs.compose.uiTest.junit4)
            implementation(libs.compose.uiTest.manifest)
            // Newer than ui-test brings in; older Espresso fails on Android 16.
            implementation(libs.androidx.espresso.core)
        }
    }
}

// The fixture photos and golden views both platforms' loader tests share.
val photoFixtures = rootProject.layout.projectDirectory.dir("fixtures/photos")
tasks.withType<Test>().configureEach {
    inputs.dir(photoFixtures).withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("keginker.photoFixtures", photoFixtures.asFile.path)
    // Robolectric's Android 16 runtime reads JDK internals.
    jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED")
}

dependencies {
    androidRuntimeClasspath(libs.compose.uiTooling)
}
