// Engine bridge: JNI binding to the C++ engine (V8 + sdkjs + core rendering)
plugins {
    id("com.android.library")
    kotlin("android")
}

// The native core (libx2t and the engine) is only included when the app is built
// with -PwithNativeCore=true; otherwise stubs report the engine as unavailable
val hasNativeCore = findProject(":libx2t") != null

// AndroidEditors checkout: core sources, the core-android libraries and the sdkjs bundles
val androidEditorsDir = rootDir.resolve("../..")
val coreLibsDir = providers.gradleProperty("PATH_LIB_BUILD_TOOLS")
    .getOrElse(androidEditorsDir.resolve("build/out/core-android").path)
val sdkjsDir = providers.gradleProperty("SDKJS_DIR")
    .getOrElse(androidEditorsDir.resolve("build/out/sdkjs").path)
val coreFontsDir = providers.gradleProperty("CORE_FONTS_DIR")
    .getOrElse(androidEditorsDir.resolve("core-fonts").path)
val engineAssetsDir = layout.buildDirectory.dir("generated/engineAssets")

android {
    namespace = "lib.editors.base"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()

        if (hasNativeCore) {
            externalNativeBuild {
                cmake {
                    arguments(
                        "-DANDROID_STL=c++_shared",
                        "-DCORE_SOURCE_DIR=${androidEditorsDir.resolve("core").path}",
                        "-DCORE_LIBS_DIR=$coreLibsDir"
                    )
                }
            }
            ndk {
                // The core libraries are only built for 64-bit ABIs
                abiFilters.addAll(listOf("arm64-v8a", "x86_64"))
            }
        }
    }

    if (hasNativeCore) {
        ndkVersion = rootProject.extra.get("NDK_VERSION").toString()
        externalNativeBuild {
            cmake {
                path("../engine/CMakeLists.txt")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    sourceSets["main"].java.srcDir(if (hasNativeCore) "src/native/java" else "src/nativeStub/java")
    if (hasNativeCore) {
        sourceSets["main"].assets.srcDirs("src/native/assets", engineAssetsDir)
    }
}

// The parts of sdkjs the engine loads (see DoctRenderer.config and core's editors.cpp), and
// open fonts with the metrics of the common Microsoft ones, so documents keep their layout
val copyEngineAssets by tasks.registering(Copy::class) {
    from(sdkjsDir) {
        include(
            "word/sdk-all-min.js",
            "word/sdk-all.js",
            "slide/sdk-all-min.js",
            "slide/sdk-all.js",
            "slide/themes/**",
            "cell/sdk-all-min.js",
            "cell/sdk-all.js",
            "common/Native/*.js",
            "common/SmartArts/**",
            "common/libfont/engine/fonts_native.js",
            "vendor/xregexp-all-min.js"
        )
        into("sdkjs")
    }
    from(coreFontsDir) {
        include(
            "ASC.ttf",
            "liberation/LiberationSans-*.ttf", // Arial
            "liberation/LiberationSerif-*.ttf", // Times New Roman
            "liberation/LiberationMono-*.ttf", // Courier New
            "crosextra/Carlito-*.ttf", // Calibri
            "caladea/*.ttf", // Cambria
            "opensans/*.ttf"
        )
        into("bundled-fonts")
    }
    into(engineAssetsDir.map { it.dir("editors/runtime") })
    doFirst {
        if (!file("$sdkjsDir/word/sdk-all.js").exists()) {
            throw GradleException("sdkjs bundles not found in $sdkjsDir (see AndroidEditors/build/README.md)")
        }
    }
}

if (hasNativeCore) {
    tasks.named("preBuild") { dependsOn(copyEngineAssets) }
}

dependencies {
    implementation(project(":libtoolkit"))

    implementation(libs.ktx)
    implementation(libs.kotlin.coroutines.android)

    if (hasNativeCore) {
        implementation(project(":libx2t"))
    }
}
