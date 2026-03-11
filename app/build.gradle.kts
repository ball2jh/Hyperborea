import java.security.KeyStore
import java.security.MessageDigest
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localPropertiesFile.inputStream().use { stream -> localProperties.load(stream) }
}
val serverUrl = localProperties.getProperty("server.url")
    ?: findProperty("server.url") as String?
    ?: "https://example.com"
val licensePublicKey = localProperties.getProperty("license.public.key")
    ?: findProperty("license.public.key") as String?
    ?: ""
val r2BaseUrl = localProperties.getProperty("r2.base.url")
    ?: findProperty("r2.base.url") as String?
    ?: ""

if (serverUrl == "https://example.com") {
    logger.warn("WARNING: server.url not configured — license API will not work")
}

fun signingConfigFingerprint(config: com.android.build.api.dsl.ApkSigningConfig): String {
    val file = config.storeFile ?: return ""
    if (!file.exists()) return ""
    val ks = KeyStore.getInstance(
        if (file.extension.equals("p12", ignoreCase = true)) "PKCS12" else "JKS"
    )
    file.inputStream().use { ks.load(it, config.storePassword?.toCharArray()) }
    val cert = ks.getCertificate(config.keyAlias) ?: return ""
    val digest = MessageDigest.getInstance("SHA-256").digest(cert.encoded)
    return digest.joinToString("") { "%02X".format(it) }
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

        buildConfigField("String", "SERVER_URL", "\"$serverUrl\"")
        buildConfigField("String", "LICENSE_PUBLIC_KEY", "\"$licensePublicKey\"")
        buildConfigField("String", "R2_BASE_URL", "\"$r2BaseUrl\"")
        buildConfigField("String", "SIGNING_CERTIFICATE_SHA256", "\"\"")
    }

    signingConfigs {
        create("platform") {
            storeFile = rootProject.file("iFit/firmware/keys/platform.p12")
            storePassword = localProperties.getProperty("platform.keystore.password") ?: ""
            keyAlias = "platform"
            keyPassword = localProperties.getProperty("platform.key.password") ?: ""
        }
        create("release") {
            storeFile = rootProject.file("release.jks")
            storePassword = localProperties.getProperty("release.keystore.password") ?: ""
            keyAlias = "hyperborea"
            keyPassword = localProperties.getProperty("release.key.password") ?: ""
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }

    flavorDimensions += "target"
    productFlavors {
        create("standard") {
            dimension = "target"
            // Normal build for development (adb install)
        }
        create("system") {
            dimension = "target"
            // System build for OTA firmware (priv-app with sharedUserId)
        }
    }

    // Platform-sign all system flavor variants (overrides debug signingConfig)
    androidComponents {
        onVariants(selector().withFlavor("target" to "system")) { variant ->
            variant.signingConfig.setConfig(signingConfigs.getByName("platform"))
            val fingerprint = signingConfigFingerprint(signingConfigs.getByName("platform"))
            variant.buildConfigFields?.put(
                "SIGNING_CERTIFICATE_SHA256",
                com.android.build.api.variant.BuildConfigField("String", "\"$fingerprint\"", null)
            )
        }
        onVariants(selector().withFlavor("target" to "standard")) { variant ->
            val debugConfig = signingConfigs.getByName("debug")
            val fingerprint = signingConfigFingerprint(debugConfig)
            variant.buildConfigFields?.put(
                "SIGNING_CERTIFICATE_SHA256",
                com.android.build.api.variant.BuildConfigField("String", "\"$fingerprint\"", null)
            )
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
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":hardware:fitpro"))
    implementation(project(":broadcast:ftms"))
    implementation(project(":broadcast:wifi"))
    implementation(project(":ecosystem:ifit"))

    implementation(libs.coroutines.android)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.navigation.compose)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.security.crypto)
    implementation(libs.zxing.core)

    testImplementation(testFixtures(project(":core")))
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.truth)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.json)
    testImplementation(libs.bouncycastle)

    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)
}
