# Universal Camera Support (Spec A) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make RawCam enumerate and record on any Android phone without ever crashing, surfacing every RAW lens the device genuinely has.

**Architecture:** Extract enumeration out of `CameraController` into a pure, Android-free function over plain data. `Camera2SnapshotSource` (the only code touching `CameraCharacteristics`) produces `List<CameraSnapshot>`; `LensDiscovery.discover()` turns that into a sealed `DeviceProfile` that is either `Supported` or `Unsupported` and never throws. Because `discover()` is pure, a JSON snapshot from any phone becomes a JVM test fixture.

**Tech Stack:** Kotlin, Jetpack Compose, Camera2, kotlinx.serialization (new), JUnit4 (new — first Kotlin tests in this project).

Spec: `docs/superpowers/specs/2026-07-22-universal-camera-support-design.md`

## Global Constraints

- **Never throw from discovery.** `LensDiscovery.discover()` returns `Supported` or `Unsupported` for every conceivable input. This is the spec's floor and is enforced by a fuzz test.
- **Hard requirements** (reject lens if missing): RAW output sizes non-empty, CFA, white level, black level. **Everything else is soft** — default it, record a `ProfileNote`, keep the lens.
- **Preserve the WB/lens identity split verbatim.** `activePhysicalId` (WB identity key), `activeCameraId` (what `openCamera` opens), `sessionTagId` (what `setPhysicalCameraId` tags, null for standalone) keep their exact current meanings. Commit `e74ead8` documents why; breaking it breaks telephoto support.
- **No INTERNET permission in this plan.** Report export uses the OS share sheet only.
- **Waves 1–2 fixtures must stand alone.** No test, coverage target, or gate may depend on the FV-5 corpus (Task 11).
- **Out of scope:** sensor-orientation correctness (Spec B), quirks DB/network (Spec C), throughput adaptation (Spec D), front cameras, logical-zoom pseudo-lenses.
- **minSdk 33, targetSdk 35, compileSdk 35, jvmTarget 17.** Do not change.
- **Commit on branch `spec/universal-camera-support`.** Never commit to `main`.

## File Structure

| File | Responsibility |
|---|---|
| `app/src/main/java/com/shez/rawcam/camera/CameraSnapshot.kt` (create) | Plain data + `@Serializable`. Zero `android.*` imports. |
| `app/src/main/java/com/shez/rawcam/camera/DeviceProfile.kt` (create) | `DeviceProfile`, `LensProfile`, `ProfileNote`, `ControlTier`, `UnsupportedReason`, `SnapshotField`. Zero `android.*` imports. |
| `app/src/main/java/com/shez/rawcam/camera/LensDiscovery.kt` (create) | Pure `discover()`. Zero `android.*` imports. |
| `app/src/main/java/com/shez/rawcam/camera/Camera2SnapshotSource.kt` (create) | Only place `CameraCharacteristics.get()` is called; widened id probe. |
| `app/src/main/java/com/shez/rawcam/camera/CompatibilityReport.kt` (create) | Renders a `DeviceProfile` to shareable text. |
| `app/src/main/java/com/shez/rawcam/camera/ShutterStops.kt` (create) | Intersects the shutter stop table with the sensor's real exposure range. |
| `app/src/main/java/com/shez/rawcam/camera/CameraController.kt` (modify) | Delete `enumerateLenses`/`buildLensCandidate`/`probeHiddenLenses`; consume `DeviceProfile`. |
| `app/src/main/java/com/shez/rawcam/ui/RecordScreen.kt` (modify) | `RecordUiState` gains profile/tier fields; unsupported screen; disabled sliders. |
| `app/src/main/java/com/shez/rawcam/ui/SettingsScreen.kt` (modify) | Compatibility-report and dump rows. |
| `app/src/test/java/com/shez/rawcam/camera/*` (create) | First JVM tests in this project. |
| `app/src/test/resources/fixtures/*.json` (create) | Shape + real-device fixtures. |

`CameraController.kt` is currently 1,523 lines; this plan removes roughly 250 and adds none — a deliberate reduction of a file that has grown unwieldy.

---

### Task 1: JVM test infrastructure + `CameraSnapshot`

**Files:**
- Modify: `app/build.gradle.kts` (plugins block ~line 4, dependencies block ~line 57)
- Create: `app/src/main/java/com/shez/rawcam/camera/CameraSnapshot.kt`
- Test: `app/src/test/java/com/shez/rawcam/camera/CameraSnapshotTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `CameraSnapshot`, `SizeSpec`, `RectSpec`, `SnapshotSet` with `toJson(): String` and `SnapshotSet.fromJson(String): SnapshotSet`.

- [x] **Step 1: Find the project's Kotlin version so the serialization plugin matches**

Run: `grep -rn "kotlin" build.gradle.kts settings.gradle.kts gradle/libs.versions.toml 2>/dev/null | grep -i version`

Use the exact version string found as `<KOTLIN_VERSION>` below. The serialization plugin version **must** equal the Kotlin plugin version or the build fails with a plugin-resolution error.

- [x] **Step 2: Add the serialization plugin and test dependencies**

In `app/build.gradle.kts`, add to `plugins`:

```kotlin
    id("org.jetbrains.kotlin.plugin.serialization") version "<KOTLIN_VERSION>"
```

Add to `dependencies`:

```kotlin
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    testImplementation("junit:junit:4.13.2")
```

Add inside `android { }`:

```kotlin
    testOptions { unitTests.isReturnDefaultValues = true }
```

- [x] **Step 3: Write the failing round-trip test**

Create `app/src/test/java/com/shez/rawcam/camera/CameraSnapshotTest.kt`:

```kotlin
package com.shez.rawcam.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CameraSnapshotTest {

    private fun minimalSnapshot() = CameraSnapshot(
        cameraId = "0",
        facing = 1,
        capabilities = listOf(0, 1, 3),
        physicalIds = listOf("2", "3"),
        rawSizes = listOf(SizeSpec(4096, 3072, 41_666_666L)),
        cfa = 0,
        whiteLevel = 1023,
        blackLevel = listOf(64, 64, 64, 64),
    )

    @Test
    fun `round trips through json`() {
        val original = SnapshotSet(model = "Pixel 7 Pro", sdkInt = 34, cameras = listOf(minimalSnapshot()))
        val restored = SnapshotSet.fromJson(original.toJson())
        assertEquals(original, restored)
    }

    @Test
    fun `absent optional fields decode as null`() {
        val restored = SnapshotSet.fromJson(
            """{"model":"X","sdkInt":34,"cameras":[{"cameraId":"0","facing":1}]}"""
        )
        val cam = restored.cameras.single()
        assertNull(cam.focalLengthsMm)
        assertNull(cam.colorTransform1)
        assertEquals(emptyList<SizeSpec>(), cam.rawSizes)
    }
}
```

- [x] **Step 4: Run it and confirm it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "*CameraSnapshotTest*"`
Expected: FAIL — `Unresolved reference: CameraSnapshot`.

- [x] **Step 5: Implement `CameraSnapshot.kt`**

```kotlin
package com.shez.rawcam.camera

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One RAW output size and its minimum frame duration (ns; 0 = unknown). */
@Serializable
data class SizeSpec(val width: Int, val height: Int, val minFrameDurationNs: Long = 0)

/** Sensor pixel bounds, mirroring android.graphics.Rect without importing it. */
@Serializable
data class RectSpec(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/**
 * A plain-data capture of one camera's CameraCharacteristics. Deliberately free
 * of android.* types so [LensDiscovery] stays a pure function and any real
 * device's characteristics can be committed as a JSON test fixture.
 *
 * Every field beyond [cameraId] is nullable or defaulted: a HAL may omit any
 * key, and an app without CAMERA permission sees most of them redacted to null.
 * Absence is data, never an error.
 */
@Serializable
data class CameraSnapshot(
    val cameraId: String,
    /** CameraMetadata.LENS_FACING_* ; 1 == BACK. Null when redacted. */
    val facing: Int? = null,
    /** REQUEST_AVAILABLE_CAPABILITIES; 3 == RAW, 1 == MANUAL_SENSOR. */
    val capabilities: List<Int> = emptyList(),
    val hardwareLevel: Int? = null,
    /** Physical children if this is a logical multi-camera; empty otherwise. */
    val physicalIds: List<String> = emptyList(),
    val rawSizes: List<SizeSpec> = emptyList(),
    /** SENSOR_INFO_COLOR_FILTER_ARRANGEMENT: RGGB=0 GRBG=1 GBRG=2 BGGR=3. */
    val cfa: Int? = null,
    val whiteLevel: Int? = null,
    val blackLevel: List<Int>? = null,
    val focalLengthsMm: List<Float>? = null,
    val physicalSizeMm: List<Float>? = null,
    /** Row-major 3x3, CIE XYZ -> sensor space. */
    val colorTransform1: List<Float>? = null,
    val colorTransform2: List<Float>? = null,
    val illuminant1: Int? = null,
    val illuminant2: Int? = null,
    val isoRange: List<Int>? = null,
    /** SENSOR_INFO_EXPOSURE_TIME_RANGE in ns, [min, max]. */
    val exposureRangeNs: List<Long>? = null,
    val activeArray: RectSpec? = null,
    val minFocusDiopters: Float? = null,
    val oisModes: List<Int>? = null,
    /** Captured for Spec B (orientation correctness); unused by this spec. */
    val sensorOrientation: Int? = null,
    /** True when this id is NOT a physical child of the primary logical camera
     * and must be opened as its own top-level CameraDevice. Set by the probe. */
    val standalone: Boolean = false,
)

/** A whole device's snapshot: what a fixture file contains. */
@Serializable
data class SnapshotSet(
    val model: String,
    val sdkInt: Int,
    val cameras: List<CameraSnapshot>,
) {
    fun toJson(): String = JSON.encodeToString(serializer(), this)

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true; prettyPrint = true }
        fun fromJson(text: String): SnapshotSet = JSON.decodeFromString(serializer(), text)
    }
}
```

- [x] **Step 6: Run the tests and confirm they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "*CameraSnapshotTest*"`
Expected: PASS, 2 tests.

- [x] **Step 7: Commit**

```bash
git add app/build.gradle.kts app/src/main/java/com/shez/rawcam/camera/CameraSnapshot.kt app/src/test/java/com/shez/rawcam/camera/CameraSnapshotTest.kt
git commit -m "feat: add CameraSnapshot data model and JVM test infrastructure"
```

---

### Task 2: `DeviceProfile` result types + hard-requirement discovery

**Files:**
- Create: `app/src/main/java/com/shez/rawcam/camera/DeviceProfile.kt`
- Create: `app/src/main/java/com/shez/rawcam/camera/LensDiscovery.kt`
- Test: `app/src/test/java/com/shez/rawcam/camera/LensDiscoveryHardRequirementsTest.kt`

**Interfaces:**
- Consumes: `CameraSnapshot`, `SizeSpec`, `RectSpec` (Task 1).
- Produces: `LensDiscovery.discover(cameras: List<CameraSnapshot>): DeviceProfile`; `DeviceProfile.Supported(lenses, mainIndex, notes)`; `DeviceProfile.Unsupported(reason, detail, notes)`; `LensProfile`; `LensSizeProfile`; `ProfileNote(cameraId, accepted, message)`; `ControlTier.FULL|AUTO_ONLY`; `UnsupportedReason`; `SnapshotField`. Also the shared test helper `rawLens(id, cfa, white, black, sizes)`.

- [x] **Step 1: Write the failing tests**

Create `app/src/test/java/com/shez/rawcam/camera/LensDiscoveryHardRequirementsTest.kt`:

```kotlin
package com.shez.rawcam.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** RAW=3 and MANUAL_SENSOR=1 in REQUEST_AVAILABLE_CAPABILITIES. */
private const val CAP_MANUAL = 1
private const val CAP_RAW = 3

