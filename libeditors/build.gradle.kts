// Engine bridge: JNI binding to the C++ engine (V8 + sdkjs + core rendering)
plugins {
    id("com.android.library")
    kotlin("android")
}

// libx2t is only included when the app is built with the native core (-PwithNativeCore=true)
val hasNativeCore = findProject(":libx2t") != null

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

    sourceSets["main"].java.srcDir(if (hasNativeCore) "src/x2t/java" else "src/x2tStub/java")
}

dependencies {
    implementation(project(":libtoolkit"))

    implementation(libs.ktx)

    if (hasNativeCore) {
        implementation(project(":libx2t"))
    }
}
