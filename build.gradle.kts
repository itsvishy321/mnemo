// Root build script deliberately stays empty of `allprojects {}` / `subprojects {}` blocks.
// Shared build logic lives in buildSrc convention plugins (see buildSrc/src/main/kotlin) and is
// applied explicitly per module. This is what keeps Spring's dependency management from ever
// leaking into mnemo-core, mnemo-protocol, or mnemo-persistence: those modules simply never
// apply anything that knows Spring exists.
plugins {
    alias(libs.plugins.springBoot) apply false
}
