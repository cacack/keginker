// Raise the build tools' own dependencies to patched versions (see libs.versions.toml).
// Constraints only lift a version; a tool that later needs a newer one still gets it.
buildscript {
    dependencies {
        constraints {
            libs.bundles.toolFloors.get().forEach { classpath(it) }
        }
    }
}

plugins {
    // this is necessary to avoid the plugins to be loaded multiple times
    // in each subproject's classloader
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidMultiplatformLibrary) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
}

subprojects {
    configurations.configureEach {
        if (isCanBeDeclared) {
            libs.bundles.toolFloors.get().forEach { project.dependencies.constraints.add(name, it) }
        }
    }
}

/** `git rev-parse --short=7 HEAD`, or null without a repository or without git installed. */
fun gitHead(): String? = runCatching {
    providers.exec {
        commandLine("git", "rev-parse", "--short=7", "HEAD")
        isIgnoreExitValue = true
    }.standardOutput.asText.orNull?.trim()
}.getOrNull()?.takeIf { it.isNotEmpty() }

/** Whether tracked files differ from HEAD, so a build from them isn't exactly that commit. */
fun gitDirty(): Boolean = runCatching {
    providers.exec {
        commandLine("git", "status", "--porcelain", "--untracked-files=no")
        isIgnoreExitValue = true
    }.standardOutput.asText.orNull?.isNotBlank() == true
}.getOrDefault(false)

/**
 * The commit a build came from, for diagnostics reports. In order: KEGINKER_COMMIT
 * (CI sets it to a pull request's head commit, since GITHUB_SHA is then a temporary
 * merge commit), GITHUB_SHA, git, else "unknown". A local build from uncommitted
 * changes adds "-dirty", so its reports don't claim to be that commit.
 */
val buildCommit: String by extra(
    listOf("KEGINKER_COMMIT", "GITHUB_SHA")
        .firstNotNullOfOrNull { name -> providers.environmentVariable(name).orNull?.takeIf { it.isNotEmpty() } }
        ?.take(7)
        ?: gitHead()?.let { if (gitDirty()) "$it-dirty" else it }
        ?: "unknown",
)

/** Version name from the release workflow (see docs/operations/signing.md); dev builds use 0.0.0-dev. */
val buildVersionName: String by extra(providers.gradleProperty("keginker.versionName").orNull ?: "0.0.0-dev")
