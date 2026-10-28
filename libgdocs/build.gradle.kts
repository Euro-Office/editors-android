// Document editor UI
plugins {
    id("com.android.library")
    kotlin("android")
}

android {
    namespace = "lib.editors.gdocs"
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
    implementation(project(":libgeditors"))
    implementation(project(":libdocs"))
    implementation(project(":libresources"))

    implementation(libs.ktx)
    implementation(libs.appcompat)
}
