package dev.vishalverma.mnemo.arch;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import dev.vishalverma.mnemo.core.Engine;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * This is the second, complementary line of defence for "mnemo-core stays pure JDK" — the
 * primary one is the Gradle-level dependency check in mnemo.pure-java-conventions, which
 * inspects the resolved dependency GRAPH and catches a forbidden dependency the moment it's
 * declared, before any code references it. This test inspects compiled BYTECODE instead, which
 * catches the thing the graph-level check cannot: a class that actually imports, extends,
 * annotates with, or throws something from a forbidden package.
 *
 * {@code @AnalyzeClasses(packagesOf = Engine.class)} resolves against this module's own
 * {@code build/classes/java/main} directory on the test runtime classpath — not a jar — so
 * there's no risk of the "resolves to a jar URL, DoNotIncludeJars silently imports nothing"
 * trap that shows up when ArchUnit runs against a *different* module's compiled output via a
 * project() dependency.
 *
 * The anchor class must live in the ROOT package {@code dev.vishalverma.mnemo.core}, because
 * {@code packagesOf} sweeps that class's package and its subpackages. Anchoring on a class in,
 * say, {@code core.store} would silently narrow every rule below to that one subpackage and stop
 * guarding {@code command}, {@code type}, and {@code repl} — while still passing.
 */
@AnalyzeClasses(packagesOf = Engine.class, importOptions = ImportOption.DoNotIncludeTests.class)
class CoreIsPureJavaTest {

    @ArchTest
    static final ArchRule core_must_not_depend_on_spring =
        noClasses()
            .should().dependOnClassesThat().resideInAnyPackage("org.springframework..")
            .because("mnemo-core is pure JDK — Spring belongs only in mnemo-app");

    @ArchTest
    static final ArchRule core_must_not_depend_on_netty =
        noClasses()
            .should().dependOnClassesThat().resideInAnyPackage("io.netty..")
            .because("mnemo-core is pure JDK — Netty belongs only in mnemo-server");

    @ArchTest
    static final ArchRule core_must_depend_only_on_the_jdk_and_itself =
        noClasses()
            .should().dependOnClassesThat()
            .resideOutsideOfPackages("dev.vishalverma.mnemo.core..", "java..", "javax..", "jdk..")
            .because("mnemo-core must have zero third-party compile dependencies");

    /**
     * A safety net for the "empty class set makes a should-clause pass vacuously" failure mode:
     * ArchUnit's failOnEmptyShould defaults to true so the rules above already wouldn't pass
     * silently, but this makes the intent explicit and gives a clear failure message if the
     * import options above are ever tightened to the point of importing nothing.
     */
    @ArchTest
    static void classes_were_actually_imported(JavaClasses classes) {
        assertThat(classes.size())
            .as("ArchUnit imported zero classes from mnemo-core — check @AnalyzeClasses config")
            .isGreaterThan(0);
    }
}
