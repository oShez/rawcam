# USB-Visible Export Folder Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make exported DNG folders show up over USB in Windows Explorer by writing them to a public, `MediaStore`-indexed location (`Download/RawCam/<clip>/`) instead of the private `Android/data/.../files/exports` directory, with no duplicate copy step and no change to the native export pipeline.

**Architecture:** A new `ExportPaths` object in `com.shez.rawcam.export` resolves the export root (public when `MANAGE_EXTERNAL_STORAGE` is granted, private fallback otherwise) and is the single place every existing call site reads/writes through. `ExportService` triggers `MediaScannerConnection.scanFile()` after a successful export so the public files appear over MTP promptly. A `.nomedia` marker keeps the DNGs out of Gallery/Photos without hiding them from MTP or file managers.

**Tech Stack:** Kotlin, Jetpack Compose, plain Android SDK (`Environment`, `MediaScannerConnection`, `Settings` intents) — no new Gradle dependencies.

## Global Constraints

- **minSdk 33** (`app/build.gradle.kts:24`, targetSdk/compileSdk 35) — `MANAGE_EXTERNAL_STORAGE` (API 30+) is unconditionally available on every device this app runs on. No `Build.VERSION.SDK_INT` guards anywhere in this feature.
- **No copy step.** The native exporter must write DNGs directly to whichever root `ExportPaths.exportsRootDir()` resolves — never write to one place and duplicate into another.
- **No native/JNI changes.** Only *which path string* gets passed into `NativeBridge.nativeExportClip` / `ExportService.start()` changes — never the native writer itself.
- **Graceful degradation.** If `MANAGE_EXTERNAL_STORAGE` isn't granted, export continues to work exactly as it does today (private path, share-sheet only) — never a hard failure.
- **`.nomedia` marker required** at `Download/RawCam/.nomedia` — thousands of per-frame DNGs must not flood the device's own Gallery/Photos app.
- **No unit tests for this feature.** Confirmed via `app/build.gradle.kts:55` (`testOptions { unitTests.isReturnDefaultValues = true }`) and `testImplementation("junit:junit:4.13.2")` at line 70 — plain JUnit, no Robolectric, so calls into `Environment`/`Settings` return defaults rather than real behavior in a unit test. This matches the spec's own call ("best verified on-device rather than mocked"). Every task below verifies with a Gradle build instead of a unit test; Task 5 is the full on-device verification pass.
- **Scope:** exported DNG folders only. `.rawv` clip files and `ClipsScreen`'s handling of them are untouched.

---

### Task 1: `ExportPaths` helper + manifest permission

**Files:**
- Create: `app/src/main/java/com/shez/rawcam/export/ExportPaths.kt`
- Modify: `app/src/main/AndroidManifest.xml:6` (add permission after the existing `POST_NOTIFICATIONS` line)

**Interfaces:**
- Produces: `ExportPaths.hasAllFilesAccess(context: Context): Boolean`, `ExportPaths.exportsRootDir(context: Context): File`, `ExportPaths.requestAllFilesAccess(context: Context): Unit` — consumed by Tasks 2, 3, 4.

- [ ] **Step 1: Add the manifest permission**

In `app/src/main/AndroidManifest.xml`, after line 6 (`<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />`):

```xml
    <uses-permission android:name="android.permission.MANAGE_EXTERNAL_STORAGE" />
```

- [ ] **Step 2: Create `ExportPaths.kt`**

```kotlin
package com.shez.rawcam.export

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import java.io.File

/**
 * Resolves where exported DNG folders are written. Public (`Download/RawCam`) when
 * this app holds MANAGE_EXTERNAL_STORAGE, so a USB cable + a desktop file browser
 * can see them via MediaStore; falls back to the original private
 * getExternalFilesDir/exports location otherwise, so export never fails outright
 * for lack of this optional permission. Every call site that reads or writes
 * export folders goes through this object instead of building the path itself.
 */
object ExportPaths {

    fun hasAllFilesAccess(context: Context): Boolean = Environment.isExternalStorageManager()

    /** Root directory new exports are written under. Ensures the directory (and,
     * for the public case, a Download/RawCam/.nomedia marker) exist before
     * returning, so callers can immediately mkdirs a clip subfolder under it. */
    fun exportsRootDir(context: Context): File {
        if (hasAllFilesAccess(context)) {
            // Deprecated since API 29, but this is exactly the API this
            // permission model is designed around: MANAGE_EXTERNAL_STORAGE
            // grants direct, real-path access to public storage, and this is
            // the real path to the public Downloads directory.
            @Suppress("DEPRECATION")
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "RawCam",
            )
            dir.mkdirs()
            val nomedia = File(dir, ".nomedia")
            if (!nomedia.exists()) nomedia.createNewFile()
            return dir
        }
        val dir = File(context.getExternalFilesDir(null), "exports")
        dir.mkdirs()
        return dir
    }

    /** Opens the per-app "All files access" grant screen, falling back to the
     * unscoped grant-screen list if the per-app intent doesn't resolve on a
     * given OEM skin. */
    fun requestAllFilesAccess(context: Context) {
        val perApp = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
            .setData(Uri.parse("package:${context.packageName}"))
        if (perApp.resolveActivity(context.packageManager) != null) {
            context.startActivity(perApp)
        } else {
            context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        }
    }
}
```