/** Shared builder for a healthy back RAW lens; other test classes reuse it. */
fun rawLens(
    id: String,
    cfa: Int? = 0,
    white: Int? = 1023,
    black: List<Int>? = listOf(64, 64, 64, 64),
    sizes: List<SizeSpec> = listOf(SizeSpec(4096, 3072, 41_666_666L)),
) = CameraSnapshot(
    cameraId = id, facing = 1, capabilities = listOf(CAP_MANUAL, CAP_RAW),
    rawSizes = sizes, cfa = cfa, whiteLevel = white, blackLevel = black,
    focalLengthsMm = listOf(6.9f), physicalSizeMm = listOf(9.8f, 7.3f),
    isoRange = listOf(50, 3200), activeArray = RectSpec(0, 0, 4096, 3072),
)

class LensDiscoveryHardRequirementsTest {

    @Test
    fun `no cameras at all is unsupported, not a crash`() {
        val result = LensDiscovery.discover(emptyList())
        assertEquals(UnsupportedReason.NO_BACK_CAMERA, (result as DeviceProfile.Unsupported).reason)
    }

    @Test
    fun `back camera without RAW capability is unsupported`() {
        val cam = rawLens("0").copy(capabilities = listOf(CAP_MANUAL))
        val result = LensDiscovery.discover(listOf(cam))
        assertEquals(UnsupportedReason.NO_RAW_CAPABILITY, (result as DeviceProfile.Unsupported).reason)
    }

    @Test
    fun `RAW capability but zero RAW sizes is unsupported`() {
        val result = LensDiscovery.discover(listOf(rawLens("0", sizes = emptyList())))
        assertEquals(UnsupportedReason.NO_USABLE_RAW_SIZES, (result as DeviceProfile.Unsupported).reason)
    }

    @Test
    fun `all-null characteristics reads as permission redacted`() {
        val redacted = CameraSnapshot(cameraId = "0", facing = null, capabilities = emptyList())
        val result = LensDiscovery.discover(listOf(redacted))
        assertEquals(UnsupportedReason.PERMISSION_REDACTED, (result as DeviceProfile.Unsupported).reason)
    }

    @Test
    fun `missing CFA rejects that lens but keeps a valid sibling`() {
        val result = LensDiscovery.discover(listOf(rawLens("0"), rawLens("2", cfa = null)))
        val ok = result as DeviceProfile.Supported
        assertEquals(listOf("0"), ok.lenses.map { it.cameraId })
        assertTrue(ok.notes.any { it.cameraId == "2" && !it.accepted && it.message.contains("CFA") })
    }

    @Test
    fun `missing black level rejects that lens`() {
        val result = LensDiscovery.discover(listOf(rawLens("0"), rawLens("2", black = null)))
        assertEquals(listOf("0"), (result as DeviceProfile.Supported).lenses.map { it.cameraId })
    }

    @Test
    fun `front cameras are ignored entirely`() {
        val front = rawLens("1").copy(facing = 0)
        val result = LensDiscovery.discover(listOf(rawLens("0"), front))
        assertEquals(listOf("0"), (result as DeviceProfile.Supported).lenses.map { it.cameraId })
    }
}
```

- [x] **Step 2: Run and confirm failure**

Run: `./gradlew :app:testDebugUnitTest --tests "*LensDiscoveryHardRequirements*"`
Expected: FAIL — `Unresolved reference: LensDiscovery`.

- [x] **Step 3: Implement `DeviceProfile.kt`**

```kotlin
package com.shez.rawcam.camera

/** Which soft field was substituted with a default; surfaced in the report. */
enum class SnapshotField {
    FOCAL_LENGTH, PHYSICAL_SIZE, COLOR_TRANSFORM1, COLOR_TRANSFORM2,
    ILLUMINANTS, ISO_RANGE, EXPOSURE_RANGE, ACTIVE_ARRAY, MIN_FOCUS, OIS_MODES,
}

/** How much manual control a lens actually offers. */
enum class ControlTier { FULL, AUTO_ONLY }

enum class UnsupportedReason {
    NO_BACK_CAMERA, NO_RAW_CAPABILITY, NO_USABLE_RAW_SIZES, PERMISSION_REDACTED,
}

/** One accept/reject decision with its reason. Feeds logcat, the in-app
 * compatibility report, and (Spec C) the upload payload. */
data class ProfileNote(val cameraId: String, val accepted: Boolean, val message: String)

/** One selectable RAW output size of a lens. */
data class LensSizeProfile(val width: Int, val height: Int, val maxFps: Int, val label: String)

/**
 * A back-facing lens RawCam can record from. Replaces CameraController.LensInfo.
 * [cameraId] keeps the exact meaning LensInfo.physicalId had: the per-lens WB
 * identity key. [standalone] means it must be opened as its own top-level
 * CameraDevice rather than tagged onto the primary logical camera.
 */
data class LensProfile(
    val cameraId: String,
    val label: String,
    val focalMm: Float?,
    val equivFocalMm: Float?,
    val fovMetric: Float,
    val sizes: List<LensSizeProfile>,
    val cfa: Int,
    val whiteLevel: Int,
    val blackLevel: IntArray,
    val colorMatrix1: FloatArray,
    val colorMatrix2: FloatArray?,
    val illuminant1: Int?,
    val illuminant2: Int?,
    val isoRange: IntRange,
    val exposureRangeNs: LongRange?,
    val minFocusDiopters: Float,
    val activeArray: RectSpec,
    val oisModes: IntArray?,
    val sensorOrientation: Int?,
    val standalone: Boolean,
    val isMain: Boolean,
    val controlTier: ControlTier,
    val defaulted: Set<SnapshotField>,
) {
    // Arrays break generated data-class equality (identity comparison); compare
    // by identity fields only, since golden tests compare LensProfile instances.
    override fun equals(other: Any?): Boolean =
        other is LensProfile && other.cameraId == cameraId && other.label == label &&
            other.controlTier == controlTier && other.defaulted == defaulted &&
            other.standalone == standalone && other.isMain == isMain
    override fun hashCode(): Int = cameraId.hashCode() * 31 + label.hashCode()
}

sealed interface DeviceProfile {
    val notes: List<ProfileNote>

    data class Supported(
        val lenses: List<LensProfile>,
        val mainIndex: Int,
        override val notes: List<ProfileNote>,
    ) : DeviceProfile

    data class Unsupported(
        val reason: UnsupportedReason,
        val detail: String,
        override val notes: List<ProfileNote>,
    ) : DeviceProfile
}
```

- [x] **Step 4: Implement `LensDiscovery.kt` (hard requirements only)**

```kotlin
package com.shez.rawcam.camera

/**
 * Pure enumeration: plain snapshots in, a DeviceProfile out. Contains no
 * android.* imports by design, so every branch is reachable from a JVM unit test
 * using a JSON fixture captured from a real phone.
 *
 * CONTRACT: this function never throws, for any input. Absence of a field is
 * data, not an error. Enforced by LensDiscoveryFuzzTest.
 */
object LensDiscovery {

    private const val CAP_MANUAL_SENSOR = 1
    private const val CAP_RAW = 3
    private const val FACING_BACK = 1

    fun discover(cameras: List<CameraSnapshot>): DeviceProfile {
        val notes = mutableListOf<ProfileNote>()

        if (cameras.isEmpty()) {
            return DeviceProfile.Unsupported(
                UnsupportedReason.NO_BACK_CAMERA, "No cameras reported by the system.", notes,
            )
        }

        // Every characteristic blank across every camera is the signature of an
        // app without CAMERA permission: Android redacts the keys rather than
        // failing the query. Distinguishing this from genuinely poor hardware
        // matters, because the fix (grant permission) is entirely different.
        if (cameras.all { it.facing == null && it.capabilities.isEmpty() && it.rawSizes.isEmpty() }) {
            return DeviceProfile.Unsupported(
                UnsupportedReason.PERMISSION_REDACTED,
                "Camera details are hidden until camera permission is granted.", notes,
            )
        }

        val back = cameras.filter { it.facing == FACING_BACK }
        if (back.isEmpty()) {
            return DeviceProfile.Unsupported(
                UnsupportedReason.NO_BACK_CAMERA, "This device reports no back-facing camera.", notes,
            )
        }

        if (back.none { it.capabilities.contains(CAP_RAW) }) {
            return DeviceProfile.Unsupported(
                UnsupportedReason.NO_RAW_CAPABILITY,
                "This phone's cameras don't provide RAW capture, which RawCam requires.", notes,
            )
        }

        val built = back.mapNotNull { buildLens(it, notes) }
        if (built.isEmpty()) {
            return DeviceProfile.Unsupported(
                UnsupportedReason.NO_USABLE_RAW_SIZES,
                "RAW is advertised but no camera offers a usable RAW image size.", notes,
            )
        }
        return DeviceProfile.Supported(built, mainIndex = 0, notes = notes)
    }

