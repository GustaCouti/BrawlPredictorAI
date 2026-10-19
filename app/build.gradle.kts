plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.brawlpredictor.ai"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.brawlpredictor.ai"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.3.0-dev"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isIncludeAndroidResources = true }
}

dependencies {
    // Modelo OCR empacotado no app: não depende de baixar o modelo na primeira execução.
    implementation("com.google.mlkit:text-recognition:16.0.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
}
