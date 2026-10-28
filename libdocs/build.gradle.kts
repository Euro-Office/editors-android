// Typed document editor API on top of libeditors
plugins {
    id("com.android.library")
    kotlin("android")
}

android {
    namespace = "lib.editors.docs"
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
    implementation(project(":libeditors"))

    implementation(libs.ktx)
}
