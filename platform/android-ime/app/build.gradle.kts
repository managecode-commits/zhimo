// Copyright © 2026 立方田 <managecode@gmail.com>
import java.security.MessageDigest
import groovy.json.JsonSlurper

plugins {
    id("com.android.application")
}

// Streaming is mandatory; legacy build flags cannot produce a Whisper APK.
check(providers.gradleProperty("zhimo.streamingSpeech").orNull != "false") {
    "Android now requires bundled streaming speech; remove -Pzhimo.streamingSpeech=false"
}
val stageHandwritingAssets by tasks.registering(Sync::class) {
    from(rootDir.resolve("../../models")) {
        include("handwriting/**", "handwriting-image/**")
    }
    into(layout.buildDirectory.dir("generated/handwritingAssets"))
}
val verifyStreamingSpeech by tasks.registering(Exec::class) {
    commandLine("python3", rootDir.resolve("../../tools/verify-streaming-speech-build.py").absolutePath,
        "--native")
}
tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(verifyStreamingSpeech, stageHandwritingAssets) }

val verifyBundledHandwriting by tasks.registering {
    val modelRoot = rootDir.resolve("../../models/handwriting")
    inputs.dir(rootDir.resolve("../../models/handwriting"))
    inputs.dir(rootDir.resolve("../../models/handwriting-image"))
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

val releaseStoreFile = providers.environmentVariable("ZHIMO_ANDROID_KEYSTORE").orNull
val releaseKeyAlias = providers.environmentVariable("ZHIMO_ANDROID_KEY_ALIAS").orNull
val releaseStorePassword = providers.environmentVariable("ZHIMO_ANDROID_STORE_PASSWORD").orNull
val releaseKeyPassword = providers.environmentVariable("ZHIMO_ANDROID_KEY_PASSWORD").orNull
val releaseSigningValues = listOf(
    releaseStoreFile,
    releaseKeyAlias,
    releaseStorePassword,
    releaseKeyPassword,
)
val releaseSigningReady = releaseSigningValues.all { !it.isNullOrBlank() }
val releaseVersion = JsonSlurper().parse(rootDir.resolve("../../release/version.json")) as Map<*, *>
val appVersionCode = (releaseVersion["android_version_code"] as Number).toInt()
val appVersionName = releaseVersion["version"] as String
check(appVersionCode > 1 && appVersionName.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+(?:-[a-zA-Z0-9.]+)?"))) {
    "Invalid release/version.json"
}
check(releaseSigningValues.none { !it.isNullOrBlank() } || releaseSigningReady) {
    "Android release signing variables must be provided together"
}

android {
    namespace = "dev.zhimo.ime"
    compileSdk = 37
    ndkVersion = "28.2.13676358"

    defaultConfig {
        buildConfigField("boolean", "STREAMING_SPEECH", "true")
        applicationId = "dev.zhimo.ime"
        minSdk = 26
        targetSdk = 37
        versionCode = appVersionCode
        versionName = appVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk.abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        externalNativeBuild.cmake.arguments += "-DZHIMO_ROOT=${rootDir.resolve("../..").absolutePath}"
    }
    buildFeatures.buildConfig = true

    // Whitelist handwriting assets; never package shared desktop Whisper models.
    sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/handwritingAssets").get().asFile)
    sourceSets.getByName("main").assets.srcDir(rootDir.resolve("../../target/streaming-speech/android-assets"))
    sourceSets.getByName("main").jniLibs.srcDir(rootDir.resolve("../../target/streaming-speech/asr-only/jni"))
    androidResources.noCompress += "bin"
    androidResources.noCompress += "onnx"

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
    implementation(files(rootDir.resolve("../../target/streaming-speech/api/classes.jar")))
    testImplementation("junit:junit:4.13.2")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.22.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
}