    /** Null when a HARD requirement is missing; a note records why. */
    private fun buildLens(cam: CameraSnapshot, notes: MutableList<ProfileNote>): LensProfile? {
        fun reject(why: String): LensProfile? {
            notes += ProfileNote(cam.cameraId, accepted = false, message = why)
            return null
        }
        if (!cam.capabilities.contains(CAP_RAW)) return reject("no RAW capability")
        if (cam.rawSizes.isEmpty()) return reject("no RAW output sizes")
        val cfa = cam.cfa ?: return reject("no CFA (colour filter arrangement)")
        val white = cam.whiteLevel ?: return reject("no white level")
        val black = cam.blackLevel?.takeIf { it.size == 4 } ?: return reject("no 4-entry black level")

        notes += ProfileNote(cam.cameraId, accepted = true, message = "accepted")
        return LensProfile(
            cameraId = cam.cameraId, label = cam.cameraId, focalMm = cam.focalLengthsMm?.firstOrNull(),
            equivFocalMm = null, fovMetric = 0f,
            sizes = cam.rawSizes.map {
                LensSizeProfile(it.width, it.height,
                    maxFps = if (it.minFrameDurationNs > 0) (1e9 / it.minFrameDurationNs).toInt() else 30,
                    label = "")
            },
            cfa = cfa, whiteLevel = white, blackLevel = black.toIntArray(),
            colorMatrix1 = FloatArray(9), colorMatrix2 = null,
            illuminant1 = cam.illuminant1, illuminant2 = cam.illuminant2,
            isoRange = 50..800, exposureRangeNs = null, minFocusDiopters = 0f,
            activeArray = cam.activeArray ?: RectSpec(0, 0, cam.rawSizes[0].width, cam.rawSizes[0].height),
            oisModes = cam.oisModes?.toIntArray(), sensorOrientation = cam.sensorOrientation,
            standalone = cam.standalone, isMain = false,
            controlTier = if (cam.capabilities.contains(CAP_MANUAL_SENSOR)) ControlTier.FULL else ControlTier.AUTO_ONLY,
            defaulted = emptySet(),
        )
    }
}
```

- [x] **Step 5: Run and confirm all 7 tests pass**

Run: `./gradlew :app:testDebugUnitTest --tests "*LensDiscoveryHardRequirements*"`
Expected: PASS, 7 tests.

- [x] **Step 6: Commit**

```bash
git add app/src/main/java/com/shez/rawcam/camera/DeviceProfile.kt app/src/main/java/com/shez/rawcam/camera/LensDiscovery.kt app/src/test/java/com/shez/rawcam/camera/LensDiscoveryHardRequirementsTest.kt
git commit -m "feat: add DeviceProfile result types and hard-requirement lens discovery"
```

---

### Task 3: Soft-field defaulting + never-throws fuzz invariant

**Files:**
- Modify: `app/src/main/java/com/shez/rawcam/camera/LensDiscovery.kt` (replace `buildLens`, add `sizeLabel`)
- Test: `app/src/test/java/com/shez/rawcam/camera/LensDiscoverySoftFieldsTest.kt`
- Test: `app/src/test/java/com/shez/rawcam/camera/LensDiscoveryFuzzTest.kt`

**Interfaces:**
- Consumes: `rawLens` helper and everything from Task 2.
- Produces: populated `LensProfile.defaulted`, real `colorMatrix1`, real `isoRange`, `exposureRangeNs`, `minFocusDiopters`, `sizes[].label`, and `ControlTier` demotion when the ISO range is absent.

- [x] **Step 1: Write the failing soft-field tests**

Create `app/src/test/java/com/shez/rawcam/camera/LensDiscoverySoftFieldsTest.kt`:

```kotlin
package com.shez.rawcam.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LensDiscoverySoftFieldsTest {

    private fun onlyLens(cam: CameraSnapshot): LensProfile =
        (LensDiscovery.discover(listOf(cam)) as DeviceProfile.Supported).lenses.single()

    @Test
    fun `missing colour matrix keeps the lens and flags it defaulted`() {
        val lens = onlyLens(rawLens("0").copy(colorTransform1 = null))
        assertTrue(SnapshotField.COLOR_TRANSFORM1 in lens.defaulted)
        assertEquals(9, lens.colorMatrix1.size)
    }

    @Test
    fun `real colour matrix is passed through untouched and not flagged`() {
        val m = listOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        val lens = onlyLens(rawLens("0").copy(colorTransform1 = m))
        assertTrue(SnapshotField.COLOR_TRANSFORM1 !in lens.defaulted)
        assertEquals(1f, lens.colorMatrix1[0], 0f)
    }

    @Test
    fun `missing physical size keeps the lens, drops the 35mm equivalent`() {
        val lens = onlyLens(rawLens("0").copy(physicalSizeMm = null))
        assertTrue(SnapshotField.PHYSICAL_SIZE in lens.defaulted)
        assertEquals(null, lens.equivFocalMm)
    }

    @Test
    fun `missing ISO range demotes the lens to AUTO_ONLY`() {
        val lens = onlyLens(rawLens("0").copy(isoRange = null))
        assertEquals(ControlTier.AUTO_ONLY, lens.controlTier)
        assertTrue(SnapshotField.ISO_RANGE in lens.defaulted)
    }

    @Test
    fun `missing MANUAL_SENSOR capability is AUTO_ONLY but still records`() {
        val lens = onlyLens(rawLens("0").copy(capabilities = listOf(3)))
        assertEquals(ControlTier.AUTO_ONLY, lens.controlTier)
    }

    @Test
    fun `missing exposure range does NOT demote the tier`() {
        val lens = onlyLens(rawLens("0").copy(exposureRangeNs = null))
        assertEquals(ControlTier.FULL, lens.controlTier)
        assertEquals(null, lens.exposureRangeNs)
    }

    @Test
    fun `present exposure range is exposed as a LongRange`() {
        val lens = onlyLens(rawLens("0").copy(exposureRangeNs = listOf(1000L, 500_000_000L)))
        assertEquals(1000L..500_000_000L, lens.exposureRangeNs)
    }

    @Test
    fun `missing active array falls back to the largest RAW size`() {
        val lens = onlyLens(rawLens("0").copy(activeArray = null))
        assertEquals(4096, lens.activeArray.width)
        assertNotNull(lens.activeArray)
    }
}
```

- [x] **Step 2: Write the never-throws fuzz test**

Create `app/src/test/java/com/shez/rawcam/camera/LensDiscoveryFuzzTest.kt`:

```kotlin
package com.shez.rawcam.camera

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The single most important test in this module. Spec A's floor is "never
 * crash"; this is what actually enforces it. Any input at all -- absurd values,
 * every field null, contradictory capabilities -- must yield Supported or
 * Unsupported, never an exception.
 */
class LensDiscoveryFuzzTest {

    private fun randomSnapshot(r: Random): CameraSnapshot {
        fun <T> maybe(v: T): T? = if (r.nextBoolean()) v else null
        val absurd = listOf(0, -1, Int.MAX_VALUE, Int.MIN_VALUE, 1023, 4095)
        return CameraSnapshot(
            cameraId = listOf("0", "2", "semt0", "", "999").random(r),
            facing = maybe(listOf(0, 1, 2, -7).random(r)),
            capabilities = List(r.nextInt(0, 5)) { r.nextInt(-2, 12) },
            physicalIds = List(r.nextInt(0, 3)) { r.nextInt(0, 40).toString() },
            rawSizes = List(r.nextInt(0, 4)) {
                SizeSpec(r.nextInt(-8, 9000), r.nextInt(-8, 9000), r.nextLong(-5, 1_000_000_000))
            },
            cfa = maybe(r.nextInt(-3, 9)),
            whiteLevel = maybe(absurd.random(r)),
            blackLevel = maybe(List(r.nextInt(0, 6)) { absurd.random(r) }),
            focalLengthsMm = maybe(List(r.nextInt(0, 3)) { r.nextFloat() * 200 - 100 }),
            physicalSizeMm = maybe(List(r.nextInt(0, 4)) { r.nextFloat() * 20 - 10 }),
            colorTransform1 = maybe(List(r.nextInt(0, 12)) { r.nextFloat() * 4 - 2 }),
            colorTransform2 = maybe(List(r.nextInt(0, 12)) { r.nextFloat() * 4 - 2 }),
            illuminant1 = maybe(r.nextInt(-5, 30)),
            illuminant2 = maybe(r.nextInt(-5, 30)),
            isoRange = maybe(List(r.nextInt(0, 4)) { absurd.random(r) }),
            exposureRangeNs = maybe(List(r.nextInt(0, 4)) { r.nextLong(-10, Long.MAX_VALUE / 2) }),
            activeArray = maybe(RectSpec(r.nextInt(-9, 9), r.nextInt(-9, 9), r.nextInt(-9, 9000), r.nextInt(-9, 9000))),
            minFocusDiopters = maybe(r.nextFloat() * 100 - 50),
            oisModes = maybe(List(r.nextInt(0, 4)) { r.nextInt(-2, 5) }),
            sensorOrientation = maybe(listOf(0, 90, 180, 270, 45, -90).random(r)),
            standalone = r.nextBoolean(),
        )
    }

    @Test
    fun `discover never throws for any input`() {
        val r = Random(20260722)
        repeat(5000) { iteration ->
            val cams = List(r.nextInt(0, 6)) { randomSnapshot(r) }
            val result = try {
                LensDiscovery.discover(cams)
            } catch (t: Throwable) {
                throw AssertionError("discover() threw on iteration $iteration: $cams", t)
            }
            assertTrue(result is DeviceProfile.Supported || result is DeviceProfile.Unsupported)
        }
    }

