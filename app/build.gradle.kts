plugins {
    id("com.android.application")
}

android {
    namespace = "com.example.localproplayer"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.example.localproplayer"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "0.2.0"
    }
}

dependencies {
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-ui:1.11.1")
    implementation("androidx.media3:media3-session:1.11.1")
}
