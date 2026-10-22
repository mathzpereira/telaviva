plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "app.telaviva.androidtv"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.telaviva.androidtv"
        minSdk = 28
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
}

dependencies {
    testImplementation(libs.junit)

    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.espresso.core)
}
