import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Clé de signature définitive : fournie par l'utilisateur via l'environnement
// (phase 6). Sans elle, l'APK est signé avec la clé de debug et marqué « test »
// — à n'utiliser qu'avec des données fictives (cf. docs/SECURITE.md §6).
val releaseKeystore = System.getenv("BOUSSOLE_KEYSTORE")?.let(::file)?.takeIf { it.exists() }
val isTestBuild = releaseKeystore == null

android {
    namespace = "app.boussole"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.boussole"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        resValue("string", "app_name", if (isTestBuild) "Boussole (test)" else "Boussole")
        resValue("bool", "is_test_build", isTestBuild.toString())
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = System.getenv("BOUSSOLE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("BOUSSOLE_KEY_ALIAS")
                keyPassword = System.getenv("BOUSSOLE_KEY_PASSWORD")
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        resValues = true
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
            )
        }
    }

    // Pas de bloc de métadonnées de dépendances chiffré pour Google dans l'APK.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("app.boussole:core")

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    implementation(libs.fragment)
    implementation(libs.core.ktx)
    implementation(libs.lifecycle.process)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.biometric)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.mlkit.text.recognition)
}
