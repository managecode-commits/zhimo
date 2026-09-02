plugins {
    id("com.android.application")
}

val releaseStoreFile = providers.environmentVariable("SHURUFA_ANDROID_KEYSTORE").orNull
val releaseKeyAlias = providers.environmentVariable("SHURUFA_ANDROID_KEY_ALIAS").orNull
val releaseStorePassword = providers.environmentVariable("SHURUFA_ANDROID_STORE_PASSWORD").orNull
val releaseKeyPassword = providers.environmentVariable("SHURUFA_ANDROID_KEY_PASSWORD").orNull
val releaseSigningValues = listOf(
    releaseStoreFile,
    releaseKeyAlias,
    releaseStorePassword,
    releaseKeyPassword,
)
val releaseSigningReady = releaseSigningValues.all { !it.isNullOrBlank() }
check(releaseSigningValues.none { !it.isNullOrBlank() } || releaseSigningReady) {
    "Android release signing variables must be provided together"
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
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        externalNativeBuild.cmake.arguments += "-DSHURUFA_ROOT=${rootDir.resolve("../..").absolutePath}"
    }

    signingConfigs {
        if (releaseSigningReady) {
            create("release") {
                storeFile = file(requireNotNull(releaseStoreFile))
                keyAlias = releaseKeyAlias
                storePassword = releaseStorePassword
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = false
            ndk.debugSymbolLevel = "SYMBOL_TABLE"
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

dependencies {
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
}
