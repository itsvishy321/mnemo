// The only module that knows Spring exists: wiring, configuration binding, the admin REST
// surface, and Actuator. Everything it depends on (mnemo-server and, transitively, protocol /
// persistence / core) is framework-agnostic; this module is where those pieces get assembled
// into a running process.
//
// Deliberately NOT applying `io.spring.dependency-management` — Gradle's native `platform()`
// support does the same job and, per Spring's own docs, resolves faster. Applying the
// `org.springframework.boot` plugin alone does not pull in dependency management, so this stays
// scoped to mnemo-app's own configurations and cannot leak into sibling modules.

plugins {
    id("mnemo.java-conventions")
    alias(libs.plugins.springBoot)
}

dependencies {
    implementation(platform(libs.springBoot.bom))

    implementation(project(":mnemo-server"))

    implementation(libs.springBoot.starterWeb)
    implementation(libs.springBoot.starterActuator)

    testImplementation(platform(libs.springBoot.bom))
    testImplementation(libs.springBoot.starterTest)
    testImplementation(libs.awaitility)
}
