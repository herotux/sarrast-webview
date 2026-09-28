plugins { id("com.android.application") }

android {
    namespace = "com.herotux.sarrastwebview"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.herotux.sarrastwebview"
        minSdk = 23
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }
}

dependencies {
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
}
