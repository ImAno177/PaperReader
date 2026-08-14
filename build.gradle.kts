plugins {
    id("com.android.application") version "9.3.1" apply false
    id("com.android.library") version "9.3.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.2.10" apply false
    id("com.google.devtools.ksp") version "2.3.6" apply false
    id("androidx.room") version "2.8.4" apply false
}

tasks.register("coverageReport") {
    group = "verification"
    description = "Generates debug unit-test coverage reports for every production module."
    dependsOn(
        ":extension-api:jacocoDebugUnitTestReport",
        ":logic:jacocoDebugUnitTestReport",
        ":app:jacocoDebugUnitTestReport",
    )
}

tasks.register("coverageConnectedReport") {
    group = "verification"
    description = "Generates merged unit and connected Android coverage for every production module."
    dependsOn(
        ":extension-api:jacocoDebugCombinedReport",
        ":logic:jacocoDebugCombinedReport",
        ":app:jacocoDebugCombinedReport",
    )
}