- [ ] **Step 3: Verify it builds**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL. (No test to run — see Global Constraints — this is a framework-glue file with no meaningfully unit-testable logic without Robolectric.)

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/shez/rawcam/export/ExportPaths.kt app/src/main/AndroidManifest.xml
git commit -m "feat: add ExportPaths helper and MANAGE_EXTERNAL_STORAGE permission"
```

---

### Task 2: Wire export call sites through `ExportPaths`, extend FileProvider paths

**Files:**
- Modify: `app/src/main/java/com/shez/rawcam/ui/ClipsScreen.kt:51,74-75,100,232`
- Modify: `app/src/main/java/com/shez/rawcam/ui/ExportsScreen.kt:44,56`
- Modify: `app/src/main/java/com/shez/rawcam/ui/RecordScreen.kt:118,992`
- Modify: `app/src/main/res/xml/file_paths.xml`

**Interfaces:**
- Consumes: `ExportPaths.exportsRootDir(context: Context): File` (Task 1).
- Produces: every export-folder read/write site (list, export, share) resolves to the same root; `ExportsScreen`'s share sheet can grant a `content://` URI for files under the new public root.

- [ ] **Step 1: `ClipsScreen.kt` — add import, drop the duplicate helper, update both call sites**

Add import right after the existing `ExportService` import (line 51):

```kotlin
import com.shez.rawcam.export.ExportService
import com.shez.rawcam.export.ExportPaths
```

Replace lines 71-76:

```kotlin
private fun clipsDirOf(context: android.content.Context) =
    File(context.getExternalFilesDir(null), "clips")

private fun exportsDirOf(context: android.content.Context) =
    File(context.getExternalFilesDir(null), "exports")

private fun baseName(f: File) = f.name.removeSuffix(".rawv")
```

with:

```kotlin
private fun clipsDirOf(context: android.content.Context) =
    File(context.getExternalFilesDir(null), "clips")

private fun baseName(f: File) = f.name.removeSuffix(".rawv")
```

Line 100, replace:

```kotlin
    val exportsDir = exportsDirOf(context)
```

with:

```kotlin
    val exportsDir = ExportPaths.exportsRootDir(context)
```

Line 232, replace:

```kotlin
                                val outDir = File(exportsDirOf(context), baseName(clip.file))
```

with:

```kotlin
                                val outDir = File(ExportPaths.exportsRootDir(context), baseName(clip.file))
```

- [ ] **Step 2: `ExportsScreen.kt` — add import, delegate the helper**

Add import right after the `SettingsRepository` import (line 44):

```kotlin
import com.shez.rawcam.settings.SettingsRepository
import com.shez.rawcam.export.ExportPaths
```

Replace line 56:

```kotlin
private fun exportsDirOf(context: Context) = File(context.getExternalFilesDir(null), "exports")
```

with:

```kotlin
private fun exportsDirOf(context: Context) = ExportPaths.exportsRootDir(context)
```

- [ ] **Step 3: `RecordScreen.kt` — add import, update the auto-export call site**

Add import right after the `ExportService` import (line 118):

```kotlin
import com.shez.rawcam.export.ExportService
import com.shez.rawcam.export.ExportPaths
```

Replace line 992:

```kotlin
                            val outDir = File(app.getExternalFilesDir(null), "exports/$baseName").absolutePath
```

with:

```kotlin
                            val outDir = File(ExportPaths.exportsRootDir(app), baseName).absolutePath
```

- [ ] **Step 4: `file_paths.xml` — add a public-storage path entry for FileProvider**

The existing `<external-files-path>` entries only cover the app's *private* external storage. `ExportsScreen`'s `shareExport()` calls `FileProvider.getUriForFile()` on whatever `exportsDirOf()` returns, so once that can be the public `Download/RawCam` directory, FileProvider needs a matching `<external-path>` entry (public-storage root, not app-private) or it throws `IllegalArgumentException` at share time.

Insert before the closing `</paths>` tag:

```xml
    <!-- Public export root once MANAGE_EXTERNAL_STORAGE is granted --
         ExportPaths.exportsRootDir() writes DNGs to Download/RawCam/<clip>/ in
         that case, and the share sheet still needs a content:// URI (not a
         raw file:// path) for that public location, same as the private one
         above. -->
    <external-path name="downloads-rawcam" path="Download/RawCam/" />
```

