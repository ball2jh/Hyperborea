plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.nettarion.hyperborea.broadcast.ftms"
    compileSdk = 36

    defaultConfig {
        minSdk = 25
    }
}

dependencies {
    api(project(":core"))
    implementation(libs.coroutines.android)
    implementation(libs.javax.inject)

    testImplementation(testFixtures(project(":core")))
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.truth)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.turbine)
}
