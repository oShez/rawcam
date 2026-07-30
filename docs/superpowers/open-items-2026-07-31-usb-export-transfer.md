# USB-Visible Export Folder — open items after device verification

Date: 2026-07-31. Plan: `docs/superpowers/plans/2026-07-31-usb-export-transfer.md`.
Spec: `docs/superpowers/specs/2026-07-31-usb-export-transfer-design.md`.

## What's done

All 4 code tasks from the plan are committed and individually reviewed clean:

| Commit | Task | What it does |
|---|---|---|
| `b4d5b9b` | 1 | `ExportPaths` helper (`hasAllFilesAccess`/`exportsRootDir`/`requestAllFilesAccess`) + `MANAGE_EXTERNAL_STORAGE` permission |
| `ba0f450` | 2 | `ClipsScreen`/`ExportsScreen`/`RecordScreen` routed through `ExportPaths`; `downloads-rawcam` FileProvider path added |
| `7fe2893` | 3 | `ExportService` triggers `MediaScannerConnection.scanFile()` on success when the public root was used |
| `89cef29` | 4 | "Allow file access" row in Settings' Device section, live-refreshing via `ON_RESUME` |

## On-device verification — 2026-07-31 (Xiaomi 14 Ultra, model `24030PN60G`)

- [x] **Permission grant + live row update.** Granted "All files access" from the new
  Settings row; the system grant screen opened correctly, and on return the row's
  subtitle flipped to "Granted — exports show up over USB in Windows/Mac file
  browsers" immediately, with no relaunch needed.
- [x] **Export lands in the public root.** Recorded and exported an 86-frame
  (2.02 GB) test clip; confirmed via `adb shell` that the DNGs landed in
  `/storage/emulated/0/Download/RawCam/clip_20260731_045522/`, not the old
  private path.
- [x] **MediaStore indexing.** Logcat showed all 86 DNGs individually scanned via
  `MediaScannerConnection`, each returned a `content://media/external_primary/file/...`
  URI.
- [x] **`.nomedia` / Gallery-exclusion inference confirmed.** This was the one
  inference in the design not independently confirmed from documentation. Queried
  the exact `MediaStore` row for one exported DNG directly: `media_type=0`
  (`MEDIA_TYPE_NONE`) despite its recognized `image/x-adobe-dng` mime type —
  confirming the file is excluded from the Images/Gallery collection while still
  present in the generic Files collection. Cross-checked visually in MIUI
  Gallery/Photos: none of the exported frames appeared there.
- [x] **USB/MTP visibility, from the actual laptop.** Switched the device to MTP
  mode (`svc usb setFunctions mtp`); Windows then listed the phone under "This PC"
  and `Download\RawCam\clip_20260731_045522\` was browsable with all 86 DNGs
  visible via the Shell COM API (the same namespace Explorer itself uses).
  Timed a real copy of the whole folder to the laptop: 2.02 GB in 71.9 s
  (~28.7 MB/s) over the wired USB/MTP path.
- [x] **`ExportsScreen` end-to-end.** Listed the export correctly ("86 DNGs ·
  2.02 GB"); "Send" opened the system share sheet with all 86 files resolved via
  the new `downloads-rawcam` FileProvider path (confirmed via logcat, no
  `IllegalArgumentException`, no crash) — Quick Share, Xiaomi Share, WhatsApp,
  etc. all listed as targets.
- [x] **Private fallback.** Revoked `MANAGE_EXTERNAL_STORAGE` (`appops set --uid
  <uid> MANAGE_EXTERNAL_STORAGE deny` — the package-scoped form of this command
  does not actually take effect for this special op on this device/OS version;
  the UID-scoped form does). Settings row correctly reverted to the ungranted
  subtitle. Re-exported the same clip: it landed under the old private
  `Android/data/com.shez.rawcam/files/exports/clip_20260731_045522/` path with no
  crash, no `MediaScannerConnection` calls fired (correctly skipped per the
  `hasAllFilesAccess` guard), and `ExportsScreen` still listed it correctly from
  the new location. Restored the permission afterward.
- [x] **No crashes.** `adb logcat -b crash -d` was empty for the entire session,
  across both the granted and revoked permission states.

### Not verified this session

- [ ] **Large-scale export (multi-GB, thousands of frames).** Only one clip was
  available in this session: 86 frames / 2.02 GB. This exercised the
  `MediaScannerConnection.scanFile()` call correctly and produced no scanner
  errors or slowdown, but the spec's own testing checklist specifically called
  out watching for scanner issues on a "real clip-sized batch" — this session's
  clip is a reasonable but not extreme-case test. Worth a quick logcat watch the
  next time a genuinely large (multi-thousand-frame) clip is exported and
  scanned, since that is the scenario the fire-and-forget `scanFile()` call is
  actually meant to hold up under.

## Conclusion

The feature is implemented, code-reviewed clean across all 4 tasks (no fix
rounds needed on any of them), and device-verified for every checklist item
except the large-scale export case, which simply had no large clip available to
test with this session — not a known failure. This is a "shipped, one narrow
follow-up identified" close, matching this project's established convention
for features with a residual untested edge case.
