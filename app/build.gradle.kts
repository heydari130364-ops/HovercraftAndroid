plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.example.hovercraftusb"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.example.hovercraftusb"
        minSdk = 23
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }
}
dependencies {
    implementation("com.github.mik3y:usb-serial-for-android:3.9.0")
}
