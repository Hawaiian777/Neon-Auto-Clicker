# Neon Auto Clicker (Android + Windows)

One project, two apps with the same look and features. The timer, settings, labels, colors
and Airbus warning-light timing live in `core` and are shared by both, so they always match.

| Folder    | What it is                                   |
|-----------|----------------------------------------------|
| `app`     | Android app                                  |
| `desktop` | Windows app                                  |
| `core`    | Shared logic used by both (plus its tests)   |

The version number for both apps is set in one place: `neon.versionName` in `gradle.properties`.

## Windows: build the installer
1. Double-click **Build Windows App.bat** in this folder.
   - It uses Android Studio's built-in Java. Without Android Studio, install Java 21 from adoptium.net first.
   - For an installer `.exe`, install **Inno Setup 7** (free, jrsoftware.org/isdl.php; version 6 also works) before building.
     Without it you still get a portable `.zip`.
2. The first build downloads what it needs (a few minutes). When it's done, the `desktop\build\dist`
   folder opens with:
   - `NeonAutoClicker-<version>-Setup.exe`: run it to install (Start menu entry, optional desktop icon, uninstaller).
   - `NeonAutoClicker-<version>-Windows-portable.zip`: unzip anywhere and run `Neon Auto Clicker.exe`.
3. Windows may show "Windows protected your PC" the first time, because the app isn't signed by a
   publisher. Click **More info**, then **Run anyway**.

Settings are saved in `%APPDATA%\NeonAutoClicker` and kept when you update or reinstall.

## Android: install on the phone
1. Install Android Studio (developer.android.com/studio).
2. Unzip this folder, then in Android Studio choose **Open** and pick the `NeonAutoClicker` folder.
3. Wait for "Gradle sync" to finish. If it offers to upgrade the Android Gradle Plugin, accept.
4. On your phone: Settings > About phone > tap **Build number** 7 times, then Settings > Developer options > turn on **USB debugging**.
5. Plug the phone in, allow the prompt on the phone, pick it in the device menu at the top, and press the green **Run** button.
6. In the app, tap **Open Accessibility settings** and turn on Neon Auto Clicker.

### Updating
After changing the code, just press the green Run button again with the phone plugged in.
Your settings (colors, sizes, crosshair spot) are kept.

### Optional: let the app turn its own permission on
Android won't let an app flip its own Accessibility switch, unless you grant it once from a PC.
1. Xiaomi only: Developer options > turn on "USB debugging (Security settings)".
2. Plug in the phone, open Command Prompt on the PC and run:
   "%LOCALAPPDATA%\Android\Sdk\platform-tools\adb" shell pm grant com.neon.autoclicker android.permission.WRITE_SECURE_SETTINGS
3. Open the app. The permission button now turns it on with one tap, and the app re-enables it by itself if the phone switches it off.

## Keeping both apps in sync
- Shared behavior (timer/session rules, defaults, limits, presets, labels, colors, light timing,
  profile format) goes in `core`. Change it once and both apps follow.
- Screens are built the same way on both: `MainActivity` ↔ `desktop/MainWindow.kt`,
  `ClickerService` ↔ `desktop/FloatingControls.kt`, `IconView`/`TargetView`/`LiveLights`/`PermBackground` ↔ `desktop/Gfx.kt`.
- Both apps store the same setting names with the same meaning.

## Tests
Shared tests (timer, formatting, colors, light timing, profiles) are in `core/src/test`.
In Android Studio, right-click that folder and choose **Run 'Tests in ...'**, or run `gradlew :core:test`.
