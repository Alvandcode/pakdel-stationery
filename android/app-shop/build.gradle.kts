import java.io.File
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

fun localSecret(key: String): String {
    val props = Properties()
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { props.load(it) }
    return props.getProperty(key) ?: System.getenv(key) ?: ""
}

// امضای نسخه انتشار: از متغیر محیطی (GitHub Actions) یا local.properties.
val keystorePath: String = localSecret("PAKDEL_KEYSTORE")
val keystorePassword: String = localSecret("PAKDEL_KEYSTORE_PASSWORD")
val keyAliasValue: String = localSecret("PAKDEL_KEY_ALIAS")
val keyPasswordValue: String = localSecret("PAKDEL_KEY_PASSWORD")
val hasKeystore = keystorePath.isNotBlank() && File(keystorePath).exists()

android {
    namespace = "com.pakdel.shop"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.pakdel.shop"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "1.0.1"

        buildConfigField("String", "SUPABASE_URL", "\"${localSecret("SUPABASE_URL")}\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"${localSecret("SUPABASE_ANON_KEY")}\"")
    }

    signingConfigs {
        if (hasKeystore) {
            create("release") {
                storeFile = File(keystorePath)
                storePassword = keystorePassword
                keyAlias = keyAliasValue
                keyPassword = keyPasswordValue
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            if (hasKeystore) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(project(":core:designsystem"))
    implementation(project(":core:data"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.navigation.compose)
    implementation(libs.hilt.android)
    implementation(libs.hilt.navigation.compose)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
}
