plugins {
    id("com.android.application")
}

android {
    namespace = "dev.shurufa.ime"
    compileSdk = 37
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "dev.shurufa.ime"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        externalNativeBuild.cmake.arguments += "-DSHURUFA_ROOT=${rootDir.resolve("../..").absolutePath}"
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}
