// Editor UI framework: base activity, tile viewer, PDF viewer
plugins {
    id("com.android.library")
    kotlin("android")
}

android {
    namespace = "lib.editors.gbase"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(project(":libtoolkit"))
    implementation(project(":libeditors"))
    implementation(project(":libresources"))

    implementation(libs.ktx)
    implementation(libs.appcompat)
    implementation(libs.lifecycle.runtime)
    implementation(libs.kotlin.coroutines.android)
}
