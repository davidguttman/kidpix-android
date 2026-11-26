pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositories { google(); mavenCentral() } }
rootProject.name = "KidPixAndroid"
include(":app")
include(":glfx")
include(":kidpix")
