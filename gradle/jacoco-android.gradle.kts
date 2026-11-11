import org.gradle.testing.jacoco.plugins.JacocoPluginExtension
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.testing.jacoco.tasks.JacocoCoverageVerification
import org.gradle.testing.jacoco.tasks.JacocoReport
import java.math.BigDecimal

apply(plugin = "jacoco")

val jacocoVersion = extensions.getByType<VersionCatalogsExtension>()
    .named("libs").findVersion("jacoco").get().requiredVersion

configure<JacocoPluginExtension> {
    toolVersion = jacocoVersion
}

val generatedAndroidClasses = listOf(
    "**/R.class",
    "**/R$*.class",
    "**/BuildConfig.*",
    "**/Manifest*.*",
    "**/*\$\$serializer.class",
    "**/*\$\$serializer\$*.class",
    "**/ComposableSingletons\$*.class",
    "**/*_Impl.class",
    "**/*_Impl$*.class",
    "**/IPaperSource*.class",
    "**/IPaperTheme*.class",
    "**/IPaperReadableDocumentCallback*.class",
)

val productionSources = files(
    "src/main/java",
    "src/main/kotlin",
)
val productionClasses = files(
    fileTree(layout.buildDirectory.dir("intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes")) {
        exclude(generatedAndroidClasses)
    },
    fileTree(layout.buildDirectory.dir("intermediates/javac/debug/compileDebugJavaWithJavac/classes")) {
        exclude(generatedAndroidClasses)
    },
)
val unitCoverageData = fileTree(layout.buildDirectory) {
    include("outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec")
}
val connectedCoverageData = fileTree(layout.buildDirectory) {
    include("outputs/code_coverage/debugAndroidTest/connected/**/*.ec")
}
val combinedCoverageData = unitCoverageData + connectedCoverageData

tasks.register<JacocoReport>("jacocoDebugUnitTestReport") {
    group = "verification"
    description = "Generates line and branch coverage for debug unit tests."
    dependsOn("testDebugUnitTest")

    reports {
        html.required.set(true)
        xml.required.set(true)
        csv.required.set(true)
        html.outputLocation.set(layout.buildDirectory.dir("reports/jacoco/debug/html"))
        xml.outputLocation.set(layout.buildDirectory.file("reports/jacoco/debug/jacoco.xml"))
        csv.outputLocation.set(layout.buildDirectory.file("reports/jacoco/debug/jacoco.csv"))
    }

    sourceDirectories.setFrom(productionSources)
    classDirectories.setFrom(productionClasses)
    executionData.setFrom(unitCoverageData)
}

val combinedReport = tasks.register<JacocoReport>("jacocoDebugCombinedReport") {
    group = "verification"
    description = "Merges debug unit and connected Android test coverage."
    dependsOn("testDebugUnitTest", "connectedDebugAndroidTest")

    reports {
        html.required.set(true)
        xml.required.set(true)
        csv.required.set(true)
        html.outputLocation.set(layout.buildDirectory.dir("reports/jacoco/debug-combined/html"))
        xml.outputLocation.set(layout.buildDirectory.file("reports/jacoco/debug-combined/jacoco.xml"))
        csv.outputLocation.set(layout.buildDirectory.file("reports/jacoco/debug-combined/jacoco.csv"))
    }

    sourceDirectories.setFrom(productionSources)
    classDirectories.setFrom(productionClasses)
    executionData.setFrom(combinedCoverageData)
    val connectedData = connectedCoverageData
    val classes = productionClasses
    doFirst {
        check(connectedData.files.any { it.isFile && it.length() > 0 }) {
            "Connected Android tests did not produce nonempty .ec coverage data."
        }
        check(classes.asFileTree.files.any { it.extension == "class" }) {
            "No production classes found. Check the Android plugin coverage paths."
        }
    }
}

val combinedCoverageMinimum = extra.properties["combinedCoverageMinimum"] as? BigDecimal
if (combinedCoverageMinimum != null) {
    tasks.register<JacocoCoverageVerification>("verifyDebugCombinedCoverage") {
        group = "verification"
        description = "Fails when merged debug coverage falls below this module's ratcheted threshold."
        dependsOn(combinedReport)
        sourceDirectories.setFrom(productionSources)
        classDirectories.setFrom(productionClasses)
        executionData.setFrom(combinedCoverageData)

        violationRules {
            rule {
                limit {
                    counter = "LINE"
                    value = "COVEREDRATIO"
                    minimum = combinedCoverageMinimum
                }
                limit {
                    counter = "BRANCH"
                    value = "COVEREDRATIO"
                    minimum = combinedCoverageMinimum
                }
            }
        }
    }
}
