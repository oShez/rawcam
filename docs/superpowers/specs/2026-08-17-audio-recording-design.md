# Audio Recording -- Design Spec

**Date:** 2026-08-17
**Status:** Approved (brainstorming complete; implementation plan not yet written)
**Scope:** Add production-quality audio capture to RawCam, synchronized to RAW frame
sensor timestamps, delivered as a sidecar WAV alongside each `.rawv` clip and copied
into the export folder next to the CinemaDNG sequence.

## 1. Goal and reference points

RawCam records RAW video to `.rawv` and exports CinemaDNG sequences. CinemaDNG has no
audio track, so audio must be delivered as a separate file regardless of how it is
stored during capture.

Audio has been on the deferred list since the original capture spec
(`2026-07-09-raw-video-capture-design.md`) and was explicitly ruled out of scope by the
settings-page and universal-camera-support specs. This spec supersedes those deferrals.

The quality bar is set by the two apps in this space:

- **MotionCam Pro / `.mcraw`** -- audio is muxed into the container as timestamped
  chunks (`std::pair<int64_t Timestamp, std::vector<int16_t>>`), 16-bit PCM, with sample
  rate and channel count in container metadata. Its open-source decoder exports one
  `audio.wav` next to the DNG sequence, and notably performs *no* timestamp correlation
  -- it concatenates chunks in order. The container can express accurate sync; the
  shipped exporter does not use it.
- **Blackmagic Camera (Android)** -- input source selection, sample rate Auto/44.1/48 kHz,
  mono or stereo, manual gain, and a levels meter with selectable ballistics
  (VU and PPM at -18/-20 dBFS reference). Encodes AAC into the MOV/BRAW container.

RawCam targets MotionCam's delivery model (WAV beside the DNGs) with sync that is
actually applied rather than merely recorded, plus Blackmagic's input/gain/meter
controls.

### Requirements

1. Real, usable production audio from the device -- not a scratch/sync-only guide track.
2. User-selectable input device among those connected to the phone.
3. Levels meter with a clip indicator.
4. Manual gain control.
5. Audio must never cost a RAW frame.

### Non-goals (v1)

- User-facing sample-rate or channel-count options. 48 kHz is pinned; stereo is used
  when the selected device offers it. Deliberate: one fewer axis of device-specific bugs.
- Mic clock drift *correction* (resampling). Drift is measured and recorded; correcting
  it is a later round.
- Bluetooth inputs. See section 4.
- DNG timecode tags matched to a BWF timecode reference. Noted as the natural follow-on
  in section 12.
- Live gain adjustment while recording. See section 8.

## 2. Chosen architecture: sidecar WAV, self-describing `.rawv`

Audio is a self-contained Kotlin subsystem. It never touches `capture.cpp`.

A dedicated audio thread pair owns its own file descriptor and writes `clip_<ts>.wav`
directly. The `.rawv` header bumps to version 5, but **header fields only** -- new fields
are carved out of the existing `reserved[284]`, so the on-disk *record* layout is
unchanged. No record-parsing code changes: the crash-recovery frame scan and `exportClip`
are untouched.

The one unavoidable reader change is the version gate. `rawv_reader.cpp:84` currently
rejects anything but an exact match (`h.version != kVersion`), so bumping `kVersion` to 5
without relaxing it would make **every existing v4 clip unreadable**. The gate becomes an
accepted-set check (4 or 5). This is a one-line change with a regression test (section 9),
but it must not be overlooked.

### Alternatives considered

**Mux into `.rawv` v5 with a record-type tag (MotionCam parity).** Rejected. Audio chunks
would funnel through `writerThread_` -- the single thread whose milliseconds rounds 3
through 5 were spent optimizing, and the one path with a demonstrated dropped-frame
problem. Contention is small in absolute terms (~288 KB/s against ~200 MB/s of RAW), but
it is new work in the worst possible place. It also forces `rawv_reader`, the recovery
scan, and `exportClip` to learn to skip audio records, and then requires demuxing back
out to WAV at export -- strictly more work for a byte-identical output.

