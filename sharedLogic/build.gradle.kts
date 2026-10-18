import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
}

kotlin {
    listOf(
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "SharedLogic"
            isStatic = true
        }
    }
    
    android {
       namespace = "io.github.cacack.keginker.sharedLogic"
       compileSdk = libs.versions.android.compileSdk.get().toInt()
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
    }
    
    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

// Mutation testing, on demand only: ./gradlew :sharedLogic:pitest
// See docs/reviews/testing/2026-10-18-mutation-testing.md.
val pitest = configurations.create("pitest") { isCanBeConsumed = false }
dependencies { pitest(libs.pitest.commandLine) }

tasks.register<JavaExec>("pitest") {
    description = "Mutation-tests the image pipeline and Waveshare protocol with PIT."
    group = "verification"
    // The host-test task is created after this script runs; look it up lazily.
    val hostTest = tasks.named<Test>("testAndroidHostTest")
    dependsOn(hostTest)
    val testClasspath = hostTest.map { it.classpath }
    val reportDir = layout.buildDirectory.dir("reports/pitest")
    val sourceDir = file("src/commonMain/kotlin").path
    val mainClasses = kotlin.targets.named("android").map { it.compilations.getByName("main").output.classesDirs }
    classpath(pitest, testClasspath)
    mainClass = "org.pitest.mutationtest.commandline.MutationCoverageReport"
    outputs.dir(reportDir)
    argumentProviders.add(CommandLineArgumentProvider {
        listOf(
            "--reportDir", reportDir.get().asFile.path,
            "--targetClasses", "io.github.cacack.keginker.image.*,io.github.cacack.keginker.waveshare.*",
            "--targetTests", "io.github.cacack.keginker.*",
            // Tests share those packages; mutate only the production classes.
            "--mutableCodePaths", mainClasses.get().files.joinToString(","),
            "--classPath", (mainClasses.get().files + testClasspath.get().files).joinToString(","),
            "--sourceDirs", sourceDir,
            "--outputFormats", "HTML,XML",
            "--timestampedReports", "false",
        )
    })
}
