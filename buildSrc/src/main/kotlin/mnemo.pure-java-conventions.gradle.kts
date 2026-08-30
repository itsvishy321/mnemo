// Applied to mnemo-core, mnemo-protocol, and mnemo-persistence: the three modules that must
// stay pure JDK. This is the primary enforcement mechanism (see README's design-decisions
// section) — it inspects the RESOLVED dependency graph, not the source code, so it catches a
// forbidden dependency the moment it's declared, even before any class references it. The
// ArchUnit test in mnemo-core is the secondary, complementary check: it catches things this
// graph-level gate cannot see, like accidental use of java.util.logging instead of SLF4J.

import org.gradle.api.DefaultTask
import org.gradle.api.artifacts.ArtifactCollection
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction

plugins {
    id("mnemo.java-conventions")
}

val libs = the<org.gradle.api.artifacts.VersionCatalogsExtension>().named("libs")

dependencies {
    add("testImplementation", libs.findLibrary("archunit-junit5").get())
}

val forbiddenGroupPrefixes = listOf("org.springframework", "jakarta.", "io.netty")

// A dedicated task type, rather than a `doLast {}` closure closing over script-local state, is
// what makes this configuration-cache compatible: a closure capturing the enclosing build
// script's local variables requires serializing a reference to the script object itself, which
// the configuration cache does not support. The resolved dependencies are threaded through as
// an `ArtifactCollection` — Gradle's purpose-built, configuration-cache-safe way to consume a
// resolved configuration's artifacts and component identifiers without eagerly resolving them
// at configuration time. A generic `Provider`/`ListProperty` built by `.map`-transforming
// `resolvedArtifacts` (tried first) breaks configuration-cache storage as soon as the
// configuration includes a project dependency (i.e. for every module but mnemo-core itself,
// which has none) — the underlying artifact set then carries a reference back to the producing
// project that a plain Provider chain cannot serialize.
abstract class CheckNoContrabandTask : DefaultTask() {

    @get:Internal
    abstract val artifacts: Property<ArtifactCollection>

    @get:Input
    abstract val projectPath: Property<String>

    @get:Input
    abstract val configurationName: Property<String>

    @get:Input
    abstract val forbiddenPrefixes: ListProperty<String>

    @TaskAction
    fun check() {
        val prefixes = forbiddenPrefixes.get()
        val offenders = artifacts.get().artifacts
            .mapNotNull { it.id.componentIdentifier as? ModuleComponentIdentifier }
            .filter { id -> prefixes.any { prefix -> id.group.startsWith(prefix) } }
            .map { "${it.group}:${it.module}:${it.version}" }
            .distinct()
            .sorted()

        if (offenders.isNotEmpty()) {
            throw GradleException(
                "${projectPath.get()} is declared pure-JDK (mnemo.pure-java-conventions) but " +
                    "'${configurationName.get()}' resolves to:\n  " + offenders.joinToString("\n  ") +
                    "\nRemove the dependency, or move this module off pure-java-conventions " +
                    "if it genuinely needs a framework."
            )
        }
    }
}

listOf("compileClasspath", "runtimeClasspath").forEach { resolvableConfigurationName ->
    val checkTask = tasks.register<CheckNoContrabandTask>(
        "checkNo${resolvableConfigurationName.replaceFirstChar(Char::titlecase)}Contraband"
    ) {
        group = "verification"
        description = "Fails if '$resolvableConfigurationName' resolves to a dependency this pure-JDK module must not have."
        this.projectPath.set(project.path)
        this.configurationName.set(resolvableConfigurationName)
        this.forbiddenPrefixes.set(forbiddenGroupPrefixes)
        this.artifacts.set(configurations.named(resolvableConfigurationName).map { it.incoming.artifacts })
        outputs.upToDateWhen { true }
    }

    tasks.named("check") { dependsOn(checkTask) }
}