**Sidecar during capture, remux at finalize.** Rejected. Copies tens of megabytes at stop
for no user-visible gain.

### Cost of the chosen approach, and its mitigation

Two files per clip can be separated by a user hand-copying files off the device. Mitigated
three ways: identical basenames; app-level pairing across delete / share / export /
auto-export / delete-after-export (section 7); and a BWF `bext` chunk that makes the WAV
carry its own sync provenance even when orphaned (section 6).

### Why Kotlin rather than the native core

`AudioRecord` is a JVM API and the data rate is ~288 KB/s. There is no performance
argument for JNI. The only native change is additive header fields plus one new entry
point to write them.

## 3. Data flow

```
CameraController.startRecording()
   |- 1. AudioRecorder.start()      <- FIRST, so audio always precedes frame 0
   |- 2. nativeStartRecording()     <- unchanged
   \- 3. createSession()            <- unchanged, blocks for 100s of ms

AudioRecorder (Kotlin, 2 threads)
   read thread  --ring buffer-->  write thread --> WavWriter --> clip_<ts>.wav
   (URGENT_AUDIO priority,                         (own fd)
    gain, peak/RMS,
    clock anchors)
            |
            v
      AudioMeterState (atomic) --> RecordScreen meter

CameraController.onCaptureCompleted (first frame only)
   \- AudioRecorder.onFirstFrame(sensorTs) -> compute trim, flush preroll, stream

CameraController.stopRecording()
   |- 1. quiesce camera (stopRepeating, abortCaptures, await idle)  <- unchanged
   |- 2. AudioRecorder.stop() -> AudioResult
   |- 3. nativeSetAudioInfo(...)
   \- 4. nativeStopRecording()   <- finalizes the header, so MUST come last
```

Arming audio *before* the capture session is what makes alignment an exact trim rather
than a guessed pad: session configuration already blocks for hundreds of milliseconds, so
audio is reliably running before the first frame arrives.

**The trim is applied at the start of a take, not at finalize.** RIFF data begins at a
fixed byte offset, so removing leading samples once the file exists would mean rewriting
the whole thing. Instead the recorder holds captured audio in memory until frame 0's
`SENSOR_TIMESTAMP` arrives, applies the trim (or pad) to that buffered prefix, and then
streams straight to disk. Because audio arms before session configuration, the prefix is
well under a second -- roughly 300 KB, capped at 2 s.

**`nativeSetAudioInfo` must precede `nativeStopRecording`.** The latter finalizes the
header; audio provenance published after it would be silently discarded.

Two threads rather than one so a filesystem stall cannot cause an `AudioRecord` overrun.
This mirrors the read/queue/write shape `capture.cpp` already uses.

## 4. Components

| Unit | Responsibility | Depends on |
|---|---|---|
| `audio/AudioDeviceCatalog.kt` | Enumerate inputs via `AudioManager.getDevices(GET_DEVICES_INPUTS)`, map to display names, hot-plug via `AudioDeviceCallback`, resolve a persisted selection to a live device | AudioManager |
| `audio/AudioRecorder.kt` | Own `AudioRecord`; thread lifecycle; gain; meter state; clock anchors; failure policy | Catalog, WavWriter, AvSync |
| `audio/WavWriter.kt` | Streaming RIFF: placeholder header, append, patch sizes on close; head trim; `bext` chunk; truncation repair | nothing (pure) |
| `audio/AvSync.kt` | Pure functions: clock bridge, offset, trim-in-samples, drift ppm, suspend detection | nothing (pure) |
| `ui/AudioMeter.kt` | Meter composable, including the NO AUDIO / AUDIO DEGRADED states | AudioMeterState |
| `core/include/rawcam/rawv.h` | v5 header fields | -- |
| `app/src/main/cpp/capture.cpp` + JNI | `nativeSetAudioInfo`, written into the header at finalize | rawv.h |

