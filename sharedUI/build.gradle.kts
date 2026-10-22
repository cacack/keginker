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
            implementation(libs.roborazzi)
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

// The README screenshots ScreenshotTest records (-Proborazzi.test.record=true)
// or checks (-Proborazzi.test.verify=true) from the sample label; without
// either it only runs the flow.
val screenshots = rootProject.layout.projectDirectory.dir("docs/images/screenshots")
val sampleLabel = rootProject.layout.projectDirectory.file("fixtures/screenshots/sample-label.png")
tasks.withType<Test>().configureEach {
    inputs.dir(screenshots).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(sampleLabel).withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("keginker.screenshots", screenshots.asFile.path)
    systemProperty("keginker.sampleLabel", sampleLabel.asFile.path)
    for (mode in listOf("roborazzi.test.record", "roborazzi.test.verify")) {
        providers.gradleProperty(mode).orNull?.let { systemProperty(mode, it) }
    }
}

dependencies {
    androidRuntimeClasspath(libs.compose.uiTooling)
}
