plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
}

tasks.register<Exec>("testInstallOnEmulator") {
    group = "verification"
    description = "Builds the debug APK, starts an Android emulator if needed, and installs the app."
    dependsOn(":app:assembleDebug")

    val apkPath = layout.projectDirectory.file("app/build/outputs/apk/debug/app-debug.apk").asFile
    val scriptPath = layout.projectDirectory.file("scripts/install-on-emulator.ps1").asFile
    val avdName = providers.gradleProperty("avdName").orNull

    commandLine(
        "powershell",
        "-NoProfile",
        "-ExecutionPolicy",
        "Bypass",
        "-File",
        scriptPath.absolutePath,
        "-ApkPath",
        apkPath.absolutePath,
    )

    if (!avdName.isNullOrBlank()) {
        args("-AvdName", avdName)
    }
}