`AvSync.kt` and `WavWriter.kt` are deliberately dependency-free: they hold the logic that
is actually hard to get right, and that isolation is what makes it testable on the JVM
without an Android device.

`ui/AudioMeter.kt` is a separate file because `RecordScreen.kt` is already 2217 lines.

### Capture parameters

- **48 kHz**, pinned. No user option.
- **Stereo** when the selected device reports two channels, else mono.
- **`MediaRecorder.AudioSource.UNPROCESSED`** -- the only source that disables AGC, noise
  suppression, and echo cancellation, all of which pump and mangle production sound.
  Falls back to `VOICE_RECOGNITION`, then `MIC`, on devices that do not advertise
  `PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED`. The source actually used is recorded in
  the header and the `bext` chunk, because it materially changes how the clip sounds.
- **Float internally, 24-bit PCM on disk.** Gain is applied in float. 24-bit is
  universally supported by NLEs. A 16-bit source is zero-extended; this is documented
  rather than special-cased, to keep one write path.

### Bluetooth exclusion

Bluetooth inputs are excluded from the picker in v1. SCO and LE Audio have variable,
uncharacterizable latency, so a Bluetooth mic would produce a clip whose sync claim is
false. Offering an input that silently breaks the feature's central promise is worse than
not offering it. Revisit only with a per-device latency calibration step.

## 5. Sync

The mechanism, in order:

1. **Determine the camera's clock.** Read `SENSOR_INFO_TIMESTAMP_SOURCE` at start.
   `REALTIME` means `SENSOR_TIMESTAMP` is on `CLOCK_BOOTTIME`; `UNKNOWN` means
   `CLOCK_MONOTONIC`. The app currently never reads this flag. The value is recorded in
   the header either way, so a clip is never ambiguous about what its timestamps mean.

2. **Bridge the clocks, repeatedly.** Sample `System.nanoTime()` and
   `SystemClock.elapsedRealtimeNanos()` back to back. The delta is the
   monotonic-to-boottime offset, which changes only when the device suspends.

   This is re-measured roughly once per second on the audio thread, **not** once at start.
   Nothing in the app currently holds a wakelock or `FLAG_KEEP_SCREEN_ON`, so suspend
   mid-take is possible, and a one-shot bridge would silently rot by the suspend duration.
   If the bridge moves, the take sets `audioStatus` bit 4 (`SUSPENDED`) and the user is
   warned, rather than being handed a clip that is quietly out of sync.

3. **Anchor the audio stream.** `AudioRecord.getTimestamp(AudioTimestamp, TIMEBASE_BOOTTIME)`
   yields `(framePosition, nanoTime)` pairs -- when a specific sample reached the
   converter, not when `read()` returned. Polled alongside the bridge.

4. **Trim**, applied to the buffered in-memory prefix the moment frame 0 arrives (section 3).
   `trimSamples = (frame0SensorTime_boottime - audioSample0_boottime) * rate / 1e9`
   Because audio arms first this is positive in practice, making alignment an exact trim.
   A negative result (audio started late) pads with silence at the head and sets
   `audioStatus` bit 5 (`PADDED`), so the padding is never mistaken for recorded silence.
   After finalize, WAV sample 0 corresponds to frame 0's `SENSOR_TIMESTAMP`, so the
   WAV drops onto an NLE timeline against the DNG sequence with no manual nudge.

5. **Drift.** A least-squares slope over the anchor series gives the mic clock's error in
   ppm. Measured, written to the header and the `bext` chunk, and warned about past a
   threshold. Not corrected in v1.

**Convention:** `SENSOR_TIMESTAMP` is the start of exposure. Alignment is to that instant,
and this is stated in the header documentation so future readers do not have to infer it.

**Related fix:** add `FLAG_KEEP_SCREEN_ON` while recording. A camera app blanking mid-take
is its own bug, independent of audio; it also makes suspend far less likely in practice.

