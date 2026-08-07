plugins {
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.kotlinAndroid)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.example.myapplication3.notifications"
    compileSdk = 35

    defaultConfig {
        minSdk = 24
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":strategy"))
    implementation(project(":news"))
    implementation(project(":database"))
    implementation(libs.okhttp.core)
    implementation(libs.androidx.core.ktx)
    // ScannerWorker reads the "daily_capital" preference for the loss-limit check.
    implementation(libs.datastore.preferences)
    implementation(libs.coroutines.core)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.workmanager.ktx)
    implementation(libs.hilt.work)
    ksp(libs.hilt.work.compiler)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.mockk)
    testImplementation(libs.workmanager.testing)
}
