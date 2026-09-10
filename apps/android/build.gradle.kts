plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val releaseStoreFile = providers.gradleProperty("BITOS_RELEASE_STORE_FILE").orNull
val releaseStorePassword = providers.gradleProperty("BITOS_RELEASE_STORE_PASSWORD").orNull
val releaseKeyAlias = providers.gradleProperty("BITOS_RELEASE_KEY_ALIAS").orNull
val releaseKeyPassword = providers.gradleProperty("BITOS_RELEASE_KEY_PASSWORD").orNull
val appVersionName = providers.gradleProperty("BITOS_APP_VERSION_NAME").orNull
    ?: error("BITOS_APP_VERSION_NAME must be configured in gradle.properties.")
val appVersionCode = providers.gradleProperty("BITOS_APP_VERSION_CODE").orNull
    ?.toIntOrNull()
    ?.takeIf { it > 0 }
    ?: error("BITOS_APP_VERSION_CODE must be a positive integer.")
val releaseSigningValues = listOf(
    releaseStoreFile,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
)

require(releaseSigningValues.none { it != null } || releaseSigningValues.all { it != null }) {
    "Configure all BITOS_RELEASE_* signing properties or none of them."
}

android {
    namespace = "space.bitos.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "space.bitos.app"
        minSdk = 29
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    if (releaseSigningValues.all { it != null }) {
        signingConfigs.getByName("release") {
            storeFile = file(checkNotNull(releaseStoreFile))
            storePassword = checkNotNull(releaseStorePassword)
            keyAlias = checkNotNull(releaseKeyAlias)
            keyPassword = checkNotNull(releaseKeyPassword)
        }
        buildTypes.getByName("release").signingConfig = signingConfigs.getByName("release")
    }

    packaging.resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
}

dependencies {
    implementation(project(":shared:business-core"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.transformer)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.video)
    implementation(libs.androidx.camera.view)
    implementation(libs.androidx.work.runtime)
    implementation(libs.okhttp)
    implementation(libs.coil.compose)
    implementation(libs.coil.gif)
    implementation(libs.zxing.core)
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation(libs.kotlinx.coroutines.android)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.core)
}
