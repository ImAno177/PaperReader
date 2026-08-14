import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

private fun String.asBuildConfigString(): String =
    "\"${replace("\\", "\\\\").replace("\"", "\\\"")}\""

val devSourcePackage = providers.gradleProperty("paperReaderDevSourcePackage").orElse("").get()
val devSourceService = providers.gradleProperty("paperReaderDevSourceService").orElse("").get()
val devSourceProviderId = providers.gradleProperty("paperReaderDevSourceProviderId").orElse("").get()
val devSourceDisplayName = providers.gradleProperty("paperReaderDevSourceDisplayName").orElse("").get()
val devSourceSigner = providers.gradleProperty("paperReaderDevSourceSignerSha256").orElse("").get()
val devSourceVersion = providers.gradleProperty("paperReaderDevSourceVersionCode").orElse("0").get()
val devThemePackage = providers.gradleProperty("paperReaderDevThemePackage").orElse("").get()
val devThemeService = providers.gradleProperty("paperReaderDevThemeService").orElse("").get()
val devThemeDisplayName = providers.gradleProperty("paperReaderDevThemeDisplayName").orElse("").get()
val devThemeId = providers.gradleProperty("paperReaderDevThemeId").orElse("").get()
val devThemeSigner = providers.gradleProperty("paperReaderDevThemeSignerSha256").orElse("").get()
val devThemeVersion = providers.gradleProperty("paperReaderDevThemeVersionCode").orElse("0").get()
val appKeystorePath = providers.gradleProperty("appKeystorePath").orNull
val appVersionCode = providers.gradleProperty("appVersionCode").orElse("1").get().toInt()
val appVersionName = providers.gradleProperty("appVersionName").orElse("0.1.0").get()
val connectedTestApplicationIdSuffix = providers.gradleProperty("paperReaderConnectedTestApplicationIdSuffix").orNull

require(devSourceSigner.isBlank() || devSourceSigner.matches(Regex("[0-9a-fA-F]{64}")))
require(devSourceVersion.toLongOrNull() != null)
require(devThemeSigner.isBlank() || devThemeSigner.matches(Regex("[0-9a-fA-F]{64}")))
require(devThemeVersion.toLongOrNull() != null)
require(connectedTestApplicationIdSuffix == null || connectedTestApplicationIdSuffix.matches(Regex("\\.[a-z][a-z0-9_]*")))

android {
    namespace = "dev.paperreader.app"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "dev.paperreader.app"
        minSdk = 28
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "DEV_SOURCE_PACKAGE", devSourcePackage.asBuildConfigString())
        buildConfigField("String", "DEV_SOURCE_SERVICE", devSourceService.asBuildConfigString())
        buildConfigField("String", "DEV_SOURCE_PROVIDER_ID", devSourceProviderId.asBuildConfigString())
        buildConfigField("String", "DEV_SOURCE_DISPLAY_NAME", devSourceDisplayName.asBuildConfigString())
        buildConfigField("String", "DEV_SOURCE_SIGNER_SHA256", devSourceSigner.asBuildConfigString())
        buildConfigField("long", "DEV_SOURCE_VERSION_CODE", "${devSourceVersion}L")
        buildConfigField("String", "DEV_THEME_PACKAGE", devThemePackage.asBuildConfigString())
        buildConfigField("String", "DEV_THEME_SERVICE", devThemeService.asBuildConfigString())
        buildConfigField("String", "DEV_THEME_DISPLAY_NAME", devThemeDisplayName.asBuildConfigString())
        buildConfigField("String", "DEV_THEME_ID", devThemeId.asBuildConfigString())
        buildConfigField("String", "DEV_THEME_SIGNER_SHA256", devThemeSigner.asBuildConfigString())
        buildConfigField("long", "DEV_THEME_VERSION_CODE", "${devThemeVersion}L")
        buildConfigField(
            "String",
            "OFFICIAL_SOURCE_STORE_URL",
            "https://raw.githubusercontent.com/ImAno177/PaperReader-sources/main/registry/index.signed.json".asBuildConfigString(),
        )
        buildConfigField("String", "OFFICIAL_SOURCE_STORE_ID", "paperreader.official.sources".asBuildConfigString())
        buildConfigField(
            "String",
            "OFFICIAL_SOURCE_STORE_PUBLIC_KEY",
            "7pUD6Tvcjk1Kf/eS+JdKnXPBktUaYisYdfcbsvB30VA=".asBuildConfigString(),
        )
    }

    signingConfigs {
        if (appKeystorePath != null) {
            create("paperReaderRelease") {
                storeFile = file(appKeystorePath)
                storePassword = providers.gradleProperty("appKeystorePassword").get()
                keyAlias = providers.gradleProperty("appKeyAlias").get()
                keyPassword = providers.gradleProperty("appKeyPassword").get()
            }
        }
    }

    buildTypes {
        debug {
            connectedTestApplicationIdSuffix?.let { applicationIdSuffix = it }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (appKeystorePath != null) signingConfig = signingConfigs.getByName("paperReaderRelease")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    bundle {
        language {
            enableSplit = false
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.06.00")

    implementation(project(":logic"))
    implementation(project(":extension-api"))
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.navigation:navigation-compose:2.9.8")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.datastore:datastore-preferences:1.2.1")
    implementation("androidx.work:work-runtime-ktx:2.11.2")
    implementation("androidx.pdf:pdf-viewer-fragment:1.0.0-alpha19")
    implementation("com.squareup.okhttp3:okhttp:5.1.0")

    testImplementation("junit:junit:4.13.2")

    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4-accessibility")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test:rules:1.7.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
}
