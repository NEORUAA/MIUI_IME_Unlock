@file:Suppress("UnstableApiUsage")
plugins {
    id("com.android.application")
    id("kotlin-android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    compileSdk = 37
    namespace = "com.xposed.wetypehook"

    defaultConfig {
        applicationId = "com.xposed.wetypehook"
        minSdk = 31
        targetSdk = 37
        versionCode = 18
        versionName = "1.20"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles("proguard-rules.pro")
        }
    }
    buildFeatures {
        compose = true
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    packaging {
        resources {
            excludes += arrayOf("META-INF/**", "kotlin/**", "google/**", "**.bin")
        }
    }
    applicationVariants.all {
        val outputFileName = "WeType_UI_Enhanced-${versionName}_${buildType.name}.apk"
        outputs.all {
            val output = this as? com.android.build.gradle.internal.api.BaseVariantOutputImpl
            output?.outputFileName = outputFileName
        }
    }
    dependenciesInfo {
        includeInApk = false
    }
}

kotlin {
    sourceSets.all {
        languageSettings.languageVersion = "2.0"
    }
}

dependencies {
    compileOnly("de.robv.android.xposed:api:82")
    implementation("com.github.kyuubiran:EzXHelper:1.0.3")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.foundation:foundation-android:1.10.6")
    implementation("androidx.compose.ui:ui-android:1.10.6")
    implementation("androidx.compose.ui:ui-graphics-android:1.10.6")
    implementation("androidx.compose.ui:ui-text-android:1.10.6")
    implementation("top.yukonga.miuix.kmp:miuix-ui-android:0.9.0")
    implementation("top.yukonga.miuix.kmp:miuix-preference-android:0.9.0")
    implementation("top.yukonga.miuix.kmp:miuix-core-android:0.9.0")
    implementation("top.yukonga.miuix.kmp:miuix-shapes-android:0.9.0")
    implementation("top.yukonga.miuix.kmp:miuix-icons-android:0.8.6") {
        exclude(group = "top.yukonga.miuix.kmp", module = "miuix-android")
    }
    implementation("io.github.kyant0:capsule:2.1.3")
}
