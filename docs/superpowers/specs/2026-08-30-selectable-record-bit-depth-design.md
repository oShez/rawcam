# Selectable record bit depth

**Date:** 2026-08-30
**Status:** Design approved (approach A), not yet implemented.

## Problem

The main camera drops frames when recording compressed RAW. Measured on-device
2026-08-30 (Xiaomi 14 Ultra, 4096x3072 @24fps, compressed, audio on): a 30s take
wrote 8.846 GB in 30.47s wall and dropped **103 of 705 frames (14.6%)**.

The shape of the drops matters more than the number. Screen-captured per-second
counts show **zero drops for the first 8 seconds**, then a ramp to a steady
~5-7 drops/sec:

| elapsed | frames | dropped |
|---------|--------|---------|
| 0:05    | 125    | 0       |
| 0:08    | 183    | 0       |
| 0:10    | 223    | 3       |
| 0:16    | 341    | 27      |
| 0:21    | 436    | 52      |
| 0:30    | 602    | 103     |

The capture queue is only `kQueueCap = 8` frames plus `kFinishQueueCap = 2`
(`capture.h:101,184`), which drains in about 2 seconds at a 5 fps deficit -- far
short of 8. So the encoder genuinely **sustained full 24 fps while cool and then
decayed to ~19 fps**. This is a throttling curve, not a constant algorithmic
shortfall.

That reframes what is worth optimising. Prior rounds established the obvious
levers are exhausted: SIMD on the Rice bit-writer is a dead end (serial
bit-append is 94-98% of `writeRice` cost; a NEON prototype ran 0.73x), more
worker threads is sublinear because the workload is memory-bandwidth-bound, and
NEON on predict targets only ~8% of encode CPU. Round 5 already took the serial
packer ~1.64x.

Against a *thermal* limit, the thing that pays is reducing **bytes per pixel**,
which pays twice: less `writeRice` work (~84% of encode CPU) *and* less sustained
write bandwidth and power, which is what provokes the throttle.

**Caveat, stated up front:** the frame-drop win is a hypothesis. The reasoning is
sound but the round-4 NEON work is a standing reminder that predicted wins can
evaporate on hardware. The device A/B (below) settles it. The default does not
change until it does.

## Approach

Let the user choose the recorded bit depth. Reducing depth discards the noisiest
low bits -- precisely where Rice coding spends most of its output -- so it
shrinks both the encode work and the file.

Two facts in the existing code make this far narrower than it looks:

1. `capture.cpp:189` derives `bitDepth` as `32 - __builtin_clz(whiteLevel)`, and
   `capture.cpp:423` selects the pack mode from `whiteLevel`. Writing a *reduced*
   `whiteLevel` into the header makes the codec and the packer follow
   automatically -- selecting 10-bit turns the uncompressed path into `Packed10`
   with no extra code.
2. `dng_writer.cpp:164-165` writes `blackLevel`/`whiteLevel` straight into DNG
   tags 50714/50717. Scale both consistently and export stays correct with no
   export-side change.

So the work is: truncate the samples, scale both levels, thread the setting
through.

### Where the shift is applied (the one real decision)

`predictAt` (`rawv_codec.cpp:310`) reads `raw16` **directly** -- there is no
scratch buffer at 1x -- and the same function serves encode and decode (`plane`
is the source frame when encoding, the output buffer when decoding).

Three options were considered:

- **A (chosen). Shift on read, encode path only.** Thread a `shift` through the
  encoder's sample reads and `predictAt`; decode passes 0, because its buffer
  already holds reduced values. Costs one ALU op per sample inside loops already
  stalled on memory -- effectively free.
- **B. A separate truncation pass before encode.** One function, trivially
  testable, but adds a full read+write of a 25 MB frame every frame: ~600 MB/s of
  extra memory traffic at 24 fps, on a workload already established as
  memory-bandwidth-bound. Would plausibly consume the win.
- **C. Fold into the crop pass.** Free when zoomed, but at 1x `capture.cpp:245`
  writes the delivered buffer verbatim, so it degenerates into B in exactly the
  case that drops frames.

A is chosen: B and C spend bandwidth to save CPU, and bandwidth is the binding
constraint.

**Risk carried by A:** the shift must reach every encode read site --
`computeBands` pass 1, the band workers' fused predict+residual+pack, and
`pack10`/`pack12` for the uncompressed path. A missed site silently corrupts
output rather than failing loudly. Covered by round-trip tests at every depth.

## Behaviour

### The option list

Offered depths: **Native, 14, 12, 10, 8**.

There is no 16-bit entry. No sensor on this device delivers 16 bits -- `Raw16` is
a container format, not a precision -- so offering it would claim something
untrue. "Native" is the top entry and stays correct on future hardware.

Sensor-native depth differs per lens: in the 2026-08-30 clips the main camera
reported `whiteLevel=16383` (14-bit) and the ultra-wide `whiteLevel=1023`
(10-bit). Depth can only ever be reduced; precision that never left the sensor
cannot be synthesised.