- [ ] **Step 5: Verify it builds**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/shez/rawcam/ui/ClipsScreen.kt app/src/main/java/com/shez/rawcam/ui/ExportsScreen.kt app/src/main/java/com/shez/rawcam/ui/RecordScreen.kt app/src/main/res/xml/file_paths.xml
git commit -m "feat: route export folders through ExportPaths, extend FileProvider paths"
```

---

### Task 3: MediaStore indexing after a successful export

**Files:**
- Modify: `app/src/main/java/com/shez/rawcam/export/ExportService.kt:8-10,138-146`

**Interfaces:**
- Consumes: `ExportPaths.hasAllFilesAccess(context: Context): Boolean` (Task 1) — same package (`com.shez.rawcam.export`), no new import needed for `ExportPaths` itself.
- Produces: newly-written DNGs are indexed into `MediaStore` (visible over MTP) immediately after a successful, non-cancelled export, whenever the public root was used.

- [ ] **Step 1: Add the `MediaScannerConnection` import**

Insert between the existing `android.content.Intent` and `android.os.IBinder` imports (lines 8-9), keeping package-alphabetical order:

```kotlin
import android.content.Intent
import android.media.MediaScannerConnection
import android.os.IBinder
```

- [ ] **Step 2: Scan the exported DNGs on success**

Replace lines 138-142:

```kotlin
            status[clipName] = when {
                ok -> ExportStatus.DONE
                wasCancelled -> ExportStatus.CANCELLED
                else -> ExportStatus.FAILED
            }
```

with:

```kotlin
            status[clipName] = when {
                ok -> ExportStatus.DONE
                wasCancelled -> ExportStatus.CANCELLED
                else -> ExportStatus.FAILED
            }
            // Indexes the just-written DNGs into MediaStore's generic Files
            // collection so they show up over MTP/USB without waiting on the
            // system's periodic scan. Only meaningful when they were written
            // under the public ExportPaths root -- skipped otherwise, since a
            // private-storage path isn't something the scanner can do
            // anything useful with.
            if (ok && ExportPaths.hasAllFilesAccess(this)) {
                val dngPaths = File(outDir).listFiles { f -> f.name.endsWith(".dng") }
                    ?.map { it.absolutePath }?.toTypedArray() ?: emptyArray()
                if (dngPaths.isNotEmpty()) {
                    MediaScannerConnection.scanFile(this, dngPaths, null, null)
                }
            }
```

(This lands directly above the existing `// Delete the source .rawv...` comment and `if (ok && deleteAfter) {` block — leave those untouched.)

- [ ] **Step 3: Verify it builds**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/shez/rawcam/export/ExportService.kt
git commit -m "feat: scan exported DNGs into MediaStore when using the public export root"
```

---

### Task 4: "Allow file access" row in Settings

**Files:**
- Modify: `app/src/main/java/com/shez/rawcam/ui/SettingsScreen.kt:47-59,69-77,294-314`

**Interfaces:**
- Consumes: `ExportPaths.hasAllFilesAccess(context: Context): Boolean`, `ExportPaths.requestAllFilesAccess(context: Context): Unit` (Task 1).
- Produces: a new row in the Settings screen's DEVICE section; no new interface for later tasks to consume.

- [ ] **Step 1: Add imports**

Insert after the existing `androidx.core.content.FileProvider` import (line 47):

```kotlin
import androidx.core.content.FileProvider
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
```

Insert after the existing `com.shez.rawcam.NativeBridge` import (line 49):

```kotlin
import com.shez.rawcam.NativeBridge
import com.shez.rawcam.export.ExportPaths
```

- [ ] **Step 2: Track the permission state, refreshing on resume**

Replace lines 75-77:

```kotlin
    var showResetDialog by remember { mutableStateOf(false) }
    var showReport by remember { mutableStateOf(false) }
    var dumpStatus by remember { mutableStateOf<String?>(null) }