    @Test
    fun `a supported result always has a valid mainIndex`() {
        val r = Random(19700101)
        repeat(3000) {
            val result = LensDiscovery.discover(List(r.nextInt(0, 6)) { randomSnapshot(r) })
            if (result is DeviceProfile.Supported) {
                assertTrue(result.lenses.isNotEmpty())
                assertTrue(result.mainIndex in result.lenses.indices)
            }
        }
    }
}
```

- [x] **Step 3: Run both and confirm failure**

Run: `./gradlew :app:testDebugUnitTest --tests "*LensDiscoverySoftFields*" --tests "*LensDiscoveryFuzz*"`
Expected: FAIL — soft-field assertions fail, and the fuzz test is a real crash candidate (`rawSizes[0]` on a zero-width size, `IntRange` from a malformed `isoRange`).

- [x] **Step 4: Replace `buildLens` in `LensDiscovery.kt` and add `sizeLabel`**

```kotlin
    /** Full-frame diagonal, for the 35mm-equivalent crop-factor formula. */
    private const val FULL_FRAME_DIAGONAL_MM = 43.27

    /** Fallback when a sensor exposes no colour calibration: identity. A DNG
     * with an identity ColorMatrix1 still opens and still grades; the lens is
     * flagged uncalibrated in the compatibility report rather than dropped. */
    private val IDENTITY_MATRIX = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)

    private const val DEFAULT_ISO_LOW = 50
    private const val DEFAULT_ISO_HIGH = 800

    /** Null when a HARD requirement is missing; a note records why. Every SOFT
     * field falls back to a documented default and is recorded in [defaulted]. */
    private fun buildLens(cam: CameraSnapshot, notes: MutableList<ProfileNote>): LensProfile? {
        fun reject(why: String): LensProfile? {
            notes += ProfileNote(cam.cameraId, accepted = false, message = why)
            return null
        }
        if (!cam.capabilities.contains(CAP_RAW)) return reject("no RAW capability")
        val validSizes = cam.rawSizes.filter { it.width > 0 && it.height > 0 }
        if (validSizes.isEmpty()) return reject("no RAW output sizes")
        val cfa = cam.cfa?.takeIf { it in 0..3 } ?: return reject("no usable CFA")
        val white = cam.whiteLevel?.takeIf { it > 0 } ?: return reject("no white level")
        val black = cam.blackLevel?.takeIf { it.size == 4 } ?: return reject("no 4-entry black level")

        val defaulted = mutableSetOf<SnapshotField>()

        val focal = cam.focalLengthsMm?.firstOrNull()?.takeIf { it > 0f }
        if (focal == null) defaulted += SnapshotField.FOCAL_LENGTH

        val physSize = cam.physicalSizeMm?.takeIf { it.size == 2 && it[0] > 0f && it[1] > 0f }
        if (physSize == null) defaulted += SnapshotField.PHYSICAL_SIZE

        // 35mm equivalent = real focal * (full-frame diagonal / this sensor's own
        // measured diagonal). Requires BOTH inputs; null otherwise, and the label
        // falls back to an ordinal (assigned in finishLenses).
        val equivFocal = if (focal != null && physSize != null) {
            val diag = Math.sqrt((physSize[0] * physSize[0] + physSize[1] * physSize[1]).toDouble())
            if (diag > 0) (focal * (FULL_FRAME_DIAGONAL_MM / diag)).toFloat() else null
        } else null

        val cm1 = cam.colorTransform1?.takeIf { it.size == 9 }?.toFloatArray()
            ?: IDENTITY_MATRIX.copyOf().also { defaulted += SnapshotField.COLOR_TRANSFORM1 }
        val cm2 = cam.colorTransform2?.takeIf { it.size == 9 }?.toFloatArray()
        if (cm2 == null) defaulted += SnapshotField.COLOR_TRANSFORM2
        if (cam.illuminant1 == null) defaulted += SnapshotField.ILLUMINANTS

        // A malformed range (wrong arity, inverted, non-positive) is treated as
        // absent rather than coerced -- guessing a sensitivity range would put
        // wrong numbers on a slider the user trusts.
        val iso = cam.isoRange?.takeIf { it.size == 2 && it[0] > 0 && it[1] >= it[0] }
            ?.let { it[0]..it[1] }
        if (iso == null) defaulted += SnapshotField.ISO_RANGE

        val exposure = cam.exposureRangeNs?.takeIf { it.size == 2 && it[0] > 0 && it[1] >= it[0] }
            ?.let { it[0]..it[1] }
        if (exposure == null) defaulted += SnapshotField.EXPOSURE_RANGE

        val largest = validSizes.maxBy { it.width.toLong() * it.height }
        val activeArray = cam.activeArray?.takeIf { it.width > 0 && it.height > 0 }
            ?: RectSpec(0, 0, largest.width, largest.height).also { defaulted += SnapshotField.ACTIVE_ARRAY }

        val minFocus = cam.minFocusDiopters?.takeIf { it >= 0f }
            ?: 0f.also { defaulted += SnapshotField.MIN_FOCUS }
        if (cam.oisModes == null) defaulted += SnapshotField.OIS_MODES

        // FULL requires the MANUAL_SENSOR capability AND a usable ISO range: a
        // manual ISO slider with no real bounds would be a lie. A missing
        // exposure range does NOT demote -- it only means the shutter stop table
        // cannot be intersected (see Task 9).
        val tier = if (cam.capabilities.contains(CAP_MANUAL_SENSOR) && iso != null)
            ControlTier.FULL else ControlTier.AUTO_ONLY

        val maxArea = largest.width.toLong() * largest.height
        notes += ProfileNote(
            cam.cameraId, accepted = true,
            message = if (defaulted.isEmpty()) "accepted"
            else "accepted; defaulted " + defaulted.joinToString(", ") { it.name.lowercase() },
        )
        return LensProfile(
            cameraId = cam.cameraId, label = "", focalMm = focal, equivFocalMm = equivFocal,
            fovMetric = if (physSize != null && focal != null) physSize[0] / focal else 0f,
            sizes = validSizes.sortedByDescending { it.width.toLong() * it.height }.map {
                LensSizeProfile(
                    it.width, it.height,
                    maxFps = if (it.minFrameDurationNs > 0)
                        (1e9 / it.minFrameDurationNs).toInt().coerceIn(1, 240) else 30,
                    label = sizeLabel(it.width, it.height, maxArea),
                )
            },
            cfa = cfa, whiteLevel = white, blackLevel = black.toIntArray(),
            colorMatrix1 = cm1, colorMatrix2 = cm2,
            illuminant1 = cam.illuminant1, illuminant2 = cam.illuminant2,
            isoRange = iso ?: (DEFAULT_ISO_LOW..DEFAULT_ISO_HIGH),
            exposureRangeNs = exposure, minFocusDiopters = minFocus, activeArray = activeArray,
            oisModes = cam.oisModes?.toIntArray(), sensorOrientation = cam.sensorOrientation,
            standalone = cam.standalone, isMain = false, controlTier = tier, defaulted = defaulted,
        )
    }

    /** "4:3" / "16:9" for full-area sizes, "LOW" for binned. Moved verbatim from
     * CameraController.sizeLabel so behaviour on existing devices is unchanged. */
    private fun sizeLabel(w: Int, h: Int, maxArea: Long): String {
        if (w.toLong() * h < maxArea / 2) return "LOW"
        val aspect = w.toFloat() / h
        return when {
            Math.abs(aspect - 4f / 3f) < 0.05f -> "4:3"
            Math.abs(aspect - 16f / 9f) < 0.1f -> "16:9"
            else -> "${h}p"
        }
    }
```

- [x] **Step 5: Run and confirm all pass**

Run: `./gradlew :app:testDebugUnitTest --tests "*LensDiscovery*"`
Expected: PASS — 7 hard-requirement + 8 soft-field + 2 fuzz tests.

- [x] **Step 6: Commit**

```bash
git add app/src/main/java/com/shez/rawcam/camera/LensDiscovery.kt app/src/test/java/com/shez/rawcam/camera/
git commit -m "feat: soft-field defaulting and never-throws fuzz invariant for lens discovery"
```

---

### Task 4: Lens ordering, labels, and main-lens selection

**Files:**
- Modify: `app/src/main/java/com/shez/rawcam/camera/LensDiscovery.kt` (tail of `discover`, add `finishLenses`)
- Test: `app/src/test/java/com/shez/rawcam/camera/LensOrderingTest.kt`

**Interfaces:**
- Consumes: `buildLens` from Task 3.
- Produces: `discover()` returns lenses sorted widest-first, deduped by focal length, labelled (`"23mm"` or ordinal `"LENS 2"`), with a correct `mainIndex` and exactly one `isMain`.

- [x] **Step 1: Write the failing tests**

Create `app/src/test/java/com/shez/rawcam/camera/LensOrderingTest.kt`:

```kotlin
package com.shez.rawcam.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LensOrderingTest {

    private fun lensWith(id: String, focalMm: Float, physW: Float = 9.8f) =
        rawLens(id).copy(focalLengthsMm = listOf(focalMm), physicalSizeMm = listOf(physW, 7.3f))

    private fun supported(vararg cams: CameraSnapshot) =
        LensDiscovery.discover(cams.toList()) as DeviceProfile.Supported

    @Test
    fun `lenses sort widest first`() {
        val r = supported(lensWith("4", 12.28f), lensWith("0", 6.9f), lensWith("3", 2.2f))
        assertEquals(listOf("3", "0", "4"), r.lenses.map { it.cameraId })
    }

    @Test
    fun `duplicate focal lengths dedupe, keeping the id with more sizes`() {
        val few = lensWith("0", 6.9f)
        val many = lensWith("7", 6.9f).copy(
            rawSizes = listOf(SizeSpec(4096, 3072, 41_666_666L), SizeSpec(2048, 1536, 20_000_000L)),
        )
        val r = supported(few, many)
        assertEquals(listOf("7"), r.lenses.map { it.cameraId })
    }

    @Test
    fun `labels use the 35mm equivalent`() {
        val r = supported(lensWith("0", 6.9f))
        assertTrue(r.lenses.single().label.endsWith("mm"))
    }

    @Test
    fun `lenses without focal data get a 1-based ordinal label over the sorted list`() {
        val noFocal = rawLens("0").copy(focalLengthsMm = null, physicalSizeMm = null)
        val r = supported(noFocal)
        assertEquals("LENS 1", r.lenses.single().label)
    }

    @Test
    fun `exactly one lens is main and mainIndex points at it`() {
        val r = supported(lensWith("3", 2.2f), lensWith("0", 6.9f), lensWith("4", 12.28f))
        assertEquals(1, r.lenses.count { it.isMain })
        assertTrue(r.lenses[r.mainIndex].isMain)
    }

    @Test
    fun `with no focal data at all mainIndex still resolves to a real lens`() {
        val a = rawLens("0").copy(focalLengthsMm = null, physicalSizeMm = null)
        val b = rawLens("2").copy(focalLengthsMm = null, physicalSizeMm = null,
            rawSizes = listOf(SizeSpec(2048, 1536, 41_666_666L)))
        val r = supported(a, b)
        assertTrue(r.mainIndex in r.lenses.indices)
        assertEquals(1, r.lenses.count { it.isMain })
    }
}
```

- [x] **Step 2: Run and confirm failure**

Run: `./gradlew :app:testDebugUnitTest --tests "*LensOrderingTest*"`
Expected: FAIL — labels empty, no sorting, `mainIndex` always 0.

- [x] **Step 3: Replace the tail of `discover()` and add `finishLenses`**

Replace the `return DeviceProfile.Supported(built, mainIndex = 0, notes = notes)` line with:

```kotlin
        val (lenses, mainIndex) = finishLenses(built, back)
        return DeviceProfile.Supported(lenses, mainIndex, notes)
    }

    /**
     * Dedupe by focal length (one sensor exposed under two ids -- keep the id
     * offering more sizes), sort widest-first, label, and choose the main lens.
     *
     * Main-lens selection must never fail, because isMain drives which lens the
     * app opens at launch: advertised focal length of the logical camera ->
     * nearest match; no focal data -> largest active array; still ambiguous ->
     * index 0. The old code fell through to 0 silently, which on a device
     * without focal lengths meant launching on the wrong lens.
     */
    private fun finishLenses(
        built: List<LensProfile>, sources: List<CameraSnapshot>,
    ): Pair<List<LensProfile>, Int> {
        val deduped = built
            .groupBy { it.focalMm }
            .map { (focal, group) -> if (focal == null) group else listOf(group.maxBy { it.sizes.size }) }
            .flatten()
            .sortedWith(compareByDescending<LensProfile> { it.fovMetric }.thenBy { it.cameraId })

        val logicalFocal = sources.firstOrNull { it.physicalIds.isNotEmpty() }
            ?.focalLengthsMm?.firstOrNull()
        val withFocal = deduped.indices.filter { deduped[it].focalMm != null }
        val mainIndex = when {
            logicalFocal != null && withFocal.isNotEmpty() ->
                withFocal.minBy { Math.abs(deduped[it].focalMm!! - logicalFocal) }
            withFocal.isNotEmpty() ->
                withFocal.maxBy { deduped[it].fovMetric.toDouble() }
            else -> deduped.indices.maxBy {
                deduped[it].activeArray.width.toLong() * deduped[it].activeArray.height
            }
        }

        val labelled = deduped.mapIndexed { i, lens ->
            val label = lens.equivFocalMm?.let { String.format(java.util.Locale.US, "%.0fmm", it) }
                ?: lens.focalMm?.let { String.format(java.util.Locale.US, "%.1fmm", it) }
                ?: "LENS ${i + 1}"
            lens.copy(label = label, isMain = i == mainIndex)
        }
        return labelled to mainIndex
    }
