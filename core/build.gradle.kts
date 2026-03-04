plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-test-fixtures`
}

dependencies {
    api(libs.coroutines.core)

    testFixturesImplementation(libs.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.truth)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.turbine)
}
