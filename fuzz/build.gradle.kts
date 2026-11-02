import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Jazzer fuzz targets for the code that parses what a tag sends back; see
// docs/operations/ci.md#fuzzing. A plain JVM build of sharedLogic's common code,
// so it builds without the Android SDK, as ClusterFuzzLite's image has none.
plugins {
    alias(libs.plugins.kotlinJvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
    }
    sourceSets.main {
        kotlin.srcDir("../sharedLogic/src/commonMain/kotlin")
    }
}

val jazzer = configurations.create("jazzer") { isCanBeConsumed = false }

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    // ClusterFuzzLite's image brings its own Jazzer; locally, the jazzer configuration does.
    compileOnly(libs.jazzer.api)
    jazzer(libs.jazzer)
}

/** The fuzz targets, each a `<Name>Fuzzer`, by the name of their `fuzz<Name>` task. */
val targets = fileTree("src/main/kotlin") { include("**/*Fuzzer.kt") }
    .map { it.nameWithoutExtension.removeSuffix("Fuzzer") }
    .sorted()

// ./gradlew :fuzz:fuzz runs each target for fuzz.seconds (default 30);
// ./gradlew :fuzz:fuzzNdef runs one. Crashes land in fuzz/build/fuzz.
val fuzzTasks = targets.map { name ->
    tasks.register<JavaExec>("fuzz$name") {
        description = "Fuzzes ${name}Fuzzer with Jazzer."
        group = "verification"
        val seconds = providers.gradleProperty("fuzz.seconds").orElse("30")
        val corpus = layout.buildDirectory.dir("fuzz/corpus/$name").get().asFile
        classpath(jazzer, sourceSets.main.map { it.runtimeClasspath })
        mainClass = "com.code_intelligence.jazzer.Jazzer"
        workingDir = corpus.parentFile.parentFile
        argumentProviders.add(CommandLineArgumentProvider {
            listOf(
                "--target_class=io.github.cacack.keginker.fuzz.${name}Fuzzer",
                "-max_total_time=${seconds.get()}",
                corpus.path,
            )
        })
        doFirst { corpus.mkdirs() }
    }
}

tasks.register("fuzz") {
    description = "Fuzzes every target with Jazzer, one after another."
    group = "verification"
    dependsOn(fuzzTasks)
}

// The targets and the jars they run on, for .clusterfuzzlite/build.sh.
tasks.register<Sync>("fuzzLibs") {
    description = "Collects the fuzz targets' jar and its runtime dependencies."
    from(tasks.jar, configurations.runtimeClasspath)
    into(layout.buildDirectory.dir("fuzz-libs"))
}
