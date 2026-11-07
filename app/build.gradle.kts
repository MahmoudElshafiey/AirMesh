plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    // NOTE: AGP 9 has built-in Kotlin support, so org.jetbrains.kotlin.android
    // must NOT be applied here.
}

android {
    namespace = "com.airmesh"
    compileSdk = 37 // AGP 9.5 supports up to API 37

    defaultConfig {
        applicationId = "com.airmesh"
        minSdk = 29
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)

    // @Preview support for the tab previews (debug builds only).
    debugImplementation("androidx.compose.ui:ui-tooling-preview")

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Used by the throwaway Wi-Fi Direct prototype (sockets, StateFlow).
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}

/**
 * Runs all simulator sweeps with 30 seeds and writes the CSV files under results/.
 *
 * Kept out of the unit-test suite because a full run is intentionally long.
 */
tasks.register<JavaExec>("runExperiments") {
    group = "verification"
    description = "Run all simulator sweeps with 30 seeds into results/*.csv"
    dependsOn("compileDebugKotlin")
    mainClass.set("com.airmesh.sim.ExperimentMainKt")
    classpath = files(
        provider { tasks.named("compileDebugKotlin").get().outputs.files },
        configurations.getByName("debugRuntimeClasspath"),
    )
    workingDir = rootProject.projectDir
}
