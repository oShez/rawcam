# RawCam v1 validation record

Date: 2026-07-15 (validation run; reference clip recorded 2026-07-13)
Device: Google Pixel (29171FDH300E2E), Android 16, wireless adb

## Step 1 — Reference clip

`clip_20260713_145115.rawv` — 4080x3072 (full RAW sensor), 24 fps, shutter 1/48s, ISO 388, focus ∞, Packed10.
Recorded 2026-07-13 in a well-lit interior; summary Snackbar: **330 frames, 0 dropped** (~13.75 s).

## Step 2 — Frame accounting

- written + dropped = 330 + 0 = 330 ≈ 13.75 s × 24 fps ✓ (recording stopped manually just before 14 s)
- File size 5,170,197,632 bytes = 512 + 330 × (64 + 15,667,200) — **byte-exact** for 330 whole records
- `nativeClipInfo` frameCount = 330, matches the Snackbar count ✓
- Sustained write ≈ 376 MB/s with 0 drops (above the Task 8 clean-run floor of 355.7 MB/s)
- Drop counts across 3 takes (Tasks 11–12 test recordings): 330/0, 93/0, 92/0 dropped

## Step 3 — Export and pull

- On-device export via ExportService: 330/330 DNGs in `exports/clip_20260713_145115/` (7.7 GiB, ~23.9 MB each ≈ 4080×3072×2 + header — plausible uncompressed RAW16)
- Pulled to `C:\Users\User\rawcam\testfootage\clip_20260713_145115\` over wireless adb (2026-07-15):
  330 files, 8,272,436,700 bytes = 330 × 25,067,990 — all byte-exact (25,067,990 = 470B header + 4080×3072×2 strip).
  First and last frames decode via rawpy; per-frame AsShotNeutral differs between frame 0 (0.507/1.0/0.512)
  and frame 329 (0.503/1.0/0.509) — per-frame WB metadata pipeline confirmed end-to-end.

## Step 4 — Desktop validation

- IFD tag audit (frame 000000, parsed directly): 26 entries, all sane — 4080×4080/3072 uncompressed 16-bit CFA strip
  (StripByteCounts 25,067,520 = 4080×3072×2), DNGVersion 1.4, CFAPattern GBRG matching the sensor,
  BlackLevel [64,64,64,64], WhiteLevel 1023, ColorMatrix1 9 rationals, AsShotNeutral (0.507, 1.0, 0.512),
  CalibrationIlluminant1 = 21 (D65), Make/Model/UniqueCameraModel populated.
- rawpy (LibRaw 0.27) decode: opens, reports 3072×4080 uint16, white level 1023, camera WB (1.973, 1.0, 1.953),
  debayers to a recognizable image. LibRaw did not apply the LONG-typed BlackLevel tag (rendered lifted/purple);
  a manual render subtracting black 64 + AsShotNeutral WB produces a clean, color-correct image — the DNG data
  and tags are self-consistent. Contingency if Resolve also shows lifted blacks: write BlackLevel as RATIONAL.
- **Defect found and fixed (commit 08a08fb):** Resolve showed "media offline" for the whole sequence. Root cause:
  the DNG writer emitted a 4-entry BlackLevel without BlackLevelRepeatDim (50713); the spec default RepeatDim [1,1]
  makes count=4 malformed. Confirmed by A/B test: single-frame variants patched with RepeatDim [2,2] loaded in
  Resolve, a FrameRate-only control variant stayed offline; LibRaw also honors the black level only with the tag
  present. Fix: writer now emits BlackLevelRepeatDim [2,2] (host test asserts it, 6/6 ctest); on-device re-export
  verified (frame 000000 pulled: tag present, +12 bytes, LibRaw black [64,64,64,64]). The 330 pulled DNGs were
  patched locally with the identical change for the Resolve gate.
- DaVinci Resolve (user-confirmed 2026-07-15, screenshot evidence): sequence imports as a single CinemaDNG clip
  [000000-000329], Camera Raw panel active in CinemaDNG mode (decode quality/WB/gamma controls live),
  White Balance "As shot" = 3504K / tint 65.2 from our AsShotNeutral, timeline duration 13:18 @ 24 fps
  (= 330 frames), clean debayer with correct colors and proper blacks, grade nodes (corrector + sizing) respond
  sanely. **User verdict: working fine.**

## Step 5/6 — Result

**V1 BAR MET.**

| Item | Result |
|---|---|
| Device | Google Pixel 7 Pro (29171FDH300E2E) |
| Benchmark (clean run) | 450.5 / 466.2 / 355.7 MB/s sequential write |
| Pack mode | Packed10 (de-strided, frameSizeBytes = w*h/4*5) |
| Recording | 4080x3072 @ 24 fps, manual ISO 388 / shutter 1/48 / focus ∞, AE off |
| Sustained write | ~376 MB/s, 0 drops |
| Drop counts (3 takes) | 330/0, 93/0, 92/0 |
| Container | byte-exact records, crash-recovery scan verified (Task 11 HOME-mid-recording test) |
| Export | 330/330 CinemaDNG on device; desktop IFD audit + LibRaw decode clean |
| Resolve | debayers, plays at 24 fps, grades — user confirmed |

Defect found & fixed during validation: missing BlackLevelRepeatDim tag (commit 08a08fb) — see Step 4.
