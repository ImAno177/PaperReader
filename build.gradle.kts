plugins {
    id("com.android.application") version "9.3.1" apply false
    id("com.android.library") version "9.3.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.2.10" apply false
    id("com.google.devtools.ksp") version "2.3.6" apply false
    id("androidx.room") version "2.8.4" apply false
}

tasks.register("hostUnitTest") {
    group = "verification"
    description = "Runs the deterministic JVM unit-test gate for every production module."
    dependsOn(
        ":extension-api:testDebugUnitTest",
        ":logic:testDebugUnitTest",
        ":app:testDebugUnitTest",
    )
}

tasks.register("hostLint") {
    group = "verification"
    description = "Runs the debug lint gate for every production module."
    dependsOn(
        ":extension-api:lintDebug",
        ":logic:lintDebug",
        ":app:lintDebug",
    )
}

tasks.register("hostConnectedTest") {
    group = "verification"
    description = "Runs connected Android tests for every production module."
    dependsOn(
        ":extension-api:connectedDebugAndroidTest",
        ":logic:connectedDebugAndroidTest",
        ":app:connectedDebugAndroidTest",
    )
}

tasks.register("coverageReport") {
    group = "verification"
    description = "Runs deterministic JVM tests and generates debug coverage for every production module."
    dependsOn(
        ":extension-api:jacocoDebugUnitTestReport",
        ":logic:jacocoDebugUnitTestReport",
        ":app:jacocoDebugUnitTestReport",
    )
}

tasks.register("coverageConnectedReport") {
    group = "verification"
    description = "Generates merged Android coverage and enforces configured module thresholds."
    dependsOn(
        ":extension-api:verifyDebugCombinedCoverage",
        ":logic:jacocoDebugCombinedReport",
        ":app:jacocoDebugCombinedReport",
    )
}
