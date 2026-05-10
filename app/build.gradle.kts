import org.gradle.api.tasks.bundling.Zip
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.security.MessageDigest
import java.util.Properties

abstract class StageReleaseBundleTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceApk: RegularFileProperty

    @get:OutputFile
    abstract val releaseApk: RegularFileProperty

    @get:Input
    abstract val requiredProperties: MapProperty<String, String>

    @TaskAction
    fun stageReleaseBundle() {
        val missing = requiredProperties.get().filterValues { it.isBlank() }.keys
        require(missing.isEmpty()) { "Missing required keys in local.properties: ${missing.joinToString()}" }

        val source = sourceApk.get().asFile
        val destination = releaseApk.get().asFile
        destination.parentFile.mkdirs()
        source.copyTo(destination, overwrite = true)
    }
}

abstract class ReportReleaseTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val releaseApk: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val releaseZip: RegularFileProperty

    @get:Input
    abstract val rootDirPath: Property<String>

    @get:Input
    abstract val versionCodeInput: Property<Int>

    @get:Input
    abstract val versionNameInput: Property<String>

    @TaskAction
    fun reportRelease() {
        val rootDir = File(rootDirPath.get())
        val apk = releaseApk.get().asFile
        val zip = releaseZip.get().asFile
        val sha256 = MessageDigest.getInstance("SHA-256")
            .digest(apk.readBytes())
            .joinToString("") { byte -> "%02x".format(byte) }

        println("\nRelease prepared: ${versionNameInput.get()} (${versionCodeInput.get()})")
        println("  APK: ${apk.relativeTo(rootDir)} (${apk.length() / 1024} KB)")
        println("  ZIP: ${zip.relativeTo(rootDir)} (${zip.length() / 1024} KB)")
        println("  SHA-256: $sha256")
        println("\nServer manifest values:")
        println("  \"versionCode\": ${versionCodeInput.get()},")
        println("  \"versionName\": \"${versionNameInput.get()}\",")
        println("  \"sha256\": \"$sha256\"")
    }
}

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
// Optional server endpoints for the in-app self-update and support-diagnostics features.
// Both default to empty, in which case those features are disabled. Set them in local.properties
// (server.url, r2.base.url) or via -P flags to point a fork at its own infrastructure.
val serverUrl = localProperties.getProperty("server.url")
    ?: findProperty("server.url") as String?
    ?: ""
val r2BaseUrl = localProperties.getProperty("r2.base.url")
    ?: findProperty("r2.base.url") as String?
    ?: ""

val versionNameProp = findProperty("appVersionName") as String
val versionParts = versionNameProp.split(".").map { it.toInt() }
require(versionParts.size == 3) { "appVersionName must be MAJOR.MINOR.PATCH, got: $versionNameProp" }
val (major, minor, patch) = versionParts

android {
    namespace = "com.nettarion.hyperborea"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.nettarion.hyperborea"
        minSdk = 22
        targetSdk = 36
        versionCode = major * 10000 + minor * 100 + patch
        versionName = versionNameProp

        buildConfigField("String", "SERVER_URL", "\"$serverUrl\"")
        buildConfigField("String", "R2_BASE_URL", "\"$r2BaseUrl\"")

        val gitHash = providers.exec { commandLine("git", "rev-parse", "--short", "HEAD") }
            .standardOutput.asText.get().trim()
        buildConfigField("String", "GIT_HASH", "\"$gitHash\"")
    }

    signingConfigs {
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
            // Use the release keystore when present (release.jks + passwords in local.properties);
            // otherwise fall back to the debug key so the project builds without those secrets.
            signingConfig = if (rootProject.file("release.jks").exists()) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    flavorDimensions += "target"
    productFlavors {
        create("standard") {
            dimension = "target"
            // Standard build for development and release (adb install / direct deploy)
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        jniLibs {
            // androidx.graphics.path ships a prebuilt native library AGP cannot strip.
            keepDebugSymbols += "**/libandroidx.graphics.path.so"
        }
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
    implementation(project(":sensor:hrm"))

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

    testImplementation(testFixtures(project(":core")))
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.truth)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.json)

    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)
}

// Ensure prepareRelease runs tasks in the right order
tasks.configureEach {
    if (name != "clean") mustRunAfter("clean")
    if (name == "test" || name.startsWith("test")) mustRunAfter("lint")
    if (name.contains("assemble", ignoreCase = true)) mustRunAfter("test")
}

val vc = android.defaultConfig.versionCode
val vn = android.defaultConfig.versionName
val releaseDir = rootProject.layout.projectDirectory.dir("release/Hyperborea")
val stagedReleaseApk = releaseDir.file("apps/Hyperborea.apk")
val packagedReleaseZip = rootProject.layout.projectDirectory.file("release/Hyperborea-v$vn.zip")
val requiredKeys = listOf(
    "release.keystore.password",
    "release.key.password"
)
val capturedProps = requiredKeys.associateWith { localProperties.getProperty(it).orEmpty() }

val stageReleaseBundle = tasks.register<StageReleaseBundleTask>("stageReleaseBundle") {
    dependsOn("assembleStandardRelease")
    sourceApk.set(layout.buildDirectory.file("outputs/apk/standard/release/app-standard-release.apk"))
    releaseApk.set(stagedReleaseApk)
    requiredProperties.set(capturedProps)
}

val packageReleaseZip = tasks.register<Zip>("packageReleaseZip") {
    dependsOn(stageReleaseBundle)

    archiveFileName.set(packagedReleaseZip.asFile.name)
    destinationDirectory.set(packagedReleaseZip.asFile.parentFile)
    includeEmptyDirs = false
    dirPermissions { unix("755") }

    from(releaseDir) {
        exclude(".DS_Store", "**/.DS_Store")

        eachFile {
            permissions {
                unix(if (relativeSourcePath.pathString == "deploy.sh") "755" else "644")
            }
        }
    }
}

tasks.register<ReportReleaseTask>("prepareRelease") {
    dependsOn("clean", "lint", "test", packageReleaseZip)
    releaseApk.set(stagedReleaseApk)
    releaseZip.set(packagedReleaseZip)
    rootDirPath.set(rootProject.projectDir.absolutePath)
    versionCodeInput.set(vc)
    versionNameInput.set(vn)
}