## 6. On-disk format

### `.rawv` header, version 4 -> 5

New fields carved from `FileHeader.reserved[284]`. `sizeof(FileHeader)` remains 512 and
the record layout is untouched.

| Field | Type | Meaning |
|---|---|---|
| `audioPresent` | `uint32_t` | 0 = no audio (all v4 files, and v5 clips recorded with audio off) |
| `audioSampleRate` | `uint32_t` | Hz, 48000 in v1 |
| `audioChannels` | `uint32_t` | 1 or 2 |
| `audioBitsPerSample` | `uint32_t` | 24 in v1 |
| `audioOffsetNs` | `int64_t` | Measured pre-trim offset, defined as `frame0SensorTime - audioSample0Time` (same sign convention as the trim formula in section 5: positive means audio started first, the normal case). Kept for provenance; the delivered WAV is already trimmed |
| `audioDriftPpm` | `int32_t` | Measured mic clock error |
| `audioTimestampSource` | `uint32_t` | 0 = unknown/monotonic, 1 = realtime/boottime |
| `audioStatus` | `uint32_t` | Bitfield; why audio is absent or degraded. See below and section 8 |
| `audioSource` | `uint32_t` | Which `MediaRecorder.AudioSource` was actually opened |
| `audioFileName` | `char[64]` | Sidecar basename, NUL-terminated |

Total 100 bytes of the available 284.

`audioStatus` is a bitfield, so a clip can report several conditions at once. There is no
separate boolean field for sync validity -- "sync is trustworthy" means
`(audioStatus & SYNC_INVALIDATING) == 0`, where `SYNC_INVALIDATING` is the mask of bits 3,
4, and 5 below.

| Bit | Name | Meaning |
|---|---|---|
| 0 | `PERMISSION_DENIED` | `RECORD_AUDIO` not granted; clip is silent |
| 1 | `OPEN_FAILED` | `AudioRecord` could not be opened; clip is silent |
| 2 | `ENDED_EARLY` | Device disconnected, read error, or disk full; WAV is short |
| 3 | `OVERRUNS` | Buffer overruns occurred; samples are missing mid-stream |
| 4 | `SUSPENDED` | Clock bridge moved mid-take; offset may be wrong by the suspend duration |
| 5 | `PADDED` | Audio started *after* frame 0; the head is silence, not real signal |
| 6 | `DRIFT_HIGH` | Measured drift exceeded the warning threshold |
| 7 | `PROCESSED_SOURCE` | `UNPROCESSED` unavailable; AGC/NS may have altered the audio |

**Compatibility:** the reader accepts version 4 and version 5. A v4 file reads back with
all audio fields zeroed, which is exactly `audioPresent = 0`. Writers always emit v5.

### WAV

Standard RIFF/WAVE, 24-bit LE PCM, interleaved. Plus a BWF `bext` chunk (~600 bytes)
carrying the offset, drift, input device, audio source, clock base, and overrun count.

The `bext` chunk matters more than its size suggests under this design: it makes the WAV
self-describing, so a WAV separated from its `.rawv` still carries its own sync
provenance. It directly mitigates the sidecar approach's one real weakness.

## 7. File pairing and lifecycle

`clip_<ts>.rawv` and `clip_<ts>.wav`, same basename, same `clips/` directory.

- **Clips list** stays driven by `.rawv` files (unchanged). Rows gain an audio badge when
  the sibling exists.
- **Delete** removes both files.
- **Share** uses `ACTION_SEND_MULTIPLE` for the pair.
- **Export**: after the DNG export succeeds, `ExportService` copies the sibling to
  `<base>.wav` in the export folder. This is a Kotlin-side file copy; `exportClip` and the
  native exporter are untouched. A failed copy warns but does **not** fail the export --
  the DNGs are the irreplaceable asset.
