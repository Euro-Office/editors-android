// Engine bridge: JNI binding to the C++ engine (V8 + sdkjs + core rendering)
plugins {
    id("com.android.library")
    kotlin("android")
}

android {
    namespace = "lib.editors.base"
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

    implementation(libs.ktx)
}
