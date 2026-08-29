// Netty transport, connection handling, replication. Not pure-JDK (needs Netty), so this uses
// the base java-conventions only.
//
// The Netty BOM is applied as `enforcedPlatform`, not `platform`. Reason: mnemo-app pulls in
// spring-boot-dependencies, which also manages io.netty:* versions. Without `enforced`, Spring's
// constraint would win inside mnemo-app's resolved graph and this module could end up running
// against a Netty version it never chose. `enforcedPlatform` makes mnemo-server's own pinned
// version win regardless of what a downstream consumer's BOM says.

plugins {
    id("mnemo.java-conventions")
}

dependencies {
    api(project(":mnemo-protocol"))
    api(project(":mnemo-persistence"))

    api(enforcedPlatform(libs.netty.bom))
    implementation(libs.bundles.netty)
}