```

with:

```kotlin
    var showResetDialog by remember { mutableStateOf(false) }
    var showReport by remember { mutableStateOf(false) }
    var dumpStatus by remember { mutableStateOf<String?>(null) }
    // The system grant screen ExportPaths.requestAllFilesAccess() opens is a
    // separate Activity on the same task backstack -- this screen resumes
    // (ON_RESUME) when the user navigates back from it, which is the only
    // signal available that the permission may have changed.
    var allFilesAccess by remember { mutableStateOf(ExportPaths.hasAllFilesAccess(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                allFilesAccess = ExportPaths.hasAllFilesAccess(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
```

- [ ] **Step 3: Add the row to the DEVICE section**

Replace lines 300-314 (the `Dump characteristics` `ActionRow` and the section's closing) —

```kotlin
            ActionRow(
                title = "Dump characteristics (JSON)",
                subtitle = dumpStatus ?: "Writes a snapshot fixture and opens the share sheet",
                onClick = {
                    dumpStatus = "Dumping…"
                    viewModel.dumpCharacteristics { result ->
                        result.onSuccess { file ->
                            dumpStatus = "Saved ${file.name}"
                            shareFile(context, file, "application/json")
                        }.onFailure { e ->
                            dumpStatus = "Failed: ${e.message}"
                        }
                    }
                },
            )

            SectionHeader("ABOUT")
```

with:

```kotlin
            ActionRow(
                title = "Dump characteristics (JSON)",
                subtitle = dumpStatus ?: "Writes a snapshot fixture and opens the share sheet",
                onClick = {
                    dumpStatus = "Dumping…"
                    viewModel.dumpCharacteristics { result ->
                        result.onSuccess { file ->
                            dumpStatus = "Saved ${file.name}"
                            shareFile(context, file, "application/json")
                        }.onFailure { e ->
                            dumpStatus = "Failed: ${e.message}"
                        }
                    }
                },
            )
            ActionRow(
                title = "Allow file access",
                subtitle = if (allFilesAccess) {
                    "Granted — exports show up over USB in Windows/Mac file browsers"
                } else {
                    "Lets exported clips show up over USB in Windows/Mac file browsers"
                },
                onClick = { if (!allFilesAccess) ExportPaths.requestAllFilesAccess(context) },
            )

            SectionHeader("ABOUT")
```

- [ ] **Step 4: Verify it builds**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/shez/rawcam/ui/SettingsScreen.kt
git commit -m "feat: add Allow file access row to Settings Device section"
```

---

### Task 5: On-device verification

**Files:** None (verification only — this task ends with a doc update, not a code change).

**Interfaces:** Consumes everything from Tasks 1-4 running together on a real device.

No further code changes in this task. Work through each item below on the actual device, in order (later items depend on earlier ones being granted/exported).

- [ ] **Step 1: Install and grant the permission**

Build and install debug: `./gradlew :app:installDebug`. Open Settings → Device → "Allow file access". Confirm the system "All files access" grant screen opens, grant it, navigate back, and confirm the row's subtitle flips to "Granted — exports show up over USB in Windows/Mac file browsers" immediately (no relaunch needed).

- [ ] **Step 2: Export a clip to the new location**

From Clips, export any existing clip. Once it completes, confirm via `adb shell ls /storage/emulated/0/Download/RawCam/<clip-name>/` that the DNGs landed there — not under the old `Android/data/com.shez.rawcam/files/exports/` path.

- [ ] **Step 3: Confirm USB visibility**

Plug the phone into the Windows laptop over USB. Confirm `Download\RawCam\<clip-name>` and its DNGs appear in File Explorer without manually forcing a rescan. Drag-copy the whole folder and confirm it's meaningfully faster than the old Quick Share workflow.

- [ ] **Step 4: Confirm Gallery/Photos exclusion**

Open the device's Gallery/Photos app. Confirm none of the exported DNGs (or their thumbnails) appear there — this is the one inference in the spec not independently confirmed from documentation before now, so treat any DNGs showing up here as a real bug, not a nitpick.

- [ ] **Step 5: Confirm `ExportsScreen` still works end-to-end**

Open Exports. Confirm the just-exported clip is listed with the correct DNG count/size, "Send" still opens the system share sheet successfully (this exercises the new `downloads-rawcam` FileProvider path from Task 2, Step 4), and "Delete" removes the folder and clears it from the list.

- [ ] **Step 6: Confirm the private fallback still works**

In system Settings, revoke RawCam's "All files access". Export a different clip. Confirm it lands under the old private `Android/data/.../files/exports/<clip>/` path with no crash, and that `ExportsScreen` lists it correctly.

- [ ] **Step 7: Confirm no scanner issues on a large export**

Re-grant "All files access". Export the largest available clip (multi-GB, thousands of frames). While it runs, tail the log: `adb logcat | grep -i "MediaScanner\|ExportService"`. Confirm no scanner errors or crashes, and that the export still completes and shows up over USB per Step 3.

- [ ] **Step 8: Update the spec status and commit**

In `docs/superpowers/specs/2026-07-31-usb-export-transfer-design.md`, change the header's `**Status:** Design` line to `**Status:** Implemented and device-verified <today's date>`. If any step above didn't fully pass, instead write a short `docs/superpowers/open-items-2026-07-31-usb-export-transfer.md` describing exactly what didn't verify (following the format of the existing `docs/superpowers/open-items-2026-07-29-zebra-shadow.md`), and leave the spec's status as `Implemented, <n> open items`.

```bash
git add docs/superpowers/specs/2026-07-31-usb-export-transfer-design.md
git commit -m "docs: mark USB-visible export folder device-verified"
```
