import java.security.MessageDigest

plugins {
    id("com.android.application")
}

val verifyBundledHandwriting by tasks.registering {
    val modelRoot = rootDir.resolve("../../models/handwriting")
    inputs.dir(rootDir.resolve("../../models"))
    doLast {
        val model = modelRoot.resolve("zh-cn/handwriting-zh_CN.model")
        check(model.length() == 26_834_816L) { "Bundled handwriting model missing or truncated" }
        val digest = MessageDigest.getInstance("SHA-256")
        model.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        check(digest.digest().joinToString("") { "%02x".format(it) } ==
            "e16153d1ff267cd479aea260d6f71a3edda8b4ba06db2d121513adda65a4449e") {
            "Bundled handwriting model SHA-256 mismatch"
        }
        for (relative in listOf("ZINNIA-COPYING", "zh-cn/COPYING",
            "zh-cn/source/handwriting-zh_CN.xml", "zh-cn/source/Makefile",
            "zh-cn/source/README.txt", "zh-cn/source/VERSION", "zh-cn/source/handwriting-zh_CN.meta")) {
            check(modelRoot.resolve(relative).isFile) { "Missing handwriting license/source: $relative" }
        }
        val imageRoot = rootDir.resolve("../../models/handwriting-image")
        val imageModel = imageRoot.resolve("pp-ocrv5-mobile-rec.onnx")
        check(imageModel.isFile) { "Experimental image model missing" }
        val imageHash = MessageDigest.getInstance("SHA-256").digest(imageModel.readBytes()).joinToString("") { "%02x".format(it) }
        check(imageHash == "5825fc7ebf84ae7a412be049820b4d86d77620f204a041697b0494669b1742c5") { "Image model SHA-256 mismatch" }
        for (name in listOf("PaddleOCR-LICENSE", "RapidOCR-LICENSE", "ONNXRUNTIME-LICENSE", "ONNXRUNTIME-ThirdPartyNotices.txt", "MODEL-CARD.md", "README.md")) {
            check(imageRoot.resolve(name).isFile) { "Image model notice missing: $name" }
        }
    }
}
tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(verifyBundledHandwriting) }

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
        ndk.abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        externalNativeBuild.cmake.arguments += "-DSHURUFA_ROOT=${rootDir.resolve("../..").absolutePath}"
    }

    // Include the model, corresponding source and licenses for first-install offline use.
    sourceSets.getByName("main").assets.srcDir(rootDir.resolve("../../models"))

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
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.22.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
}