- **`deleteAfterExport`** removes both source files.
- **Auto-export** inherits all of the above, since it runs through the same
  `ExportService` path.
- **Crash recovery**: a WAV from a killed process has bogus RIFF size fields.
  `WavWriter.repairIfTruncated()` infers the data size from the file length and is called
  by `ExportService` before the copy. This mirrors the recover-by-scan approach `.rawv`
  already uses for `frameCount`.
- **Free-space reserve is unchanged.** Audio is roughly 0.15% of the video bitrate;
  folding it into `startRecordingInternal`'s arithmetic adds a term that never changes an
  outcome. This is a decision, not an oversight.

## 8. Settings, UI, and failure behavior

### Settings

Three new fields on `Settings`, each following the file's documented corrupt-key contract
(a default for every field, clamped on write in `update()` alongside `clipPrefix` and
`freeSpaceReserveSeconds`):

| Field | Default | Notes |
|---|---|---|
| `recordAudio` | `false` | Default off so an upgrade neither springs a mic-permission prompt nor silently starts writing a second file per clip |
| `audioInputKey` | `""` | Empty = system default. Encoded `"<type>:<productName>"`, **not** `AudioDeviceInfo.getId()`, which is unstable across reconnects. Re-resolved against the live device list at every start |
| `audioGainDb` | `0f` | Clamped -20.0 to +30.0 |

`RECORD_AUDIO` is requested when the user flips the toggle in Settings, never at record
time -- a permission dialog appearing as the user hits record is how takes get lost.

Manifest addition: `<uses-permission android:name="android.permission.RECORD_AUDIO" />`.
No foreground-service type is needed: recording is Activity-scoped and backgrounding
already finalizes the clip via `MainActivity.onStop`.

### UI

- **Settings screen**: a new "Audio" section -- record-audio toggle, input picker (live
  list; a persisted device that is no longer present shows "unavailable -- using default"
  rather than failing), gain slider with a dB readout.
- **Record screen**: an `AudioMeter` in the HUD, visible whenever audio is enabled and
  independent of the `showStatsSidebar` toggle -- it is a recording-critical indicator,
  not a stat. Per-channel bars, ~1.5 s peak hold, and a clip indicator latching red for
  2 s at or above -0.1 dBFS. The same widget renders the NO AUDIO and AUDIO DEGRADED
  states.

**Gain is locked while recording.** Live gain is a Blackmagic feature, but changing it
mid-take writes a level discontinuity into the file that nothing records. Freely
adjustable in standby, frozen once rolling.

### Failure behavior: video always wins

Every path below lets the RAW recording run to completion, latches the NO AUDIO or AUDIO
DEGRADED badge, toasts the cause on stop, and writes an `audioStatus` code into the v5
header so the clip records *why*. RAW frames are the irreplaceable asset; audio is
re-recordable.

| Failure | Result |
|---|---|
| `RECORD_AUDIO` denied | Record silent, warn |
| `AudioRecord` open fails, or mic held by another app | Record silent, warn |
| USB mic unplugged mid-take | Finalize WAV at that point, warn |
| `read()` returns `ERROR_DEAD_OBJECT` / `ERROR_INVALID_OPERATION` | As unplug |
| Buffer overruns | Counted; `OVERRUNS` bit set, clip reported sync-degraded |
| Disk full on WAV write | Close WAV, warn, video continues; `ENDED_EARLY` |
| Suspend detected (clock bridge moved) | WAV kept, `SUSPENDED` bit set, warn |

## 9. Testing

### JVM (`app/src/test`, JUnit 4)

This is where the genuinely hard logic lives, and all of it is dependency-free by design.

- **`AvSyncTest`** -- trim-samples from synthetic anchors; the negative-offset pad branch;
  drift ppm from a deliberately drifting anchor series; suspend detection on a jumped
  bridge; `REALTIME` and `UNKNOWN` conversion in both directions.
