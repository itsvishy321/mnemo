// Snapshot + AOF. Pure JDK — reads and writes mnemo-core's value model directly.

plugins {
    id("mnemo.pure-java-conventions")
}

dependencies {
    api(project(":mnemo-core"))
}