```

- [x] **Step 4: Run and confirm pass**

Run: `./gradlew :app:testDebugUnitTest --tests "*LensDiscovery*" --tests "*LensOrderingTest*"`
Expected: PASS — all tests, including the fuzz invariant which now also exercises `finishLenses`.

- [x] **Step 5: Commit**

```bash
git add app/src/main/java/com/shez/rawcam/camera/LensDiscovery.kt app/src/test/java/com/shez/rawcam/camera/LensOrderingTest.kt
git commit -m "feat: lens ordering, dedupe, labelling and never-failing main-lens selection"
```

---

### Task 5: `Camera2SnapshotSource` — the Android adapter and widened probe

**Files:**
- Create: `app/src/main/java/com/shez/rawcam/camera/Camera2SnapshotSource.kt`
- Test: none — this is the untestable-by-design Android boundary, verified on-device in Task 6

**Interfaces:**
- Consumes: `CameraSnapshot`, `SizeSpec`, `RectSpec`, `SnapshotSet` (Task 1).
- Produces: `Camera2SnapshotSource(cameraManager: CameraManager).capture(): SnapshotSet`.

- [x] **Step 1: Implement the source**

```kotlin
package com.shez.rawcam.camera

import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.ColorSpaceTransform
import android.os.Build
import android.os.SystemClock
import android.util.Log

/**
 * The ONLY place in the app that calls CameraCharacteristics.get(). Converts
 * Camera2's object graph into plain [CameraSnapshot] data so [LensDiscovery]
 * can stay pure and unit-testable.
 *
 * Every read is individually null-tolerant: a HAL may omit any key, and Android
 * redacts most of them for an app without CAMERA permission. Nothing here throws
 * on a missing key -- absence is passed through as null and classified
 * downstream.
 *
 * MUST be called off the main thread: getCameraCharacteristics is binder IPC,
 * once per camera id.
 */
class Camera2SnapshotSource(private val cameraManager: CameraManager) {

    fun capture(): SnapshotSet {
        val listed = try {
            cameraManager.cameraIdList.toList()
        } catch (e: Exception) {
            Log.w(TAG, "cameraIdList unavailable", e)
            emptyList()
        }

        val children = listed.flatMap { id ->
            try {
                cameraManager.getCameraCharacteristics(id).physicalCameraIds.toList()
            } catch (e: Exception) {
                emptyList()
            }
        }

        val known = (listed + children).toSet()
        val snapshots = mutableListOf<CameraSnapshot>()
        for (id in known) {
            snapshotOf(id, standalone = false)?.let { snapshots += it }
        }
        snapshots += probeHidden(known)

        return SnapshotSet(model = Build.MODEL, sdkInt = Build.VERSION.SDK_INT, cameras = snapshots)
    }

    /**
     * Probes ids the OS hides from both cameraIdList and every logical camera's
     * physicalCameraIds -- observed on MIUI/HyperOS (Xiaomi 14 Ultra ids "4"/"5",
     * the 3.2x telephoto and 5x periscope), which report full RAW support and
     * open successfully as standalone CameraDevices.
     *
     * Widened from the previous 0..15 to 0..31 because Samsung is known to use
     * higher ids. Bounded by a wall-clock budget so a slow vendor HAL cannot
     * stall app launch: a device that answers slowly gets fewer probes, never a
     * frozen startup.
     *
     * KNOWN LIMITATION: only decimal ids are reachable. A vendor using
     * non-numeric ids is undiscoverable by any scan and needs a Spec C quirks
     * entry.
     */
    private fun probeHidden(exclude: Set<String>): List<CameraSnapshot> {
        val found = mutableListOf<CameraSnapshot>()
        val deadline = SystemClock.elapsedRealtime() + PROBE_BUDGET_MS
        for (i in 0 until HIDDEN_PROBE_RANGE) {
            if (SystemClock.elapsedRealtime() >= deadline) {
                Log.w(TAG, "hidden-lens probe budget exhausted at id $i")
                break
            }
            val id = i.toString()
            if (id in exclude) continue
            snapshotOf(id, standalone = true)?.let { found += it }
        }
        return found
    }