- **`WavWriterTest`** -- byte-exact RIFF (field offsets, size patching, 24-bit LE packing
  including sign extension of negative samples, stereo interleave), head trim by N
  samples, `bext` chunk layout and size, truncated-file repair.
- **`AudioDeviceCatalogTest`** -- persisted-key resolution when the device is present,
  absent, or renamed. Fixture-driven in the same JSON style as the existing
  `LensDiscovery` tests.

### ctest (`core/tests`)

Extend `test_rawv_layout`: v5 field offsets, `sizeof(FileHeader) == 512` still holds, a v4
file reads back with audio fields zeroed, and the reader accepts versions 4 and 5.

### On-device acceptance

The codec rounds established that a green host build proves nothing about device
behavior, so the sync claim is measured rather than asserted:

- **Clap test.** Record a clap, export, and measure the offset between the audio transient
  and the frame in which the hands meet. Perform at the head of a take and again at ten
  minutes, to observe drift.
- **Both known-good hardware families** -- Xiaomi 14 Ultra (24030PN60G) and Samsung Galaxy
  S22 Ultra (SM-S908E). `UNPROCESSED` support and `SENSOR_INFO_TIMESTAMP_SOURCE` both vary
  by vendor, and these are the two families already device-verified for capture.
- **Input switching.** Verify built-in mic and at least one USB audio interface, including
  unplugging mid-take to exercise the failure path.

### Device gotchas to respect

Per prior on-device sessions: HyperOS drops the app's own logcat tags, so capture logs to
a file instead. Verify the recorded header by reading the `FileHeader` struct directly --
do not eyeball adjacent `u32`s -- before trusting any measurement.

## 10. Success criteria

1. A clip recorded with audio enabled produces `clip_<ts>.wav` whose sample 0 corresponds
   to frame 0's `SENSOR_TIMESTAMP`, verified by clap test to within one frame at 24 fps.
2. Exporting that clip yields a folder containing the DNG sequence and `<base>.wav`; the
   WAV drops onto an NLE timeline against the sequence with no manual nudge.
3. Input device selection works across built-in and USB inputs, and survives a
   disconnect/reconnect cycle.
4. The meter shows accurate levels and latches on clipping.
5. No audio-related regression in frame landing rate. The compressed-capture path is
   bandwidth-bound and must be unaffected -- `capture.cpp` is not modified.
6. Every failure in section 8 leaves a complete, valid RAW recording.

## 11. Risks

| Risk | Mitigation |
|---|---|
| `SENSOR_INFO_TIMESTAMP_SOURCE` is `UNKNOWN` and the vendor's actual base is neither documented clock | The clap test measures ground truth on each hardware family; the header records which source was reported, so a wrong assumption is diagnosable after the fact rather than invisible |
| `UNPROCESSED` unsupported, so AGC/NS silently alters the audio | The source actually opened is recorded in the header and `bext`, and surfaced in the UI |
| USB audio interfaces vary widely in Android support | The failure path is explicit and non-fatal; the catalog re-resolves by type and product name |
| Sidecar WAV separated from its `.rawv` | Identical basenames, app-level pairing, and a self-describing `bext` chunk |
| Suspend mid-take corrupts the clock bridge | Bridge re-measured about once per second; suspend detected and flagged, not silently absorbed. `FLAG_KEEP_SCREEN_ON` added while recording |

## 12. Deferred

- Mic clock drift **correction** by resampling. v1 measures and reports it.
- Bluetooth inputs, pending a per-device latency calibration step.
- Blackmagic-style meter ballistics (VU and PPM at -18/-20 dBFS reference). v1 ships a
  peak meter with clip indication.
- User-facing sample-rate and channel options.
- Matched timecode: DNG `TimeCodes` tags written to align with a BWF `TimeReference`, so
  an NLE auto-syncs by timecode rather than by position. This is the natural follow-on and
  the header already reserves room for it.
- Live gain adjustment during recording, which would require recording gain changes as
  timestamped events so the level discontinuity is at least documented.
