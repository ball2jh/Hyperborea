plugins {
    alias(libs.plugins.android.lint)
    alias(libs.plugins.kotlin.jvm)
    `java-test-fixtures`
}

lint {
    checkDependencies = true
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
