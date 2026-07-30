# USB-Visible Export Folder — Design Spec

**Date:** 2026-07-31
**Status:** Implemented, 1 open item (device-verified 2026-07-31)
**Feature:** Moves exported DNG folders from the app-private, USB-invisible
`Android/data/.../files/exports` directory to a public, `MediaStore`-indexed
location (`Download/RawCam/<clip>/`), so a USB cable + Windows Explorer can
browse and drag-copy them directly — no Quick Share / Wi-Fi Direct required.

## Goal

Exported clips are tens of gigabytes of DNGs. Today the only way off the
phone is the "Send" share-sheet button, which routes through Quick Share
over Wi-Fi Direct — slow at this file size, and the same for any other
share-sheet target. The user has a USB cable available but plugging it in
today shows nothing useful in Windows Explorer, because exports live under
`Android/data/`, a location scoped storage never lets the system's MTP
responder index. Fix this without adding any new networking, without a
second on-device copy pass (files are already large enough), and without
touching the native capture/export pipeline.

## Global constraints

- No copy step. The native exporter must write directly to the final,
  USB-visible location — never write to one place and duplicate into
  another. The user explicitly rejected a copy-based approach given file
  sizes involved.
- No native/JNI changes. `NativeBridge.nativeExportClip` already takes a
  plain output directory path and writes DNGs into it; this feature only
  changes *which path* Kotlin passes in, never the native writer itself.
- Graceful degradation, matching this project's established convention: if
  the permission this feature depends on isn't granted, export still works
  exactly as it does today (private, share-sheet-only) rather than failing.
- Exported RAW frames must not flood the device's own Gallery/Photos app.
  Thousands of per-frame DNGs becoming "photos" on the device would be a
  regression in its own right.
- Scope: exported DNG folders only. The `.rawv` clip files (`ClipsScreen`)
  are untouched — the user transfers exported DNGs, not raw clips, off the
  device.

## Architecture

USB/MTP visibility on Android 10+ is driven entirely by `MediaStore`
indexing, not raw filesystem location — a file has to be scanned into
`MediaStore` before Windows' MTP-based Explorer view can see it, regardless
of which directory it physically lives in. Two separate problems therefore
need solving:

1. **Where the files are writable at all.** `Android/data/<pkg>/files/...`
   is scoped-storage-private; other public directories (e.g. `Download/`)
   require either routing every write through `ContentResolver`/`MediaStore`
   Uris, or holding the `MANAGE_EXTERNAL_STORAGE` ("All files access")
   permission, which bypasses the scoped-storage FUSE layer and gives the
   app direct, real-path file I/O — the same performance profile the native
   writer already relies on, just pointed at a public path. Since RawCam is
   sideloaded (not Play-distributed), this permission carries no store-policy
   concern.
2. **Getting those files indexed.** Writing to a public path directly
   (bypassing FUSE) does not by itself insert `MediaStore` rows. A
   `MediaScannerConnection.scanFile()` call after a completed export is what
   makes the new files appear promptly over MTP, instead of waiting on
   whatever the system's periodic scan schedule happens to be.

A `.nomedia` marker at the `Download/RawCam/` root suppresses this content
from being surfaced through the *media* collections (Images/Video/Audio) —
which is what keeps it out of Gallery/Photos — while the generic `Files`
collection entry `MediaScannerConnection` creates (which is what MTP reads
from) is unaffected. This is the same mechanism apps like WhatsApp use to
keep received media out of Gallery while leaving it fully visible to file
managers and MTP.

If `MANAGE_EXTERNAL_STORAGE` is not granted, export falls back to today's
private `getExternalFilesDir(null)/exports/<clip>` location unchanged — the
feature is additive, not a hard requirement for export to function.

## Components

- **`AndroidManifest.xml`** — add
  `<uses-permission android:name="android.permission.MANAGE_EXTERNAL_STORAGE" />`.
- **New `com.shez.rawcam.export.ExportPaths.kt`** (consolidates the two
  existing, drifting copies of this logic in `ClipsScreen.kt` and
  `ExportsScreen.kt` into one place, since both need to change identically
  here anyway):
  - `hasAllFilesAccess(context): Boolean` — wraps
    `Environment.isExternalStorageManager()`.
  - `exportsRootDir(context): File` — returns
    `Environment.getExternalStoragePublicDirectory(DIRECTORY_DOWNLOADS)/RawCam`
    when `hasAllFilesAccess()` is true, otherwise the existing
    `getExternalFilesDir(null)/exports` private path. Ensures the returned
    directory exists and, for the public case, that a `Download/RawCam/.nomedia`
    file exists (created once, idempotent check on every call).
  - `requestAllFilesAccess(context)` — starts
    `Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION` (per-app grant
    screen, scoped to this app via a `package:` URI), falling back to the
    unscoped `Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION` if the
    per-app intent doesn't resolve on a given OEM skin.
- **`ClipsScreen.kt` / `RecordScreen.kt`** — the two `outDir` construction
  sites (`ClipsScreen.kt:232`, `RecordScreen.kt:992`) switch from
  `File(context.getExternalFilesDir(null), "exports/$baseName")` to
  `File(ExportPaths.exportsRootDir(context), baseName)`. No other change to
  either call site — `ExportService.start(...)` still receives a plain
  absolute path exactly as today.
