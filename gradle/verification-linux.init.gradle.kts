// Adds the Linux build of AAPT2, which CI's ubuntu jobs download, to
// gradle/verification-metadata.xml when it is refreshed on a Mac. AGP picks
// the AAPT2 build matching its aapt2-proto dependency. See docs/operations/ci.md.
rootProject {
    tasks.register("resolveLinuxAapt2") {
        val version = buildscript.configurations.getByName("classpath")
            .resolvedConfiguration.resolvedArtifacts
            .map { it.moduleVersion.id }
            .single { it.group == "com.android.tools.build" && it.name == "aapt2-proto" }
            .version
        val aapt2 = configurations.detachedConfiguration(
            dependencies.create("com.android.tools.build:aapt2:$version:linux"),
        ).apply { isTransitive = false }
        doLast { aapt2.files.forEach { println("Resolved ${it.name}") } }
    }
}
