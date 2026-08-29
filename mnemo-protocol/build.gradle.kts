// RESP2 codec. Pure JDK — encodes/decodes against mnemo-core's Reply/Command types only.

plugins {
    id("mnemo.pure-java-conventions")
}

dependencies {
    api(project(":mnemo-core"))
}