- **`ExportsScreen.kt`** — `exportsDirOf()` becomes a thin call to
  `ExportPaths.exportsRootDir(context)`; listing/share/delete logic is
  otherwise unchanged, since it already just operates on whatever `File` it's
  given.
- **`ExportService.kt`** — after a successful, non-cancelled completion
  (the existing `ok -> ExportStatus.DONE` branch), if
  `ExportPaths.hasAllFilesAccess(this)`, list the just-written DNGs in
  `outDir` and pass them to `MediaScannerConnection.scanFile(this, paths,
  null, null)`. Fire-and-forget — does not gate `stopSelf`/notification
  completion, matching this service's existing "notification is best-effort"
  posture.
- **`SettingsScreen.kt`** — new `ActionRow` (existing pattern, see
  `CompatibilityReportScreen`'s row) in the Device section: "Allow file
  access" / "Lets exported clips show up over USB in Windows/Mac file
  browsers" when not yet granted; shows as already-satisfied (no action) once
  `ExportPaths.hasAllFilesAccess()` is true. Tapping it calls
  `ExportPaths.requestAllFilesAccess(context)`.

## Data flow

Export tap (`ClipsScreen`) → `ExportPaths.exportsRootDir()` resolves to
public or private root depending on the permission → `ExportService.start()`
→ native `nativeExportClip` writes DNGs to that path exactly as it does
today → on success, if public, `MediaScannerConnection.scanFile()` indexes
the new files → files appear in `MediaStore`'s generic `Files` collection →
Windows' MTP-based Explorer view (already polling/subscribed to that
collection over the USB connection) shows the folder without further app
involvement. `ExportsScreen` reads from the same resolved root, so it always
lists whatever this export run actually produced.

## Error handling

- Permission not granted at export time: use the private fallback path,
  identical to current behavior — no crash, no blocked export, just no USB
  visibility until the user grants it.
- Permission revoked after being granted (user turns it off later in system
  settings): `exportsRootDir()` re-checks `hasAllFilesAccess()` on every
  call, so the very next export simply falls back — no stale-permission
  state to track.
- `scanFile()` given zero files (empty `outDir`, e.g. export failed before
  producing any frames): call is skipped entirely, since there's nothing to
  index.
- Exports that already exist in the old private location before this change
  ships are left there — `ExportsScreen` only lists the newly-resolved root,
  so old exports simply stop appearing in-app. This is a one-time cutover,
  not a migration; the files aren't deleted, just no longer surfaced by this
  screen. Acceptable since the user's own workflow is export-then-transfer-
  then-delete, not long-term on-device archival.

## Testing

No unit tests: this is OS-permission and file-path plumbing (Kotlin/Android
framework glue, not the native `core` pipeline that already has coverage),
best verified on-device rather than mocked. On-device verification required
before this is considered done, per this project's established convention:

- [ ] Grant "Allow file access" from the new Settings row; confirm
  `Environment.isExternalStorageManager()` reflects it immediately.
- [ ] Export a clip; confirm the DNGs land in
  `/storage/emulated/0/Download/RawCam/<clip>/`, not the old private path.
- [ ] Plug the phone into the Windows laptop over USB; confirm the folder
  and its DNGs appear in File Explorer without manually triggering a scan,
  and confirm a drag-and-drop copy of the whole folder is meaningfully
  faster than the previous Quick Share workflow.
- [ ] Confirm the DNGs do **not** appear in the device's Gallery/Photos app
  (`.nomedia` verification) — this is the one inference in this design not
  independently confirmed from documentation, worth confirming first.
- [ ] Confirm `ExportsScreen` still lists, shares (`Send`), and deletes
  correctly against the new location.
- [ ] Revoke the permission (system Settings), export again, confirm it
  falls back to the private location without crashing, and that
  `ExportsScreen` still shows it.
- [ ] Confirm a large export (multi-GB, thousands of frames) doesn't stall
  or crash the `scanFile()` call — it's fire-and-forget, but worth watching
  logcat for scanner errors on a real clip-sized batch.

## Out of scope (this feature)

- MCRAW-style compressed capture format — a real candidate for shrinking
  transfer size at the source, but a separate, larger native-pipeline change
  the user explicitly deferred to a future spec.
- On-device clip/export preview — raised in the same conversation as a
  follow-up question, not part of this fix; the `.nomedia` marker here
  actually removes the one "free" preview path (Gallery showing individual
  frames), so this is worth designing deliberately later rather than as a
  side effect.
- Wireless/LAN transfer (e.g. an in-app HTTP server) — not needed given a
  USB cable is available; would also be a much larger change and cuts
  against this codebase's existing "no custom networking, share sheet is
  standard and already-hardened" philosophy (see `ExportsScreen.kt`'s
  `shareExport` doc comment).
- Migrating pre-existing exports from the old private location to the new
  public one.
- Any change to `.rawv` capture, the native DNG writer itself, or
  `ClipsScreen`'s handling of raw clips.
