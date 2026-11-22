// Neon Auto Clicker for Windows. Same version and shared logic (":core") as the Android app.
//
//   gradlew :desktop:run              start it from source
//   gradlew :desktop:packageWindows   build the installer (Inno Setup) or a portable .zip
//
// The easy way: double-click "Build Windows App.bat" in the project folder.
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

val neonVersion: String = providers.gradleProperty("neon.versionName").get()
// Build number: date and hour (yyMMddHH), the same rule as the Android versionCode.
val buildNumber: String = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyMMddHH"))
version = neonVersion

kotlin {
    // Java 21 to build and to bundle (Android Studio's own Java is used when present;
    // otherwise Gradle downloads one automatically).
    jvmToolchain(21)
}

dependencies {
    implementation(project(":core"))
    implementation("net.java.dev.jna:jna:5.14.0") // Win32 calls (mouse, hotkey, overlay styles), no native code of our own
}

application {
    mainClass.set("com.neon.autoclicker.desktop.MainKt")
    applicationName = "NeonAutoClicker"
    applicationDefaultJvmArgs = listOf("-Xmx256m", "-XX:+UseSerialGC")
}

// Version shown in the app ("Version 1.3.0", "Build 26112215").
val versionResource = tasks.register("versionResource") {
    val out = layout.buildDirectory.dir("generated/version")
    inputs.property("version", neonVersion)
    inputs.property("build", buildNumber)
    outputs.dir(out)
    doLast {
        val f = out.get().file("neon-version.properties").asFile
        f.parentFile.mkdirs()
        f.writeText("version=$neonVersion\nbuild=$buildNumber\n")
    }
}
sourceSets["main"].resources.srcDir(versionResource)

// ---------------------------------------------------------------------------------------------
// Windows packaging: jpackage makes "Neon Auto Clicker.exe" with its own Java inside (nothing
// else to install), then Inno Setup wraps it in an installer. Without Inno Setup, a portable
// .zip is made instead.
// ---------------------------------------------------------------------------------------------

val toolchainHome = javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) }
    .map { it.metadata.installationPath.asFile }

tasks.register("packageWindows") {
    group = "distribution"
    description = "Builds the Windows app (.exe), its installer and a portable zip."
    dependsOn("installDist")
    val isWindows = System.getProperty("os.name").lowercase().startsWith("windows")
    val libDir = layout.buildDirectory.dir("install/NeonAutoClicker/lib")
    val imageDir = layout.buildDirectory.dir("jpackage")
    val distDir = layout.buildDirectory.dir("dist")
    val pkgDir = layout.projectDirectory.dir("packaging")
    val jarName = tasks.jar.flatMap { it.archiveFileName }
    val jdk = toolchainHome

    doLast {
        if (!isWindows) throw GradleException("packageWindows has to run on Windows (jpackage can't build a Windows app elsewhere).")
        fun run(vararg cmd: String) {
            println("> " + cmd.joinToString(" "))
            val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
            p.outputStream.close()
            p.inputStream.bufferedReader().forEachLine { println(it) }
            if (p.waitFor() != 0) throw GradleException("Failed: ${cmd.first()}")
        }
        val appName = "Neon Auto Clicker"
        val image = imageDir.get().asFile
        image.deleteRecursively()
        val dist = distDir.get().asFile
        dist.mkdirs()

        // 1. The app folder with "Neon Auto Clicker.exe" (version, icon and description in its file properties)
        run(
            File(jdk.get(), "bin/jpackage.exe").absolutePath,
            "--type", "app-image",
            "--name", appName,
            "--app-version", neonVersion,
            "--vendor", "Neon Auto Clicker",
            "--description", "Neon Auto Clicker $neonVersion (build $buildNumber)",
            "--copyright", "Neon Auto Clicker",
            "--icon", pkgDir.file("neon.ico").asFile.absolutePath,
            "--input", libDir.get().asFile.absolutePath,
            "--main-jar", jarName.get(),
            "--main-class", "com.neon.autoclicker.desktop.MainKt",
            "--add-modules", "java.base,java.desktop,java.logging,jdk.unsupported",
            "--java-options", "-Xmx256m",
            "--java-options", "-XX:+UseSerialGC",
            "--java-options", "-Dsun.java2d.d3d=true",
            "--dest", image.absolutePath,
        )
        val appFolder = File(image, appName)

        // 2. Portable zip (always): unzip anywhere and run "Neon Auto Clicker.exe"
        val zip = File(dist, "NeonAutoClicker-$neonVersion-Windows-portable.zip")
        zip.delete()
        ant.invokeMethod("zip", mapOf("destfile" to zip.absolutePath, "basedir" to image.absolutePath))
        println("Portable app: ${zip.absolutePath}")

        // 3. Installer with Inno Setup 7 or 6 (free: https://jrsoftware.org/isdl.php), if installed
        // Inno Setup 7 or 6, wherever the standard installer put it (or ISCC=<path to ISCC.exe>)
        val roots = listOfNotNull(System.getenv("ProgramFiles"), System.getenv("ProgramFiles(x86)"),
            System.getenv("LOCALAPPDATA")?.let { "$it\\Programs" })
        val iscc = (listOfNotNull(System.getenv("ISCC")) +
            listOf("Inno Setup 7", "Inno Setup 6").flatMap { v -> roots.map { "$it\\$v\\ISCC.exe" } })
            .map { File(it) }.firstOrNull { it.isFile }
        if (iscc != null) {
            run(
                iscc.absolutePath,
                "/DAppVersion=$neonVersion",
                "/DBuildNumber=$buildNumber",
                "/DSourceDir=${appFolder.absolutePath}",
                "/DOutputDir=${dist.absolutePath}",
                "/DIconFile=${pkgDir.file("neon.ico").asFile.absolutePath}",
                pkgDir.file("NeonAutoClicker.iss").asFile.absolutePath,
            )
            println("Installer: ${File(dist, "NeonAutoClicker-$neonVersion-Setup.exe").absolutePath}")
        } else {
            println("Inno Setup not found, so no installer was made (the portable zip works without installing).")
            println("To get the installer: install Inno Setup 7 (or 6) from https://jrsoftware.org/isdl.php and build again.")
        }
    }
}
