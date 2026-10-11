plugins {
    id("com.android.application")
}

android {
    namespace = "com.example.depthclock"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.depthclock"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // All the real code lives in :wallpaper (setup screen + live wallpaper service)
    implementation(project(":wallpaper"))
}
