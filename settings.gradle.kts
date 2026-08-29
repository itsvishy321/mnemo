rootProject.name = "mnemo"

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

include(
    "mnemo-core",
    "mnemo-protocol",
    "mnemo-persistence",
    "mnemo-server",
    "mnemo-app",
)
