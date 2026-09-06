import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.kotlin.android)
    alias(libs.plugins.compose.compiler)
    kotlin("plugin.serialization") version "2.0.20"
}

// Diagnostic-log delivery credentials, read from local.properties (gitignored) at compile time so they
// never appear in the repo or any settings screen. Absent keys → "" and LogReporter then skips sending.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) FileInputStream(f).use { load(it) }
}

android {
    namespace = "com.enderthor.kpower"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.enderthor.kpower"
        minSdk = 26   // Karoo 2 = Android 8 (API 26); lets FileLogTree's java.time work without desugaring
        targetSdk = 34
        versionCode = 202606131
        versionName = "3.0.0"

        // Telegram bot for diagnostic-log delivery (only used when the rider enables diagnostic logging).
        // From local.properties: calib.bot_token / calib.chat_id. Absent → "" → LogReporter is a no-op.
        buildConfigField("String", "CALIB_BOT_TOKEN", "\"${localProps.getProperty("calib.bot_token", "")}\"")
        buildConfigField("String", "CALIB_CHAT_ID", "\"${localProps.getProperty("calib.chat_id", "")}\"")
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    buildFeatures {
        viewBinding = true
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.hammerhead.karoo.ext)
    implementation(files("libs/android_antlib_4-16-0.aar"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.bundles.androidx.lifeycle)
    implementation(libs.androidx.activity.compose)
    implementation(libs.bundles.compose.ui)
    implementation(libs.androidx.navigation.runtime.ktx)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.color)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.timber)
    implementation(libs.androidx.foundation.android)
    implementation(libs.androidx.foundation.layout.android)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.mapsforge.map.reader)
    testImplementation(libs.junit)
}

