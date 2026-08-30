// Shared by every module: language level, test platform, and the two test libraries every
// module needs regardless of whether it's pure JDK or Spring-aware.

plugins {
    `java-library`
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    maxHeapSize = "1g"
}

val libs = the<org.gradle.api.artifacts.VersionCatalogsExtension>().named("libs")

dependencies {
    add("testImplementation", platform(libs.findLibrary("junit-bom").get()))
    add("testImplementation", libs.findLibrary("junit-jupiter").get())
    add("testImplementation", libs.findLibrary("assertj-core").get())
    add("testRuntimeOnly", libs.findLibrary("junit-platform-launcher").get())
}