    /** Null only when the id does not exist or is hard-blocked by the HAL. */
    private fun snapshotOf(id: String, standalone: Boolean): CameraSnapshot? {
        val ch = try {
            cameraManager.getCameraCharacteristics(id)
        } catch (e: Exception) {
            // Nonexistent id, or a genuine "system only device" refusal (this
            // device's ids 7/8). Expected during probing; not an error.
            Log.d(TAG, "camera $id unavailable", e)
            return null
        }
        fun <T> read(key: CameraCharacteristics.Key<T>): T? = try {
            ch.get(key)
        } catch (e: Exception) {
            null
        }

        val map = read(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val rawSizes = map?.getOutputSizes(ImageFormat.RAW_SENSOR)?.map { s ->
            SizeSpec(s.width, s.height,
                minFrameDurationNs = try {
                    map.getOutputMinFrameDuration(ImageFormat.RAW_SENSOR, s)
                } catch (e: Exception) { 0L })
        } ?: emptyList()

        val physSize = read(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val black = read(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)
            ?.let { p -> IntArray(4).also { p.copyTo(it, 0) }.toList() }
        // Row-major 3x3: index i -> row i/3, column i%3 (getElement takes column, row).
        fun matrix(k: CameraCharacteristics.Key<ColorSpaceTransform>) =
            read(k)?.let { t -> List(9) { i -> t.getElement(i % 3, i / 3).toFloat() } }
        val iso = read(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        val exp = read(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        val arr = read(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)

        return CameraSnapshot(
            cameraId = id,
            facing = read(CameraCharacteristics.LENS_FACING),
            capabilities = read(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toList() ?: emptyList(),
            hardwareLevel = read(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL),
            physicalIds = try { ch.physicalCameraIds.toList() } catch (e: Exception) { emptyList() },
            rawSizes = rawSizes,
            cfa = read(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT),
            whiteLevel = read(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL),
            blackLevel = black,
            focalLengthsMm = read(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.toList(),
            physicalSizeMm = physSize?.let { listOf(it.width, it.height) },
            colorTransform1 = matrix(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1),
            colorTransform2 = matrix(CameraCharacteristics.SENSOR_COLOR_TRANSFORM2),
            illuminant1 = read(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1)?.toInt(),
            illuminant2 = read(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2)?.toInt(),
            isoRange = iso?.let { listOf(it.lower, it.upper) },
            exposureRangeNs = exp?.let { listOf(it.lower, it.upper) },
            activeArray = arr?.let { RectSpec(it.left, it.top, it.right, it.bottom) },
            minFocusDiopters = read(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE),
            oisModes = read(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)?.toList(),
            sensorOrientation = read(CameraCharacteristics.SENSOR_ORIENTATION),
            standalone = standalone,
        )
    }

    private companion object {
        const val TAG = "Camera2SnapshotSource"
        const val HIDDEN_PROBE_RANGE = 32
        const val PROBE_BUDGET_MS = 400L
    }
}
```

- [x] **Step 2: Confirm it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [x] **Step 3: Commit**

```bash
git add app/src/main/java/com/shez/rawcam/camera/Camera2SnapshotSource.kt
git commit -m "feat: add Camera2SnapshotSource with widened, time-bounded hidden-lens probe"
```

---

### Task 6: Wire `CameraController` to `DeviceProfile` (pure extraction, no behaviour change)

**This is the highest-risk task in the plan.** The goal is *zero* behavioural change on the two owned devices. New capability comes in later tasks; this one only swaps the enumeration engine.

**Files:**
- Modify: `app/src/main/java/com/shez/rawcam/camera/CameraController.kt` — delete `enumerateLenses` (~982-1006), `buildLensCandidate` (~1009-1073), `probeHiddenLenses` (~1089-1103), `sizeLabel` (~1106-1114), and the `LensInfo`/`LensSize` data classes (~70-122); rewrite `initialize()` (~353-374); retype `lenses`, `applySelectedLens`, `specFor`, `wbCalibFor`
- Modify: `app/src/main/java/com/shez/rawcam/ui/RecordScreen.kt:146` — `RecordUiState.lenses` type

**Interfaces:**
- Consumes: `Camera2SnapshotSource.capture()` (Task 5), `LensDiscovery.discover()` (Task 4).
- Produces: `CameraController.profile: DeviceProfile?`; `CameraController.lenses: List<LensProfile>`; `initialize(): DeviceProfile` (returns rather than throws).

- [x] **Step 1: Delete `LensInfo`/`LensSize`, replace usages with `LensProfile`/`LensSizeProfile`**

`LensInfo.physicalId` maps to `LensProfile.cameraId`. **The three-way identity split stays exactly as it is:**

```kotlin
    private fun applySelectedLens(lens: LensProfile, sizeIndex: Int) {
        activePhysicalId = lens.cameraId          // WB identity key -- unchanged meaning
        rawSpec = specFor(lens, sizeIndex)
        activeArraySize = Rect(lens.activeArray.left, lens.activeArray.top,
                               lens.activeArray.right, lens.activeArray.bottom)
        activeOisModes = lens.oisModes
        wbCalib = wbCalibFor(lens)
        if (lens.standalone) {
            activeCameraId = lens.cameraId        // open this id directly
            sessionTagId = null                   // never tag a standalone device
        } else {
            activeCameraId = primaryCameraId
            sessionTagId = lens.cameraId
        }
    }
```

- [x] **Step 2: Rewrite `initialize()` to return a profile instead of throwing**

```kotlin
    /** Populated by [initialize]; null until then. */
    @Volatile var profile: DeviceProfile? = null
        private set

    /**
     * Captures this device's camera characteristics and resolves them into a
     * [DeviceProfile]. Binder IPC per camera id -- MUST run off the main thread.
     *
     * Returns Unsupported rather than throwing. The previous implementation used
     * cameraIdList.first{} and check(deduped.isNotEmpty()), either of which
     * crashed the process on hardware without a RAW back camera (and on any
     * launch before CAMERA permission was granted).
     */
    fun initialize(): DeviceProfile {
        val snapshot = Camera2SnapshotSource(cameraManager).capture()
        val result = LensDiscovery.discover(snapshot.cameras)
        profile = result
        result.notes.forEach { Log.i(TAG, "lens ${it.cameraId}: ${it.message}") }
        if (result !is DeviceProfile.Supported) {
            Log.w(TAG, "device unsupported: ${(result as DeviceProfile.Unsupported).reason}")
            return result
        }
        // The primary logical camera is the parent of every non-standalone lens:
        // the first back camera declaring physical children, else the first back
        // camera, else the first accepted lens.
        primaryCameraId = snapshot.cameras.firstOrNull { it.physicalIds.isNotEmpty() && it.facing == 1 }?.cameraId
            ?: snapshot.cameras.firstOrNull { it.facing == 1 }?.cameraId
            ?: result.lenses.first().cameraId
        lenses = result.lenses
        defaultLensIndex = result.mainIndex
        applySelectedLens(lenses[defaultLensIndex], 0)
        // Permanent cheap sanity line for field debugging (unchanged from before).
        val g2000 = gainsFor(2000, 0); val g5600 = gainsFor(5600, 0); val g10000 = gainsFor(10000, 0)
        Log.i(TAG, "WB gains sanity 2000K=(${g2000.red},${g2000.greenEven},${g2000.blue}) " +
            "5600K=(${g5600.red},${g5600.greenEven},${g5600.blue}) " +
            "10000K=(${g10000.red},${g10000.greenEven},${g10000.blue})")
        return result
    }
```

- [x] **Step 3: Retype `specFor` and `wbCalibFor`**

Only the parameter type changes, from `LensInfo` to `LensProfile`. `specFor` keeps its CCT-sorting logic **verbatim** — that is the DNG illuminant-ordering fix and must not be touched. `lens.colorMatrix2`, `lens.illuminant1`, `lens.illuminant2` read identically on the new type.

- [x] **Step 4: Update `RecordUiState.lenses`**

In `RecordScreen.kt:146`:

```kotlin
    val lenses: List<LensProfile> = emptyList(),
```

Fix the resulting compile errors at the lens-chip render sites by replacing `lens.physicalId` with `lens.cameraId`. `lens.label`, `lens.sizes`, `lens.isMain` keep their names.

- [x] **Step 5: Build and confirm green**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS — all Task 1-4 tests still green.

- [x] **Step 6: On-device regression — the actual gate for this task**

```bash
./gradlew :app:assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
adb logcat -c && adb logcat | grep -i "CameraController\|lens "
```

Use `adb install -r` (not uninstall/reinstall) so existing clips survive — including the preserved telephoto test clip `clip_20260721_220030`.

Xiaomi 14 Ultra — required results:
- LENS panel shows **exactly 4 chips: 12mm / 23mm / 74mm / 117mm**, in that order
- App launches on the **23mm** lens
- Switching 1x→3x→5x→1x gives clean preview, no crash, same process PID throughout
- A record + export on the 3x (standalone) lens succeeds

Pixel 7 Pro — required results:
- LENS panel shows its **2** chips, launches on the main lens
- ISO slider range matches today's (50-3200 on 1x)
- A record + export succeeds

If lens counts, order, labels, or the default lens differ from today, **stop and fix before proceeding** — that is a regression in the extraction, not a new capability.

- [x] **Step 7: Commit**

```bash
git add app/src/main/java/com/shez/rawcam/camera/CameraController.kt app/src/main/java/com/shez/rawcam/ui/RecordScreen.kt
git commit -m "refactor: drive CameraController from LensDiscovery instead of inline enumeration"
```

---

### Task 7: Unsupported-device screen + install unblocking

**Files:**
- Modify: `app/src/main/AndroidManifest.xml:7`
- Create: `app/src/main/java/com/shez/rawcam/camera/CompatibilityReport.kt`
- Modify: `app/src/main/java/com/shez/rawcam/ui/RecordScreen.kt` — `RecordUiState`, `ensureCameraInitialized`, composable gate
- Test: `app/src/test/java/com/shez/rawcam/camera/CompatibilityReportTest.kt`

**Interfaces:**
- Consumes: `DeviceProfile` (Task 2), `CameraController.initialize()` (Task 6).
- Produces: `CompatibilityReport.render(profile: DeviceProfile, model: String, sdkInt: Int): String`; `RecordUiState.unsupported: DeviceProfile.Unsupported?`; `RecordUiState.reportText: String`.

- [x] **Step 1: Unblock installation on non-RAW devices**

In `AndroidManifest.xml`, change line 7:

```xml
    <uses-feature android:name="android.hardware.camera.raw" android:required="false" />
```

Without this, a device with no RAW support cannot install the app at all, so it can never reach the screen this task builds.

- [x] **Step 2: Write the failing test**

Create `app/src/test/java/com/shez/rawcam/camera/CompatibilityReportTest.kt`:

```kotlin
package com.shez.rawcam.camera

import org.junit.Assert.assertTrue
import org.junit.Test

class CompatibilityReportTest {

    @Test
    fun `supported report names every lens and its tier`() {
        val profile = LensDiscovery.discover(listOf(rawLens("0"), rawLens("2").copy(
            focalLengthsMm = listOf(2.2f))))
        val text = CompatibilityReport.render(profile, "Test Device", 34)
        assertTrue(text.contains("SUPPORTED"))
        assertTrue(text.contains("Test Device"))
        assertTrue(text.contains("FULL"))
    }

    @Test
    fun `unsupported report states the reason and the enumeration log`() {
        val profile = LensDiscovery.discover(listOf(rawLens("0").copy(capabilities = listOf(1))))
        val text = CompatibilityReport.render(profile, "Cheap Phone", 33)
        assertTrue(text.contains("NOT SUPPORTED"))
        assertTrue(text.contains("NO_RAW_CAPABILITY"))
    }

    @Test
    fun `defaulted fields are called out explicitly`() {
        val profile = LensDiscovery.discover(listOf(rawLens("0").copy(colorTransform1 = null)))
        val text = CompatibilityReport.render(profile, "Odd Phone", 34)
        assertTrue(text.contains("DEFAULTED"))
        assertTrue(text.contains("COLOR_TRANSFORM1"))
    }
}
```

- [x] **Step 3: Run and confirm failure**

Run: `./gradlew :app:testDebugUnitTest --tests "*CompatibilityReportTest*"`
Expected: FAIL — `Unresolved reference: CompatibilityReport`.

- [x] **Step 4: Implement the renderer**

```kotlin
package com.shez.rawcam.camera

/** Renders a [DeviceProfile] as plain shareable text: what RawCam found on this
 * phone, which lenses it accepted, and why it rejected the rest. Deliberately
 * free of android.* imports so it is unit-testable. */
object CompatibilityReport {

    fun render(profile: DeviceProfile, model: String, sdkInt: Int): String = buildString {
        appendLine("RawCam compatibility report")
        appendLine("Device: $model (Android SDK $sdkInt)")
        appendLine()
        when (profile) {
            is DeviceProfile.Unsupported -> {
                appendLine("RESULT: NOT SUPPORTED (${profile.reason})")
                appendLine(profile.detail)
            }
            is DeviceProfile.Supported -> {
                appendLine("RESULT: SUPPORTED - ${profile.lenses.size} lens(es)")
                profile.lenses.forEachIndexed { i, l ->
                    appendLine()
                    appendLine("[${i + 1}] ${l.label}  id=${l.cameraId}${if (l.isMain) "  (main)" else ""}")
                    appendLine("    control: ${l.controlTier}")
                    appendLine("    sizes:   " + l.sizes.joinToString(", ") { "${it.width}x${it.height}@${it.maxFps}" })
                    appendLine("    ISO:     ${l.isoRange.first}-${l.isoRange.last}")
                    appendLine("    standalone: ${l.standalone}")
                    if (l.defaulted.isNotEmpty()) {
                        appendLine("    DEFAULTED: " + l.defaulted.joinToString(", ") { it.name })
                    }
                }
            }
        }
        appendLine()
        appendLine("Enumeration log:")
        profile.notes.forEach { appendLine("  id ${it.cameraId}: ${if (it.accepted) "OK" else "SKIP"} - ${it.message}") }
    }
}
```

- [x] **Step 5: Run and confirm pass**

Run: `./gradlew :app:testDebugUnitTest --tests "*CompatibilityReportTest*"`
Expected: PASS, 3 tests.

- [x] **Step 6: Surface the unsupported state in `RecordUiState`**

Add to `RecordUiState` (RecordScreen.kt:144):

```kotlin
    val unsupported: DeviceProfile.Unsupported? = null,
    val reportText: String = "",
```

In `ensureCameraInitialized()`, capture `initialize()`'s result and stop early when unsupported:

```kotlin
            val result = controller.initialize()
            val report = CompatibilityReport.render(result, Build.MODEL, Build.VERSION.SDK_INT)
            if (result is DeviceProfile.Unsupported) {
                _uiState.update { it.copy(unsupported = result, reportText = report) }
                return@launch
            }
            _uiState.update { it.copy(reportText = report) }
```

Everything after this in that coroutine (rawSpec publication, WB anchor restore, startup auto-meter) already assumes a working camera and must stay below this guard.

- [x] **Step 7: Render the unsupported screen**

In `RecordScreen`'s composable, ahead of the existing `rawSpec == null` loading gate:

```kotlin
    val unsupported = state.unsupported
    if (unsupported != null) {
        UnsupportedDeviceScreen(
            reason = when (unsupported.reason) {
                UnsupportedReason.NO_RAW_CAPABILITY -> "This phone's cameras don't provide RAW capture"
                UnsupportedReason.NO_USABLE_RAW_SIZES -> "This phone reports RAW but offers no usable RAW image size"
                UnsupportedReason.NO_BACK_CAMERA -> "No back-facing camera was found"
                UnsupportedReason.PERMISSION_REDACTED -> "Camera details are hidden until permission is granted"
            },
            detail = unsupported.detail,
            reportText = state.reportText,
        )
        return
    }
```

`UnsupportedDeviceScreen` reuses the camera-permission gate's visual language: a centred column on the near-black background, the message in the app's existing body style, and one bordered accent pill labelled **COPY REPORT** writing `reportText` via `LocalClipboardManager.current.setText(AnnotatedString(reportText))`. Do **not** add a share-sheet intent here — Task 10 adds sharing from Settings, and duplicating it invites two divergent paths.

- [x] **Step 8: Build and verify**

Run: `./gradlew :app:assembleDebug && ./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all tests pass.

On-device, confirm the normal path is untouched: both phones launch straight into the viewfinder, no new screen. Then exercise the guard without unsupported hardware:

```bash
adb shell pm revoke com.shez.rawcam android.permission.CAMERA
# launch: expect the permission gate, NOT a crash
adb shell pm grant com.shez.rawcam android.permission.CAMERA
```

- [x] **Step 9: Commit**

```bash
git add app/src/main/AndroidManifest.xml app/src/main/java/com/shez/rawcam/camera/CompatibilityReport.kt app/src/main/java/com/shez/rawcam/ui/RecordScreen.kt app/src/test/java/com/shez/rawcam/camera/CompatibilityReportTest.kt
git commit -m "feat: unsupported-device screen and non-RAW install unblocking"
```

---

### Task 8: `AUTO_ONLY` control tier in the UI

**Files:**
- Modify: `app/src/main/java/com/shez/rawcam/ui/RecordScreen.kt` — `RecordUiState`, slider render sites
- Test: `app/src/test/java/com/shez/rawcam/camera/ControlTierTest.kt`

**Interfaces:**
- Consumes: `LensProfile.controlTier` (Task 3).
- Produces: `RecordUiState.controlTier: ControlTier`.

- [x] **Step 1: Write the test**

Create `app/src/test/java/com/shez/rawcam/camera/ControlTierTest.kt`:

```kotlin
package com.shez.rawcam.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class ControlTierTest {

    @Test
    fun `a lens with manual sensor and a real ISO range is FULL`() {
        val r = LensDiscovery.discover(listOf(rawLens("0"))) as DeviceProfile.Supported
        assertEquals(ControlTier.FULL, r.lenses.single().controlTier)
    }

    @Test
    fun `tiers are independent per lens on the same device`() {
        val full = rawLens("0")
        val auto = rawLens("2").copy(capabilities = listOf(3), focalLengthsMm = listOf(2.2f))
        val r = LensDiscovery.discover(listOf(full, auto)) as DeviceProfile.Supported
        assertEquals(ControlTier.AUTO_ONLY, r.lenses.first { it.cameraId == "2" }.controlTier)
        assertEquals(ControlTier.FULL, r.lenses.first { it.cameraId == "0" }.controlTier)
    }
}
```

- [x] **Step 2: Run it**

Run: `./gradlew :app:testDebugUnitTest --tests "*ControlTierTest*"`
Expected: PASS — Task 3 already implements the rule. If it fails, the Task 3 tier logic is wrong; fix `buildLens`, not the test.

- [x] **Step 3: Publish the tier into UI state**

Add to `RecordUiState`:

```kotlin
    val controlTier: ControlTier = ControlTier.FULL,
    val exposureRangeNs: LongRange? = null,
```

Set both wherever `lensIndex` changes and in the post-`initialize` publication, alongside the existing `rawSpec` republication in `coerceToMode`:

```kotlin
    controlTier = lenses.getOrNull(lensIndex)?.controlTier ?: ControlTier.FULL,
    exposureRangeNs = lenses.getOrNull(lensIndex)?.exposureRangeNs,
```

`exposureRangeNs` is added here rather than in Task 9 so both lens-derived fields are published from one place — Task 9 only consumes it.

- [x] **Step 4: Disable manual sliders on an AUTO_ONLY lens**

At the ISO / shutter / focus slider sites, the existing `enabled` condition accounts only for lock flags. Extend each so an unsupported control is disabled **and visually distinct from a locked one**:

```kotlin
    val manualAvailable = state.controlTier == ControlTier.FULL
    // ISO slider (apply the same pattern to shutter and focus):
    enabled = manualAvailable && !state.isoLocked && !locked,
```

When `!manualAvailable`, render one line of body text above the chip row: `"This lens records RAW with automatic exposure"`. Do **not** show lock icons for these — they are not locked, they are absent, and conflating the two states is exactly the confusion this task exists to prevent.

- [x] **Step 5: Build and verify**

Run: `./gradlew :app:assembleDebug && ./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all tests pass.

On-device: both phones report `FULL` on every lens, so every slider must behave exactly as today. **This task is invisible on the owned hardware — that is the expected outcome, not a failure to verify.**

- [x] **Step 6: Commit**

```bash
git add app/src/main/java/com/shez/rawcam/ui/RecordScreen.kt app/src/test/java/com/shez/rawcam/camera/ControlTierTest.kt
git commit -m "feat: honest UI for AUTO_ONLY lenses without manual sensor control"
```

---

### Task 9: Intersect the shutter stop table with the sensor's real exposure range

**Files:**
- Create: `app/src/main/java/com/shez/rawcam/camera/ShutterStops.kt`
- Modify: `app/src/main/java/com/shez/rawcam/ui/RecordScreen.kt` — shutter stop list construction
- Test: `app/src/test/java/com/shez/rawcam/camera/ShutterStopsTest.kt`

**Interfaces:**
- Consumes: `RecordUiState.exposureRangeNs` (published in Task 8).
- Produces: `ShutterStops.available(all: List<Long>, range: LongRange?): List<Long>`.

- [x] **Step 1: Write the failing test**

Create `app/src/test/java/com/shez/rawcam/camera/ShutterStopsTest.kt`:

```kotlin
package com.shez.rawcam.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class ShutterStopsTest {

    private val stops = listOf(2_000_000L, 4_000_000L, 8_000_000L, 16_000_000L, 33_000_000L)

    @Test
    fun `a null range leaves the table untouched`() {
        assertEquals(stops, ShutterStops.available(stops, null))
    }

    @Test
    fun `stops outside the sensor range are dropped`() {
        assertEquals(listOf(4_000_000L, 8_000_000L), ShutterStops.available(stops, 3_000_000L..10_000_000L))
    }

    @Test
    fun `a range excluding every stop keeps the single closest one`() {
        assertEquals(listOf(2_000_000L), ShutterStops.available(stops, 100L..1_000L))
    }
}
```

The third case matters: an empty shutter list would leave the UI with no selectable value at all, which is worse than one imperfect option the HAL will clamp.

- [x] **Step 2: Run and confirm failure**

Run: `./gradlew :app:testDebugUnitTest --tests "*ShutterStopsTest*"`
Expected: FAIL — `Unresolved reference: ShutterStops`.

- [x] **Step 3: Implement**

```kotlin
package com.shez.rawcam.camera

/**
 * Filters the app's fixed shutter stop table down to what a sensor can actually
 * honour. Before this existed, RawCam offered every stop on every device and let
 * the HAL silently clamp out-of-range requests -- the UI then displayed a
 * shutter speed the sensor was not using.
 */
object ShutterStops {
    fun available(all: List<Long>, range: LongRange?): List<Long> {
        if (range == null) return all
        val inRange = all.filter { it in range }
        if (inRange.isNotEmpty()) return inRange
        // Never return an empty list: a picker with no options is unusable. Keep
        // the nearest stop and let the HAL clamp it.
        return listOfNotNull(all.minByOrNull {
            minOf(Math.abs(it - range.first), Math.abs(it - range.last))
        })
    }
}
```

- [x] **Step 4: Use it at the shutter slider**

Where `RecordScreen` builds its shutter stop list:

```kotlin
    val shutterChoices = ShutterStops.available(SHUTTER_STOPS, state.exposureRangeNs)
```

Clamp `shutterIndex` into `shutterChoices.indices` whenever the lens changes, in the same place `coerceToMode` already clamps ISO. An out-of-range index after a lens switch is exactly the class of bug fixed in `0ba7eaa`.

- [x] **Step 5: Run tests and build**

Run: `./gradlew :app:testDebugUnitTest --tests "*ShutterStopsTest*" && ./gradlew :app:assembleDebug`
Expected: PASS, BUILD SUCCESSFUL.

- [x] **Step 6: On-device check**

On both phones, confirm the shutter list is unchanged from today (both sensors' ranges comfortably contain the app's stops) and that switching lenses never leaves the shutter chip blank.

- [x] **Step 7: Commit**

```bash
git add app/src/main/java/com/shez/rawcam/camera/ShutterStops.kt app/src/main/java/com/shez/rawcam/ui/RecordScreen.kt app/src/test/java/com/shez/rawcam/camera/ShutterStopsTest.kt
git commit -m "feat: offer only shutter speeds the sensor can honour"
```

---

### Task 10: Compatibility report screen + fixture dump action + golden fixtures

**Files:**
- Modify: `app/src/main/java/com/shez/rawcam/ui/SettingsScreen.kt`
- Create: `app/src/test/resources/fixtures/*.json`
- Test: `app/src/test/java/com/shez/rawcam/camera/GoldenFixtureTest.kt`

**Interfaces:**
- Consumes: `CompatibilityReport.render` (Task 7), `SnapshotSet.toJson` (Task 1), `Camera2SnapshotSource.capture` (Task 5).
- Produces: committed fixtures `xiaomi-14-ultra.json`, `pixel-7-pro.json`, `galaxy-s10plus-fv5.json`, plus 10 shape fixtures.

- [x] **Step 1: Add the Settings rows**

In the existing sectioned list, add a "Device" section with two rows following the established row-widget pattern:
- **Compatibility report** — opens a scrollable read-only text view of `state.reportText`, with a SHARE action firing `Intent.createChooser` on `ACTION_SEND` / `text/plain`.
- **Dump characteristics (JSON)** — writes `Camera2SnapshotSource(cameraManager).capture().toJson()` to `getExternalFilesDir(null)/snapshot-<model>.json` and shares it the same way. This is how real fixtures get produced.

Both run off the main thread on the existing `cameraOps` dispatcher — `capture()` is binder IPC.

- [x] **Step 2: Capture the two real fixtures**

On each phone: Settings → Dump characteristics → share to yourself. Save as:
- `app/src/test/resources/fixtures/xiaomi-14-ultra.json`
- `app/src/test/resources/fixtures/pixel-7-pro.json`

For `galaxy-s10plus-fv5.json`, hand-write a `SnapshotSet` from the free FV-5 sample's published values: back camera GRBG (`cfa = 1`), `whiteLevel = 1023`, `isoRange = [50, 1250]`, `sensorOrientation = 90`, `facing = 1`, capabilities including RAW and MANUAL_SENSOR, one RAW size. Task 11's importer replaces this hand-written file with a generated one.

- [x] **Step 3: Write the 10 shape fixtures**

Each is a small `SnapshotSet` JSON in `app/src/test/resources/fixtures/`, one per failure shape: `no-raw.json`, `raw-without-manual.json`, `orientation-270.json`, `missing-physical-size.json`, `missing-color-matrix.json`, `samsung-high-ids.json`, `permission-redacted.json`, `single-lens-legacy.json`, `zero-raw-sizes.json`, `absurd-values.json`.

Example — `raw-without-manual.json`:

```json
{
  "model": "Shape: RAW without MANUAL_SENSOR",
  "sdkInt": 34,
  "cameras": [
    {
      "cameraId": "0",
      "facing": 1,
      "capabilities": [0, 3],
      "rawSizes": [{"width": 4032, "height": 3024, "minFrameDurationNs": 41666666}],
      "cfa": 0,
      "whiteLevel": 1023,
      "blackLevel": [64, 64, 64, 64],
      "focalLengthsMm": [5.4],
      "physicalSizeMm": [7.4, 5.6],
      "sensorOrientation": 90
    }
  ]
}
```

- [x] **Step 4: Write the golden test**

Create `app/src/test/java/com/shez/rawcam/camera/GoldenFixtureTest.kt`:

```kotlin
package com.shez.rawcam.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GoldenFixtureTest {

    private fun load(name: String): SnapshotSet =
        SnapshotSet.fromJson(
            checkNotNull(javaClass.classLoader?.getResourceAsStream("fixtures/$name")) {
                "fixture not found: $name"
            }.bufferedReader().readText()
        )

    private fun profileOf(name: String) = LensDiscovery.discover(load(name).cameras)

    @Test
    fun `xiaomi 14 ultra yields four lenses with the main at 23mm`() {
        val r = profileOf("xiaomi-14-ultra.json") as DeviceProfile.Supported
        assertEquals(4, r.lenses.size)
        assertEquals(listOf("12mm", "23mm", "74mm", "117mm"), r.lenses.map { it.label })
        assertEquals("23mm", r.lenses[r.mainIndex].label)
        assertTrue(r.lenses.any { it.standalone })
    }

    @Test
    fun `pixel 7 pro yields two lenses, both fully manual`() {
        val r = profileOf("pixel-7-pro.json") as DeviceProfile.Supported
        assertEquals(2, r.lenses.size)
        assertTrue(r.lenses.all { it.controlTier == ControlTier.FULL })
    }

    @Test
    fun `galaxy s10 plus back sensor is GRBG and supported`() {
        val r = profileOf("galaxy-s10plus-fv5.json") as DeviceProfile.Supported
        assertEquals(1, r.lenses.single().cfa)
    }

    @Test
    fun `a device with no RAW is unsupported`() {
        assertEquals(UnsupportedReason.NO_RAW_CAPABILITY,
            (profileOf("no-raw.json") as DeviceProfile.Unsupported).reason)
    }

    @Test
    fun `RAW without manual sensor still records, as AUTO_ONLY`() {
        val r = profileOf("raw-without-manual.json") as DeviceProfile.Supported
        assertEquals(ControlTier.AUTO_ONLY, r.lenses.single().controlTier)
    }

    @Test
    fun `every shape fixture resolves without throwing`() {
        listOf("orientation-270.json", "missing-physical-size.json", "missing-color-matrix.json",
            "samsung-high-ids.json", "permission-redacted.json", "single-lens-legacy.json",
            "zero-raw-sizes.json", "absurd-values.json").forEach { name ->
            val r = profileOf(name)
            assertTrue("$name produced neither result",
                r is DeviceProfile.Supported || r is DeviceProfile.Unsupported)
        }
    }
}
```

- [x] **Step 5: Run and iterate**

Run: `./gradlew :app:testDebugUnitTest --tests "*GoldenFixtureTest*"`
Expected: PASS. If the Xiaomi fixture yields anything other than 4 lenses with those labels, `LensDiscovery` has diverged from shipped behaviour — **fix the discovery code, not the assertion.**

- [x] **Step 6: Commit**

```bash
git add app/src/main/java/com/shez/rawcam/ui/SettingsScreen.kt app/src/test/resources/fixtures/ app/src/test/java/com/shez/rawcam/camera/GoldenFixtureTest.kt
git commit -m "feat: compatibility report, characteristics dump, and golden device fixtures"
```

---

### Task 11: FV-5 importer (supplementary breadth only)

**Files:**
- Create: `app/src/test/java/com/shez/rawcam/camera/FvFiveImporter.kt`
- Test: `app/src/test/java/com/shez/rawcam/camera/FvFiveImporterTest.kt`

**Interfaces:**
- Consumes: `CameraSnapshot`, `SnapshotSet` (Task 1).
- Produces: `FvFiveImporter.import(text: String): SnapshotSet`.

**This task must not become load-bearing.** Per the spec's governing rule, every correctness claim is already covered by Tasks 1-10. This adds fuzz breadth and nothing else. The importer lives in the **test** source set: it is a fixture-building tool, never shipped code.

- [x] **Step 1: Write the failing test against the free sample**

Save the free sample to `app/src/test/resources/fv5/samsung_sm-g975f_beyond2.json`, then create `app/src/test/java/com/shez/rawcam/camera/FvFiveImporterTest.kt`:

```kotlin
package com.shez.rawcam.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class FvFiveImporterTest {

    private fun sampleOrNull(): String? =
        javaClass.classLoader?.getResourceAsStream("fv5/samsung_sm-g975f_beyond2.json")
            ?.bufferedReader()?.readText()

    @Test
    fun `imports the free sample into snapshots`() {
        val raw = sampleOrNull()
        // Skips cleanly when the corpus is absent -- the suite must stay green
        // without any licensed or downloaded data. See the spec's governing rule.
        assumeTrue("FV-5 sample not present; skipping", raw != null)
        val set = FvFiveImporter.import(raw!!)
        assertTrue(set.cameras.isNotEmpty())
        val back = set.cameras.first { it.facing == 1 }
        assertEquals(1023, back.whiteLevel)
        assertEquals(1, back.cfa) // GRBG
        assertEquals(90, back.sensorOrientation)
    }

    @Test
    fun `imported snapshots resolve without throwing`() {
        val raw = sampleOrNull()
        assumeTrue("FV-5 sample not present; skipping", raw != null)
        val r = LensDiscovery.discover(FvFiveImporter.import(raw!!).cameras)
        assertTrue(r is DeviceProfile.Supported || r is DeviceProfile.Unsupported)
    }
}
```

- [x] **Step 2: Run and confirm failure**

Run: `./gradlew :app:testDebugUnitTest --tests "*FvFiveImporterTest*"`
Expected: FAIL — `Unresolved reference: FvFiveImporter`.

- [x] **Step 3: Implement the importer**

```kotlin
package com.shez.rawcam.camera

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Converts a Camera FV-5 device-database document into [CameraSnapshot]s.
 *
 * Their shape is {sdkLevel: {cameraId: {AOSP-key: value}}}. Verified against the
 * public Galaxy S10+ sample: every field CameraSnapshot needs is present under
 * AOSP key names, INCLUDING android.sensor.orientation and
 * android.sensor.info.exposureTimeRange.
 *
 * IMPORTANT LIMITATION: their dumps carry NO logical-multi-camera topology --
 * physicalCameraIds is empty and sub-lenses are absent entirely. This corpus can
 * therefore never test lens discovery, only per-sensor field handling. Topology
 * coverage comes from the hand-authored shape fixtures and stays there.
 *
 * Test-source-set only. A fixture-building tool, never shipped code.
 */
object FvFiveImporter {

    private val JSON = Json { ignoreUnknownKeys = true; isLenient = true }

    /** CFA and facing may arrive as names rather than ints depending on the
     * dump's processing level; map both forms rather than bending the model. */
    private val CFA_NAMES = mapOf("RGGB" to 0, "GRBG" to 1, "GBRG" to 2, "BGGR" to 3)
    private val FACING_NAMES = mapOf("FRONT" to 0, "BACK" to 1, "EXTERNAL" to 2)

    fun import(text: String): SnapshotSet {
        val root = JSON.parseToJsonElement(text).jsonObject
        // Highest SDK level present is the most representative of current behaviour.
        val sdkKey = root.keys.maxByOrNull { it.toIntOrNull() ?: -1 }
            ?: return SnapshotSet("unknown", 0, emptyList())
        val cameras = root[sdkKey]?.jsonObject ?: return SnapshotSet("unknown", 0, emptyList())
        return SnapshotSet(
            model = "fv5:$sdkKey",
            sdkInt = sdkKey.toIntOrNull() ?: 0,
            cameras = cameras.entries.mapNotNull { (id, node) ->
                runCatching { snapshot(id, node.jsonObject) }.getOrNull()
            },
        )
    }

    private fun snapshot(id: String, o: JsonObject): CameraSnapshot {
        fun str(k: String) = o[k]?.jsonPrimitive?.content
        fun int(k: String) = str(k)?.toIntOrNull()
        fun ints(k: String) = o[k]?.jsonArray?.mapNotNull { it.jsonPrimitive.content.toIntOrNull() }
        fun longs(k: String) = o[k]?.jsonArray?.mapNotNull { it.jsonPrimitive.content.toLongOrNull() }
        fun floats(k: String) = o[k]?.jsonArray?.mapNotNull { it.jsonPrimitive.content.toFloatOrNull() }

        val cfaRaw = str("android.sensor.info.colorFilterArrangement")
        val facingRaw = str("android.lens.facing")

        return CameraSnapshot(
            cameraId = id,
            facing = facingRaw?.toIntOrNull() ?: FACING_NAMES[facingRaw?.uppercase()],
            capabilities = ints("android.request.availableCapabilities") ?: emptyList(),
            physicalIds = emptyList(), // never present in this corpus -- see kdoc
            rawSizes = emptyList(),    // stream config not parsed; field coverage only
            cfa = cfaRaw?.toIntOrNull() ?: CFA_NAMES[cfaRaw?.uppercase()],
            whiteLevel = int("android.sensor.info.whiteLevel"),
            blackLevel = ints("android.sensor.blackLevelPattern"),
            focalLengthsMm = floats("android.lens.info.availableFocalLengths"),
            physicalSizeMm = floats("android.sensor.info.physicalSize"),
            colorTransform1 = floats("android.sensor.colorTransform1"),
            colorTransform2 = floats("android.sensor.colorTransform2"),
            illuminant1 = int("android.sensor.referenceIlluminant1"),
            illuminant2 = int("android.sensor.referenceIlluminant2"),
            isoRange = ints("android.sensor.info.sensitivityRange"),
            exposureRangeNs = longs("android.sensor.info.exposureTimeRange"),
            minFocusDiopters = floats("android.lens.info.minimumFocusDistance")?.firstOrNull(),
            oisModes = ints("android.lens.info.availableOpticalStabilization"),
            sensorOrientation = int("android.sensor.orientation"),
        )
    }
}
```

Note `rawSizes` is deliberately empty: this corpus is for *field* coverage, so its snapshots resolve to `Unsupported(NO_USABLE_RAW_SIZES)`, which is a valid outcome the fuzz assertion accepts. If a later pass wants these devices to reach lens-building, parse `android.scaler.streamConfigurationMap` here — but that is not required by this spec.

> **Corrected 2026-07-23, after fetching the real sample:** the snippet above was written against a guessed shape (flat `android.*` keys) before the actual file was in hand. The real download from `camerafv5.com/devices/licensing/` is `{sdkLevel: {cameraId: {apiNumber, cameraDirection, cameraId, cameraOrientation, capabilities: [{name, value}]}}}` — every AOSP field lives inside `capabilities`, not as a top-level key, and `value` is a typed wrapper (`NamedInteger` with `v`, `List` with `items`, `IntegerRange`/`LongRange` with `min`/`max`, `FloatSize` with `w`/`h`), plus two fields (`blackLevelPattern`, `colorTransform1/2`) that arrive as stringified Java `toString()` output requiring regex parsing. The shipped `FvFiveImporter.kt` was rewritten to match; see that file for the real implementation. Also corrected: `physicalCameraIds` **is** populated for this device's logical cameras (contrary to the original "IMPORTANT LIMITATION" claim below) — the corpus can exercise real topology after all, though the spec's governing rule still doesn't depend on it.

- [x] **Step 4: Run and confirm pass (or clean skip)**

Run: `./gradlew :app:testDebugUnitTest --tests "*FvFiveImporterTest*"`
Expected: PASS with the sample present; **SKIPPED, not failed**, when absent.

- [x] **Step 5: Verify the suite is green with no FV-5 data at all**

```bash
mv app/src/test/resources/fv5 /tmp/fv5-hold
./gradlew :app:testDebugUnitTest
mv /tmp/fv5-hold app/src/test/resources/fv5
```

Expected: **BUILD SUCCESSFUL.** If removing the corpus breaks the suite, the governing constraint is violated — fix it before committing.

- [x] **Step 6: Commit**

```bash
git add app/src/test/java/com/shez/rawcam/camera/FvFiveImporter.kt app/src/test/java/com/shez/rawcam/camera/FvFiveImporterTest.kt app/src/test/resources/fv5/
git commit -m "test: add FV-5 fixture importer as optional fuzz breadth"
```

---

## Final verification

- [x] `./gradlew :app:testDebugUnitTest` — all JVM tests green (47 tests, 0 skipped, 0 failures)
- [x] `./gradlew :app:assembleDebug :app:assembleRelease` — both variants build, R8 runs
- [x] C++ host tests still green: `& "$env:LOCALAPPDATA\Android\Sdk\cmake\3.22.1\bin\ctest.exe" --test-dir core/build` — 7/7
- [x] Suite green with `app/src/test/resources/fv5/` removed
- [x] **Xiaomi 14 Ultra:** 4 lenses (12/23/74/117mm), launches on 23mm, all lens-crossing directions clean, record + export on the 3x standalone lens succeeds
- [x] **Pixel 7 Pro:** 3 lenses (13/24/117mm, corrected from the plan's original "2 lenses" guess), correct ISO ranges, record + export succeeds on a non-main lens (13mm ultrawide, 651 frames/651 DNGs, 0 dropped)
- [x] Permission revoke → gate screen, no crash; re-grant → normal launch (verified on both phones)
- [x] Compatibility report renders and shares on both phones
- [x] No new `Log.e` or FATAL EXCEPTION in a full logcat sweep on either device
