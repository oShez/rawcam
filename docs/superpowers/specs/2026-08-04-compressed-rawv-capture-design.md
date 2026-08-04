# Compressed `.rawv` Capture — Design Spec

**Date:** 2026-08-04
**Status:** Implemented, but FAILS on-device throughput verification (2026-08-05) —
real-time capture at this project's usual 4096x3072@24fps class drops ~91% of
frames with compression on; a same-session compression-off control recording
at identical settings dropped 0. See
`docs/superpowers/open-items-2026-08-04-compressed-rawv-capture.md` for full
findings. Not ready to ship; needs a codec performance pass
(`rawv_codec.cpp`'s per-bit `BitWriter` and two-pass full-frame scan) before
this constraint (stated below) can be met.
**Feature:** Lossless compression of the `.rawv` capture container's per-frame RAW
payload, cutting on-device recording storage use by a scene-dependent amount
(ballpark ~20-50%, matching the general class of technique used by lossless-
compressed RAW formats industry-wide) with zero pixel data loss.

## Goal

Long recordings at this project's usual resolutions/frame rates consume disk
space fast. Add real, lossless compression to the `.rawv` container so a shoot
fits more/longer takes in the same free space — without a second on-device
copy pass, without touching the native capture callback's real-time budget
enough to drop frames, and without any change to exported DNGs' correctness
or compatibility.

This was originally scoped as "MCRAW-style" capture in an earlier session,
but that name is dropped entirely: MotionCam Pro's actual compressed-write
code isn't available under any license compatible with this project's private-
source distribution model (the only open capture-pipeline code from that
project, `f0enix/motioncam`, is GPL-3.0 — copyleft that would force releasing
RawCam's own source, which conflicts with the established choice to keep it
private). This design is an independent implementation using public, patent-
expired, decades-established techniques (linear prediction + Golomb-Rice
residual coding — the same general family FLAC/JPEG-LS/lossless-JPEG use),
not a port or derivative of anyone else's code.

## Global constraints

- No copy step, no native/JNI-boundary change beyond `core/` and
  `app/src/main/cpp/` themselves (same posture as every prior native-pipeline
  change in this project).
- Must not become the bottleneck: capture is currently storage-bandwidth-
  bound (~2.1GB/s sustained, per the 2026-07-21 export-perf work); the
  predictor+entropy-coder's added CPU cost per frame must not push recording
  into dropped frames at this project's usual 4096x3072@24fps class of
  recording. On-device throughput verification is required before this is
  considered done.
- Lossless only. No quantization, no rounding, no perceptual tuning — decoded
  output must be bit-exact against the original sensor samples. This is a
  hard requirement, not a target: the entire point is "no sacrifice," and a
  round-trip fidelity test enforces it (see Testing).
- **Scope cut, made explicitly during design:** this spec covers `.rawv`
  capture-side compression ONLY. `dng_writer.cpp`'s `writeDng()` and the DNG
  export path are UNTOUCHED — exported DNGs remain exactly what they are
  today (plain, uncompressed, Resolve-verified). Sharing compressed bytes
  directly between `.rawv` and a DNG's own pixel strip would require this
  project's compressor to be byte-compatible with one of the DNG
  specification's own registered compression schemes (Compression tag 7,
  real lossless-JPEG bitstream syntax — SOI/SOF3/DHT/SOS markers, standard
  JPEG Huffman table encoding) — a much larger TIFF/JPEG-spec-compliance
  effort than a compressor for our own private container, for a benefit
  (skipping one decode-then-plain-write pass at export time, which is cheap
  since export already reads full frames into memory) that doesn't justify
  the risk to the just-shipped, device-verified export/USB-transfer path.
  Compressed DNG export is deferred to its own future spec, and should use
  the simpler DNG Compression tag 8 (Deflate) route rather than lossless-
  JPEG bitstream compliance.
- No unit tests for anything outside `core/` (same established convention);
  the new codec itself is pure `core/` C++ and gets full host `ctest`
  coverage, same as `pack10`/`pack12`.

## Architecture

`rawv.h`'s `PackMode` enum gains a fourth value, `CompressedPredictive`,
which supersedes today's Packed10/Packed12/Raw16 *selection* in
`capture.cpp` when a new Settings toggle is on. Packed10/Packed12/Raw16
themselves are untouched and remain the exact fallback path — both when the
toggle is off, and per-frame, when compression fails or would not actually
shrink the frame (see Error handling).

