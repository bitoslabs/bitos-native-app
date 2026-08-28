import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    android {
        namespace = "space.bitos.core"
        compileSdk = 36
        minSdk = 29
        withHostTest {}
    }

    iosArm64()
    iosSimulatorArm64()
    // Intel-simulator slice so the XCFramework links for x86_64 destinations
    // (Rosetta simulators, Intel CI runners).
    iosX64()

    // Native Apple test lane (SBC-019): the machine this repo builds on has
    // no iOS simulator runtime, so common tests execute natively on macOS.
    macosArm64()

    targets.withType<KotlinNativeTarget>().configureEach {
        binaries.framework {
            baseName = "BusinessCore"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}
