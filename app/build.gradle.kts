plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.nettarion.hyperborea"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.nettarion.hyperborea"
        minSdk = 25
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        buildConfigField("String", "UPDATE_MANIFEST_URL", "\"https://example.com/hyperborea/manifest.json\"")
    }

    signingConfigs {
        create("system") {
            storeFile = rootProject.file("iFit/firmware/keys/platform.p12")
            storePassword = "android"
            keyAlias = "platform"
            keyPassword = "android"
        }
    }

    flavorDimensions += "target"
    productFlavors {
        create("standard") {
            dimension = "target"
            isDefault = true
        }
        create("system") {
            dimension = "target"
            signingConfig = signingConfigs.getByName("system")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        // Ensure lint never analyzes stale intermediates by treating warnings as errors
        // and always checking dependencies across all modules.
        checkDependencies = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":hardware:fitpro"))
    implementation(project(":broadcast:ftms"))
    implementation(project(":broadcast:dircon"))
    implementation(project(":ecosystem:ifit"))

    implementation(libs.coroutines.android)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.navigation.compose)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    testImplementation(testFixtures(project(":core")))
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.truth)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.mockk)
    testImplementation(libs.robolectric)

    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)
}
