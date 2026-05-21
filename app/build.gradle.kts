plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.vienna.server.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.vienna.server.android"
        minSdk = 26
        targetSdk = 28
        versionCode = 1
        versionName = "0.0.5-android"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*")
        }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("com.github.rtm516:Apktool:3d177ffa61")
    implementation("ro.andob.androidawt:androidawt:1.0.4")
    implementation("org.bouncycastle:bcpkix-jdk15on:1.56")
    implementation("com.github.kellinwood.zip-signer:zipsigner-lib:2bb3b69ff3")
    implementation("com.github.kellinwood.zip-signer:zipsigner-lib-optional:2bb3b69ff3")
    implementation("com.github.kellinwood.zip-signer:android-sun-jarsign-support:2bb3b69ff3")
    implementation("com.github.rtm516.jgit:org.eclipse.jgit:61810ad68a")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
