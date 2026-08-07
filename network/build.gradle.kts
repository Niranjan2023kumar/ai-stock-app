import java.util.Properties
import java.io.FileInputStream

plugins {
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.kotlinAndroid)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

fun readLocalProperties(): Properties {
    val props = Properties()
    val f = rootProject.file("local.properties")
    if (f.exists()) props.load(FileInputStream(f))
    return props
}

val localProps = readLocalProperties()

android {
    namespace = "com.example.myapplication3.network"
    compileSdk = 35

    defaultConfig {
        minSdk = 24
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
        buildConfigField("String", "POLYGON_API_KEY", "\"${localProps["POLYGON_API_KEY"] ?: ""}\"")
        buildConfigField("String", "FINNHUB_API_KEY", "\"${localProps["FINNHUB_API_KEY"] ?: ""}\"")
        buildConfigField("String", "ALPHAVANTAGE_API_KEY", "\"${localProps["ALPHAVANTAGE_API_KEY"] ?: ""}\"")
        buildConfigField("String", "OPENAI_API_KEY", "\"${localProps["OPENAI_API_KEY"] ?: ""}\"")
        buildConfigField("String", "ANTHROPIC_API_KEY", "\"${localProps["ANTHROPIC_API_KEY"] ?: ""}\"")
        buildConfigField("String", "NEWS_API_KEY", "\"${localProps["NEWS_API_KEY"] ?: ""}\"")
    }

    buildFeatures { buildConfig = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.retrofit.core)
    implementation(libs.retrofit.converter.gson)
    implementation(libs.okhttp.core)
    implementation(libs.okhttp.logging)
    implementation(libs.gson)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.mockk)
}