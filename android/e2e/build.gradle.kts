import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.kotlin.android)
}

// Module de test « boîte noire » : il s'exécute dans son propre processus
// (auto-instrumenté) et pilote l'application installée par l'accessibilité,
// sans dépendre de son code. On peut donc tester l'APK exact qui sera publié,
// y compris après optimisation R8.
android {
    namespace = "app.boussole.e2e"
    compileSdk = 36

    defaultConfig {
        minSdk = 31
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    targetProjectPath = ":app"
    experimentalProperties["android.experimental.self-instrumenting"] = true

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.test.runner)
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.uiautomator)
}
