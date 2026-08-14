plugins {
    id("com.android.library")
    id("maven-publish")
}

group = "dev.paperreader"
version = "0.1.0"

android {
    namespace = "dev.paperreader.extensions.api"
    compileSdk = 36

    defaultConfig {
        minSdk = 28
        consumerProguardFiles("consumer-rules.pro")
    }

    buildFeatures {
        aidl = true
    }

    aidlPackagedList.addAll(
        listOf(
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
    testImplementation("junit:junit:4.13.2")
}