**Per-frame encode** (new `core/src/rawv_codec.cpp` /
`core/include/rawcam/rawv_codec.h`), operating on the same per-pixel integer
samples Packed10/Packed12 already work with (at the sensor's real bit depth,
already known from `whiteLevel` exactly as today's pack-mode selection uses
it):

1. **Predict.** For each CFA color plane independently (same-color samples
   are far more correlated with each other than with diagonally-adjacent
   different-color samples), predict each sample from its same-color
   left/above neighbors (a MED/Paeth-style predictor — the well-established,
   patent-expired technique used by PNG, JPEG-LS, and FLAC's LPC stage).
   Emit the signed residual (actual − predicted).
2. **Entropy-code the residuals.** Golomb-Rice coding, not full Huffman —
   dramatically simpler to implement correctly (no canonical-table
   construction/transmission), fast enough for a real-time capture callback,
   and a very well-established fit for exactly this signal shape (small
   residuals clustered near zero after a good linear predictor — the same
   reason FLAC uses Rice coding for its own LPC residuals). The Rice
   parameter is chosen once per frame per plane from the residuals'
   magnitude (a cheap single pass), not adaptively per-sample — keeps the
   encoder simple and the decoder's per-sample cost minimal.

**Per-frame decode** is the exact inverse, used by the export path
(`exporter.cpp`) to reconstruct plain RAW16 before handing off to the
untouched `dng_writer.cpp`, and by any future `.rawv`-repair/recovery
tooling.

## Components

- **`rawv.h`**: `kVersion` 3 → 4 (semantic change, same precedent as the
  1→3 dual-illuminant bump). `PackMode` gains `CompressedPredictive = 3`.
  `FrameMeta` gains two fields carved from the existing `reserved[12]`
  (struct size stays the fixed 64 bytes / `kFrameMetaSize`, no header-size
  change): `uint32_t payloadBytes` (actual on-disk bytes for *this* frame's
  payload) and `uint32_t compressed` (0 = stored/fallback, 1 = compressed);
  `reserved` shrinks to `reserved[4]`. `FileHeader.frameSizeBytes` keeps its
  current exact-stride meaning for Packed10/Packed12/Raw16; for
  `CompressedPredictive` it becomes an allocation *ceiling* (sized to what
  Raw16 would have needed for this resolution — the guaranteed-safe worst
  case), not an exact per-frame stride.
- **New `core/src/rawv_codec.cpp` / `rawv_codec.h`**: `encodeFrame()` /
  `decodeFrame()` as described above. Pure functions over in-memory buffers,
  no file I/O — same shape as `pack10.cpp`/`pack12.cpp`, so it slots into
  `core/`'s existing test/build pattern directly.
- **`rawv_writer.h`/`.cpp`**: `RawvWriter::writeFrame()` signature gains an
  explicit `uint32_t payloadBytes` parameter — payload length can no longer
  be assumed to equal `hdr_.frameSizeBytes` once variable-length frames
  exist. Existing Packed10/Packed12/Raw16 call sites (`capture.cpp:140,142`)
  pass their already-known fixed size unchanged — zero behavior change for
  those paths.
- **`rawv_reader.h`/`.cpp`**: the existing `frameCount == 0` recovery path
  ("recover by scan", per `rawv.h`'s own header comment) currently assumes
  constant record stride. This becomes record-by-record walking using each
  frame's stored `payloadBytes` instead of dividing remaining bytes by a
  constant — **this is the highest-risk, most-invasive part of this change**
  and gets dedicated test coverage of its own (truncated/corrupt files mixing
  compressed and stored-fallback frames).
- **`app/src/main/cpp/capture.cpp`**: when the new Settings toggle is on,
  picks `CompressedPredictive` instead of today's Packed10/Packed12/Raw16
  selection; calls `rawv_codec::encodeFrame()` into a ceiling-sized scratch
  buffer per frame (mirroring the existing `packBuf_` reuse pattern), sets
  `FrameMeta.payloadBytes`/`compressed` accordingly, and falls back to
  today's exact Packed10/Packed12/Raw16 logic on any encode exception or
  Settings-off.
- **`core/src/exporter.cpp`**: gains a `CompressedPredictive` branch calling
  `rawv_codec::decodeFrame()` to reconstruct RAW16, alongside the existing
  Packed10/Packed12 unpack calls — feeds the exact same RAW16 buffer into
  the untouched `dng_writer.cpp`.
- **Settings**: new `Settings.compressRecordings` toggle (default ON),
  Clips/Export section, same DataStore-backed pattern as every other
  capture-behavior setting in this project.

## Data flow

Record start → `capture.cpp` reads `Settings.compressRecordings` → picks
`CompressedPredictive` (or today's Packed10/Packed12/Raw16 if off) → per
frame, `rawv_codec::encodeFrame()` → `RawvWriter::writeFrame(meta, payload,
payloadBytes)` → `.rawv` on disk, smaller for compressed frames, byte-
identical to today for stored/fallback ones. Export → `exporter.cpp` reads
each frame's `FrameMeta.compressed` flag → decodes if needed → hands RAW16 to
the unchanged `dng_writer.cpp` → DNG output is pixel-identical to what
today's uncompressed path would have produced, always.

## Error handling

- **Per-frame fallback, not a whole-recording failure.** If
  `encodeFrame()`'s output would be `>=` the ceiling size (pathological/
  adversarial-noise content, or any encode exception), that ONE frame is
  written stored/uncompressed (`compressed = 0`, `payloadBytes` = the
  Packed10/Packed12/Raw16-equivalent size) instead — mirrors this project's
  established "never fail the whole operation for one bad unit" convention
  (e.g. `ExportService`'s per-clip cancel, not global).
  This also matches how real DNG writers handle strips that don't compress:
  per-unit stored-vs-compressed, never a hard failure.
- **Settings-off / feature disabled:** capture behaves exactly as it does
  today, byte-for-byte — `CompressedPredictive` is never selected.
  Graceful-degradation posture matches every other optional feature in this
  project.
- **Corrupt/truncated `.rawv` recovery:** the reader's scan-recovery path
  must stop cleanly at the first record whose stored `payloadBytes` would
  read past EOF, exactly mirroring today's truncation handling for fixed-
  stride files, just walking variable strides instead of dividing.
- **Version mismatch:** a `kVersion == 3` file (pre-this-change) is read
  exactly as before — this change only affects newly-written `kVersion == 4`
  files; no migration of existing recordings is needed or attempted.

## Testing

Host `ctest`, mirroring `test_pack10.cpp`/`test_pack12.cpp`'s existing
pattern:

- Round-trip fidelity: `encodeFrame()` then `decodeFrame()` reproduces the
  exact original sample buffer, across synthetic content spanning realistic
  sensor noise, flat/smooth gradients, high-detail/high-frequency content,
  and adversarial near-random noise (to exercise the stored-fallback path)
  at each supported bit depth (10/12/16-bit, matching today's
  Packed10/Packed12/Raw16 coverage).
- `rawv_writer`/`rawv_reader` round-trip with mixed compressed and stored-
  fallback frames in one file, including the corrupt/truncated-file
  scan-recovery path with variable strides.
- `test_rawv_layout.cpp`: `FrameMeta` stays exactly 64 bytes
  (`kFrameMetaSize`) after adding `payloadBytes`/`compressed`.

On-device (required before this is considered done, per this project's
established convention — no unit test substitutes for real hardware here):

- [ ] Confirm no dropped frames at this project's usual recording resolution/
  fps with compression on, vs. an identical take with it off.
- [ ] Measure the REAL compression ratio on actual footage from this
  device's sensor at a couple of representative ISOs — report the real
  number achieved, not the ~20-50% ballpark cited above.
- [ ] Export a compressed recording; confirm the resulting DNGs are
  pixel-identical to exporting an equivalent uncompressed recording (diff
  the raw pixel data, not just visual inspection).
- [ ] Force the stored-fallback path (e.g. synthetic max-entropy test
  content) and confirm it round-trips correctly and exports correctly.
- [ ] Confirm a `kVersion == 3` file recorded before this change still opens
  and exports correctly (no forced migration).

## Out of scope

- Compressed DNG export (writing DNG's own Compression tag directly) —
  deferred to a future spec; recommended approach there is the simpler
  Deflate/zlib route (DNG Compression tag 8) rather than lossless-JPEG
  bitstream compliance.
- Any MCRAW file-format compatibility or interop with the MotionCam
  ecosystem's own tooling (MCRAW_Studio, motioncam-fs, etc.) — not a goal;
  this is an independent format understood only by RawCam itself, same as
  `.rawv` already is today.
- Adaptive/per-sample entropy coding, arithmetic coding, or any encoder
  more sophisticated than the single-pass-Rice-parameter design above —
  real compression-ratio numbers from on-device testing should drive whether
  a more sophisticated (and slower) encoder is ever worth pursuing later.
- Migrating or re-compressing existing `kVersion == 3` recordings.