The list is therefore **fixed, with entries above the active lens's native depth
visibly disabled** (option (c) of three considered; the alternatives were a menu
that changes per lens, and silent clamping, both rejected -- the first for a
moving menu, the second because "12-bit" would sometimes mean 10).

**"Native" and an explicit equal depth are not the same setting**, even where they
currently produce identical output. On the 14-bit main camera both Native and 14
record `shift = 0` today; the difference is what happens on another lens or
another device. Native *follows* the sensor -- it is always `shift = 0`, whatever
the sensor gives. An explicit 14 *pins* the depth, and clamps down on a lens that
cannot reach it. Native is the only entry never disabled.

### Persistence and clamping

`recordBitDepth: Int` in `SettingsRepository` (0 = Native), persisted.

The **requested** value is stored; clamping happens only at capture. Selecting
12-bit on the main camera and switching to the ultra-wide records 10-bit for that
lens, and switching back restores 12 -- lens switching never silently degrades a
setting the user chose.

### Default

**Native.** Nothing changes until the user deliberately selects a lower depth.

A RAW capture app should not quietly begin discarding sensor data on upgrade;
fidelity is why the format was chosen. Flipping the default later is a one-line
change, and should be backed by the device A/B rather than by this reasoning.

### Rounding

Round, do not truncate, and clamp: `(x + (1 << (n-1))) >> n`, capped at the new
white level. A bare `>>` biases every sample down by half an LSB, shifting the
black point.

**No dithering.** It would hide banding in gradients but adds entropy, which
works directly against the purpose of the feature.

## Files touched

**Kotlin**

- `settings/SettingsRepository.kt` -- `recordBitDepth` (0 = Native), persisted and
  coerced to the valid set.
- `ui/SettingsScreen.kt` -- picker following the existing
  `freeSpaceReserveSeconds` pattern (line 211), entries disabled per active lens.
- `camera/CameraController.kt` -- pass the requested depth to
  `nativeStartRecording`; `RawSpec` already carries `whiteLevel`, which supplies
  the native depth for clamping.
- `ui/RecordScreen.kt` -- `frameRecordBytes` must model the **selected** depth
  (it currently keys off `spec.whiteLevel`), and `captureRateKey` must gain bit
  depth as an axis. Without the latter, a rate measured on a 14-bit take
  mispredicts a 12-bit one and re-breaks the time-left readout fixed earlier the
  same day.

**C++**

- `cpp/jni_bridge.cpp`, `cpp/capture.cpp` -- accept the requested depth, compute
  `shift = nativeDepth - selectedDepth`, write scaled `whiteLevel` and
  `blackLevel[4]` into the header. The existing `bitDepth` derivation and
  pack-mode selection then follow unchanged.
- `core/src/rawv_codec.cpp` -- thread the shift through the encode read sites;
  decode passes 0.
- `core/src/pack10.cpp` -- shift on read for the uncompressed packed modes.

## Format compatibility

**No format change and no version bump.** Reduced depth is fully described by
`whiteLevel` + `blackLevel`, which `rawv_reader` and `dng_writer` already consume.
Existing clips are unaffected and DNG export needs no edit.

## The correctness trap

`blackLevel[4]` is in sensor units and **must** be scaled by the same shift with
the same rounding as the samples. Miss it and every frame carries a wrong black
point and every exported DNG is subtly broken -- visually plausible, quietly
wrong, and not something a smoke test would catch. This gets dedicated tests.

## Testing

**Host (core, ctest -- build from PowerShell, not the Bash tool)**

- Round-trip encode/decode is bit-exact at each depth.
- Residuals genuinely shrink as depth falls (the mechanism the feature depends on).
- `worstCaseRiceRowBytes` still bounds output at reduced depth -- this is the
  buffer-overflow guard, and a wrong bound here is a memory-safety bug.
- Rounding is unbiased and clamps at the new white level.
- `blackLevel` scales consistently with the samples.

**JVM**

- Clamp-per-lens: requested value preserved, effective value clamped.
- Settings persistence and coercion.
- `frameRecordBytes` and `captureRateKey` at each depth.

**Device**

- A **cool, unplugged** 14-bit vs 12-bit A/B on the main camera, measuring
  landing rate. Both the 2026-08-17 round-5 measurement and the 2026-08-30
  measurement above were taken on a warm, USB-plugged device and are therefore
  upper bounds on drops, not baselines. A clean baseline is needed to know
  whether the gap is 1.05x or 1.4x.
- Verify `packMode@20 == 3` and the reduced `whiteLevel@28` on the resulting clip
  before trusting any number.
- Confirm an exported DNG from a reduced-depth clip has correct black and white
  points.

## Out of scope

- Changing the default away from Native (revisit after the A/B).
- A 16:9 capture crop (a separate ~25% pixel reduction, noted as the other
  remaining lever).
- The unmeasured landing rate under zoom (open item #2 from the zoom work).
