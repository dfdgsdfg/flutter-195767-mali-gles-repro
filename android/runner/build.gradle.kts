plugins {
    id("com.android.application")
}

android {
    namespace = "com.example.flutter_mali_repro.runner"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.flutter_mali_repro.runner"
        minSdk = 23
        targetSdk = 28
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "com.example.flutter_mali_repro.runner.MaliInstrumentation"
    }
}
