plugins {
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.kotlinAndroid)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.example.myapplication3.database"
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

// Room writes one schema JSON per @Database version into database/schemas/ on
// every build (requires exportSchema = true in AppDatabase). Those files are
// the ground truth that makes Migrations reviewable and testable — keep the
// directory in version-controlled storage, never delete it.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(project(":core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.room.paging)
    implementation(libs.gson)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.mockk)
    testImplementation(libs.room.testing)
}
