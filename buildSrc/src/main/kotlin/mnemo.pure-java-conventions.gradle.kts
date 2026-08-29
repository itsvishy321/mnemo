// Applied to mnemo-core, mnemo-protocol, and mnemo-persistence: the three modules that must
// stay pure JDK. This is the primary enforcement mechanism (see README's design-decisions
// section) — it inspects the RESOLVED dependency graph, not the source code, so it catches a
// forbidden dependency the moment it's declared, even before any class references it. The
// ArchUnit test in mnemo-core is the secondary, complementary check: it catches things this
// graph-level gate cannot see, like accidental use of java.util.logging instead of SLF4J.

import org.gradle.api.artifacts.component.ModuleComponentIdentifier

plugins {
    id("mnemo.java-conventions")
}

val libs = the<org.gradle.api.artifacts.VersionCatalogsExtension>().named("libs")

dependencies {
    add("testImplementation", libs.findLibrary("archunit-junit5").get())
}

val forbiddenGroupPrefixes = listOf("org.springframework", "jakarta.", "io.netty")

listOf("compileClasspath", "runtimeClasspath").forEach { configurationName ->
    val artifacts = configurations.named(configurationName)
        .flatMap { it.incoming.artifacts.resolvedArtifacts }

    val checkTask = tasks.register("checkNo${configurationName.replaceFirstChar(Char::titlecase)}Contraband") {
        group = "verification"
        description = "Fails if '$configurationName' resolves to a dependency this pure-JDK module must not have."
        val projectPath = project.path

        inputs.property("forbidden", forbiddenGroupPrefixes)
        outputs.upToDateWhen { true }

        doLast {
            val offenders = artifacts.get()
                .mapNotNull { it.id.componentIdentifier as? ModuleComponentIdentifier }
                .filter { id -> forbiddenGroupPrefixes.any { prefix -> id.group.startsWith(prefix) } }
                .map { "${it.group}:${it.module}:${it.version}" }
                .distinct()
                .sorted()

            if (offenders.isNotEmpty()) {
                throw GradleException(
                    "$projectPath is declared pure-JDK (mnemo.pure-java-conventions) but " +
                        "'$configurationName' resolves to:\n  " + offenders.joinToString("\n  ") +
                        "\nRemove the dependency, or move this module off pure-java-conventions " +
                        "if it genuinely needs a framework."
                )
            }
        }
    }

    tasks.named("check") { dependsOn(checkTask) }
}
