import java.math.BigDecimal

plugins {
    alias(libs.plugins.android.library)
    id("maven-publish")
}

extra["combinedCoverageMinimum"] = BigDecimal("1.00")
apply(from = rootProject.file("gradle/jacoco-android.gradle.kts"))

group = "dev.paperreader"
version = "0.2.0"

android {
    buildToolsVersion = libs.versions.buildTools.get()
    namespace = "dev.paperreader.extensions.api"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        consumerProguardFiles("consumer-rules.pro")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            enableUnitTestCoverage = true
            enableAndroidTestCoverage = true
        }
    }

    testCoverage {
        jacocoVersion = libs.versions.jacoco.get()
    }

    buildFeatures {
        aidl = true
    }

    aidlPackagedList.addAll(
        listOf(
            "dev/paperreader/extensions/api/IPaperReadableDocumentCallback.aidl",
            "dev/paperreader/extensions/api/IPaperSourceCallback.aidl",
            "dev/paperreader/extensions/api/IPaperSourceService.aidl",
            "dev/paperreader/extensions/api/IPaperThemeCallback.aidl",
            "dev/paperreader/extensions/api/IPaperThemeService.aidl",
        ),
    )

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                artifactId = "extension-api"
            }
        }
    }
}

dependencies {
    testImplementation(libs.junit)

    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
