// Editor UI framework: base activity, tile viewer, PDF viewer
plugins {
    id("com.android.library")
    kotlin("android")
    // The editors' chrome (top bar, command sheet, menus) is Compose Material 3
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "lib.editors.gbase"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }

    buildTypes {
        // JaCoCo report of the unit tests (createDebugUnitTestCoverageReport) for the CI's
        // coverage check
        getByName("debug") {
            enableUnitTestCoverage = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(project(":libtoolkit"))
    // MessengerClient: users to mention in comments (DocSpace)
    implementation(project(":libshared"))
    implementation(project(":libeditors"))
    implementation(project(":libresources"))

    implementation(libs.ktx)
    implementation(libs.appcompat)
    implementation(libs.google.material)
    implementation(libs.lifecycle.runtime)
    implementation(libs.kotlin.coroutines.android)
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.uiToolingPreview)
    debugImplementation(libs.compose.uiTooling)

    testImplementation(libs.junit)
}
