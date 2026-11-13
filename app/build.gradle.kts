import com.android.build.api.variant.BuildConfigField

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

apply(from = rootProject.file("gradle/jacoco-android.gradle.kts"))

private fun String.asBuildConfigString(): String =
    "\"${replace("\\", "\\\\").replace("\"", "\\\"")}\""

val devSourcePackage = providers.gradleProperty("paperReaderDevSourcePackage").orElse("").get()
val devSourceService = providers.gradleProperty("paperReaderDevSourceService").orElse("").get()
val devSourceProviderId = providers.gradleProperty("paperReaderDevSourceProviderId").orElse("").get()
val devSourceDisplayName = providers.gradleProperty("paperReaderDevSourceDisplayName").orElse("").get()
val devSourceSigner = providers.gradleProperty("paperReaderDevSourceSignerSha256").orElse("").get()
val devSourceVersion = providers.gradleProperty("paperReaderDevSourceVersionCode").orElse("0").get()
val devSourceMinimumInterval = providers.gradleProperty("paperReaderDevSourceMinimumIntervalMillis").orElse("1000").get()
val devSourceCapabilities = providers.gradleProperty("paperReaderDevSourceCapabilities").orElse("search,details,pdf_link").get()
val devSourceRoles = providers.gradleProperty("paperReaderDevSourceRoles").orElse("content_source").get()
val devSourceIdentifierTypes = providers.gradleProperty("paperReaderDevSourceIdentifierTypes").orElse("doi,arxiv,pmid,pmcid").get()
val devSourceSupportedSorts = providers.gradleProperty("paperReaderDevSourceSupportedSorts").orElse("relevance,newest,oldest").get()
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
require(devSourceMinimumInterval.toLongOrNull()?.let { it >= 0 } == true)
require(devThemeSigner.isBlank() || devThemeSigner.matches(Regex("[0-9a-fA-F]{64}")))
require(devThemeVersion.toLongOrNull() != null)
require(connectedTestApplicationIdSuffix == null || connectedTestApplicationIdSuffix.matches(Regex("\\.[a-z][a-z0-9_]*")))

gradle.taskGraph.whenReady {
    val connectedAppTasks = allTasks.map { it.path }.filter { it.startsWith(":app:connected") }
    if (connectedAppTasks.isNotEmpty() && connectedTestApplicationIdSuffix == null) {
        throw GradleException(
            "Refusing to run ${connectedAppTasks.joinToString()} without " +
                "-PpaperReaderConnectedTestApplicationIdSuffix=.uitest. Without the suffix, the connected " +
                "run uninstalls dev.paperreader.app and wipes the user's library. Rerun with: " +
                ".\\gradlew.bat hostConnectedTest '-PpaperReaderConnectedTestApplicationIdSuffix=.uitest'. " +
                "Do not remove this guard.",
        )
    }
}

android {
    buildToolsVersion = libs.versions.buildTools.get()
    namespace = "dev.paperreader.app"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "dev.paperreader.app"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = appVersionCode
        versionName = appVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
            enableUnitTestCoverage = true
            enableAndroidTestCoverage = true
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

    testCoverage {
        jacocoVersion = libs.versions.jacoco.get()
    }

    testOptions {
        animationsDisabled = true
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

androidComponents {
    onVariants { variant ->
        variant.buildConfigFields?.apply {
            put("DEV_SOURCE_PACKAGE", BuildConfigField("String", devSourcePackage.asBuildConfigString(), null))
            put("DEV_SOURCE_SERVICE", BuildConfigField("String", devSourceService.asBuildConfigString(), null))
            put("DEV_SOURCE_PROVIDER_ID", BuildConfigField("String", devSourceProviderId.asBuildConfigString(), null))
            put("DEV_SOURCE_DISPLAY_NAME", BuildConfigField("String", devSourceDisplayName.asBuildConfigString(), null))
            put("DEV_SOURCE_SIGNER_SHA256", BuildConfigField("String", devSourceSigner.asBuildConfigString(), null))
            put("DEV_SOURCE_VERSION_CODE", BuildConfigField("long", "${devSourceVersion}L", null))
            put("DEV_SOURCE_MINIMUM_INTERVAL_MILLIS", BuildConfigField("long", "${devSourceMinimumInterval}L", null))
            put("DEV_SOURCE_CAPABILITIES", BuildConfigField("String", devSourceCapabilities.asBuildConfigString(), null))
            put("DEV_SOURCE_ROLES", BuildConfigField("String", devSourceRoles.asBuildConfigString(), null))
            put("DEV_SOURCE_IDENTIFIER_TYPES", BuildConfigField("String", devSourceIdentifierTypes.asBuildConfigString(), null))
            put("DEV_SOURCE_SUPPORTED_SORTS", BuildConfigField("String", devSourceSupportedSorts.asBuildConfigString(), null))
            put("DEV_THEME_PACKAGE", BuildConfigField("String", devThemePackage.asBuildConfigString(), null))
            put("DEV_THEME_SERVICE", BuildConfigField("String", devThemeService.asBuildConfigString(), null))
            put("DEV_THEME_DISPLAY_NAME", BuildConfigField("String", devThemeDisplayName.asBuildConfigString(), null))
            put("DEV_THEME_ID", BuildConfigField("String", devThemeId.asBuildConfigString(), null))
            put("DEV_THEME_SIGNER_SHA256", BuildConfigField("String", devThemeSigner.asBuildConfigString(), null))
            put("DEV_THEME_VERSION_CODE", BuildConfigField("long", "${devThemeVersion}L", null))
            put(
                "OFFICIAL_SOURCE_STORE_URL",
                BuildConfigField(
                    "String",
                    "https://raw.githubusercontent.com/ImAno177/PaperReader-sources/main/registry/v2/index.signed.json"
                        .asBuildConfigString(),
                    null,
                ),
            )
            put(
                "OFFICIAL_SOURCE_STORE_ID",
                BuildConfigField("String", "paperreader.official.sources".asBuildConfigString(), null),
            )
            put(
                "OFFICIAL_SOURCE_STORE_PUBLIC_KEY",
                BuildConfigField(
                    "String",
                    "7pUD6Tvcjk1Kf/eS+JdKnXPBktUaYisYdfcbsvB30VA=".asBuildConfigString(),
                    null,
                ),
            )
        }
    }
}

dependencies {
    val composeBom = platform(libs.androidx.compose.bom)

    implementation(project(":logic"))
    implementation(project(":extension-api"))
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.pdf.viewer)
    implementation(libs.material)
    implementation(libs.okhttp)

    testImplementation(libs.junit)

    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.compose.ui.test.accessibility)
    androidTestImplementation(libs.androidx.test.espresso)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    androidTestImplementation(libs.androidx.test.junit)
}
