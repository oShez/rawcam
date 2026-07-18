package com.shez.rawcam.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

/** When to show the metering overlay on app startup. */
enum class StartupMeter { ALWAYS, IF_NO_SAVED, NEVER }

/** Which controls the spot meter drives while it's active. */
enum class MeterScope { EVERYTHING, EXPOSURE_FOCUS, WB_ONLY }

/** Mains flicker frequency used to bias shutter-speed suggestions. */
enum class MainsFreq { OFF, HZ50, HZ60 }

/** Optical image stabilization mode. */
enum class OisMode { AUTO, ON, OFF }

/** How shutter speed is displayed: as a fraction of a second, or as a shutter angle. */
enum class ShutterDisplay { FRACTION, ANGLE }

/** Size of the meter reticle's sampling box, as a fraction of frame width: 0.05f / 0.10f / 0.20f. */
enum class MeterRegion { SMALL, MEDIUM, LARGE }

/**
 * User-configurable app settings, persisted via [SettingsRepository]. Every field carries
 * a default so a fresh install -- or a corrupt/missing individual DataStore key -- always
 * resolves to a sane value; see [SettingsRepository]'s kdoc for the fallback contract.
 */
data class Settings(
    val startupMeter: StartupMeter = StartupMeter.IF_NO_SAVED,
    val defaultKelvin: Int = 5600,
    val defaultTint: Int = 0,
    val defaultIso: Int = 0,                 // 0 = device minimum
    val defaultShutterDenom: Int = 48,
    val defaultFps: Int = 24,
    val defaultLensIndex: Int = -1,          // -1 = device main
    val defaultSizeIndex: Int = 0,
    val rememberLastState: Boolean = true,
    val freeSpaceReserveSeconds: Int = 35,   // 5..120 step 5
    val maxClipLengthSeconds: Int = 0,       // 0=off, 30, 60, 300, 600
    val thermalAutoStop: Boolean = false,
    val mainsFreq: MainsFreq = MainsFreq.OFF,
    val oisMode: OisMode = OisMode.AUTO,
    val clipPrefix: String = "clip",         // sanitized [A-Za-z0-9_-], 1..16
    val meterScope: MeterScope = MeterScope.EVERYTHING,
    val meterRegion: MeterRegion = MeterRegion.MEDIUM,
    val reticleHoldMs: Int = 600,            // 300, 600, 1200
    val gridEnabled: Boolean = false,
    val levelEnabled: Boolean = false,
    val shutterDisplay: ShutterDisplay = ShutterDisplay.FRACTION,
    val showStatsSidebar: Boolean = true,
    val showBench: Boolean = true,
    val confirmDelete: Boolean = true,
    val deleteAfterExport: Boolean = false,
    val autoExport: Boolean = false,
    val debugLogging: Boolean = false,
)

/**
 * Snapshot of in-progress capture controls, saved so the next launch can restore where
 * the user left off (when [Settings.rememberLastState] is enabled). `anchorR/G/B <= 0f`
 * means no white-balance anchor was set.
 */
data class CaptureState(
    val iso: Int, val shutterDenom: Int, val focusDiopters: Float,
    val kelvin: Int, val tint: Int, val fps: Int,
    val lensIndex: Int, val sizeIndex: Int,
    val anchorR: Float, val anchorG: Float, val anchorB: Float,
    val anchorKelvin: Int,
)

/**
 * DataStore-backed persistence for [Settings] and [CaptureState].
 *
 * Corrupt-key fallback contract: every read decodes each preference independently and
 * falls back to that field's default the instant the value is missing or unreadable --
 * a bad or absent single key never invalidates the rest of the record, and never
 * throws. Enum fields are stored as their [Enum.name] string and decoded with
 * `runCatching { enumValueOf<T>(s) }.getOrDefault(default)`, so a renamed/removed enum
 * constant (e.g. after an app update changes an enum's members) silently falls back
 * instead of crashing on read. IO failures reading the DataStore file itself (not just
 * a missing key -- e.g. a corrupted preferences file on disk) are caught at the [Flow]
 * level via `.catch { emit(emptyPreferences()) }`, which then decodes to all-defaults
 * through that same per-field fallback path rather than propagating the exception to
 * collectors.
 *
 * [settings] and [captureState] are exposed as `get()`-backed properties (not eagerly
 * captured `val`s) so they read [dataStore] lazily on each collection -- evaluating
 * `dataStore.data` eagerly at object-init time would run before [init] has assigned the
 * lateinit field, since Kotlin `object` property initializers run as part of the
 * singleton's first-touch class init, ahead of any member function call.
 *
 * [init] must be called once (idempotent) -- e.g. from `Activity.onCreate` -- before
 * [settings], [update], [captureState], [saveCaptureState], or [clearAll] are used.
 */
object SettingsRepository {

    private val Context.dataStore by preferencesDataStore(name = "settings")

    private lateinit var dataStore: DataStore<Preferences>

    /** Idempotent; call once from Activity onCreate. */
    fun init(context: Context) {
        if (!::dataStore.isInitialized) {
            dataStore = context.applicationContext.dataStore
        }
    }

    // ---- Settings keys (key name == field name) ----
    private val KEY_STARTUP_METER = stringPreferencesKey("startupMeter")
    private val KEY_DEFAULT_KELVIN = intPreferencesKey("defaultKelvin")
    private val KEY_DEFAULT_TINT = intPreferencesKey("defaultTint")
    private val KEY_DEFAULT_ISO = intPreferencesKey("defaultIso")
    private val KEY_DEFAULT_SHUTTER_DENOM = intPreferencesKey("defaultShutterDenom")
    private val KEY_DEFAULT_FPS = intPreferencesKey("defaultFps")
    private val KEY_DEFAULT_LENS_INDEX = intPreferencesKey("defaultLensIndex")
    private val KEY_DEFAULT_SIZE_INDEX = intPreferencesKey("defaultSizeIndex")
    private val KEY_REMEMBER_LAST_STATE = booleanPreferencesKey("rememberLastState")
    private val KEY_FREE_SPACE_RESERVE_SECONDS = intPreferencesKey("freeSpaceReserveSeconds")
    private val KEY_MAX_CLIP_LENGTH_SECONDS = intPreferencesKey("maxClipLengthSeconds")
    private val KEY_THERMAL_AUTO_STOP = booleanPreferencesKey("thermalAutoStop")
    private val KEY_MAINS_FREQ = stringPreferencesKey("mainsFreq")
    private val KEY_OIS_MODE = stringPreferencesKey("oisMode")
    private val KEY_CLIP_PREFIX = stringPreferencesKey("clipPrefix")
    private val KEY_METER_SCOPE = stringPreferencesKey("meterScope")
    private val KEY_METER_REGION = stringPreferencesKey("meterRegion")
    private val KEY_RETICLE_HOLD_MS = intPreferencesKey("reticleHoldMs")
    private val KEY_GRID_ENABLED = booleanPreferencesKey("gridEnabled")
    private val KEY_LEVEL_ENABLED = booleanPreferencesKey("levelEnabled")
    private val KEY_SHUTTER_DISPLAY = stringPreferencesKey("shutterDisplay")
    private val KEY_SHOW_STATS_SIDEBAR = booleanPreferencesKey("showStatsSidebar")
    private val KEY_SHOW_BENCH = booleanPreferencesKey("showBench")
    private val KEY_CONFIRM_DELETE = booleanPreferencesKey("confirmDelete")
    private val KEY_DELETE_AFTER_EXPORT = booleanPreferencesKey("deleteAfterExport")
    private val KEY_AUTO_EXPORT = booleanPreferencesKey("autoExport")
    private val KEY_DEBUG_LOGGING = booleanPreferencesKey("debugLogging")

    // ---- CaptureState keys (cs_ prefix + a presence guard) ----
    private val KEY_CS_SAVED = booleanPreferencesKey("captureStateSaved")
    private val KEY_CS_ISO = intPreferencesKey("cs_iso")
    private val KEY_CS_SHUTTER_DENOM = intPreferencesKey("cs_shutterDenom")
    private val KEY_CS_FOCUS_DIOPTERS = floatPreferencesKey("cs_focusDiopters")
    private val KEY_CS_KELVIN = intPreferencesKey("cs_kelvin")
    private val KEY_CS_TINT = intPreferencesKey("cs_tint")
    private val KEY_CS_FPS = intPreferencesKey("cs_fps")
    private val KEY_CS_LENS_INDEX = intPreferencesKey("cs_lensIndex")
    private val KEY_CS_SIZE_INDEX = intPreferencesKey("cs_sizeIndex")
    private val KEY_CS_ANCHOR_R = floatPreferencesKey("cs_anchorR")
    private val KEY_CS_ANCHOR_G = floatPreferencesKey("cs_anchorG")
    private val KEY_CS_ANCHOR_B = floatPreferencesKey("cs_anchorB")
    private val KEY_CS_ANCHOR_KELVIN = intPreferencesKey("cs_anchorKelvin")

    /** Decodes an enum-by-name preference, falling back to [default] on a missing or corrupt value. */
    private inline fun <reified T : Enum<T>> decodeEnum(raw: String?, default: T): T =
        if (raw == null) default else runCatching { enumValueOf<T>(raw) }.getOrDefault(default)

    private fun Preferences.toSettings(): Settings {
        val fallback = Settings()
        return Settings(
            startupMeter = decodeEnum(this[KEY_STARTUP_METER], fallback.startupMeter),
            defaultKelvin = this[KEY_DEFAULT_KELVIN] ?: fallback.defaultKelvin,
            defaultTint = this[KEY_DEFAULT_TINT] ?: fallback.defaultTint,
            defaultIso = this[KEY_DEFAULT_ISO] ?: fallback.defaultIso,
            defaultShutterDenom = this[KEY_DEFAULT_SHUTTER_DENOM] ?: fallback.defaultShutterDenom,
            defaultFps = this[KEY_DEFAULT_FPS] ?: fallback.defaultFps,
            defaultLensIndex = this[KEY_DEFAULT_LENS_INDEX] ?: fallback.defaultLensIndex,
            defaultSizeIndex = this[KEY_DEFAULT_SIZE_INDEX] ?: fallback.defaultSizeIndex,
            rememberLastState = this[KEY_REMEMBER_LAST_STATE] ?: fallback.rememberLastState,
            freeSpaceReserveSeconds = this[KEY_FREE_SPACE_RESERVE_SECONDS] ?: fallback.freeSpaceReserveSeconds,
            maxClipLengthSeconds = this[KEY_MAX_CLIP_LENGTH_SECONDS] ?: fallback.maxClipLengthSeconds,
            thermalAutoStop = this[KEY_THERMAL_AUTO_STOP] ?: fallback.thermalAutoStop,
            mainsFreq = decodeEnum(this[KEY_MAINS_FREQ], fallback.mainsFreq),
            oisMode = decodeEnum(this[KEY_OIS_MODE], fallback.oisMode),
            clipPrefix = this[KEY_CLIP_PREFIX] ?: fallback.clipPrefix,
            meterScope = decodeEnum(this[KEY_METER_SCOPE], fallback.meterScope),
            meterRegion = decodeEnum(this[KEY_METER_REGION], fallback.meterRegion),
            reticleHoldMs = this[KEY_RETICLE_HOLD_MS] ?: fallback.reticleHoldMs,
            gridEnabled = this[KEY_GRID_ENABLED] ?: fallback.gridEnabled,
            levelEnabled = this[KEY_LEVEL_ENABLED] ?: fallback.levelEnabled,
            shutterDisplay = decodeEnum(this[KEY_SHUTTER_DISPLAY], fallback.shutterDisplay),
            showStatsSidebar = this[KEY_SHOW_STATS_SIDEBAR] ?: fallback.showStatsSidebar,
            showBench = this[KEY_SHOW_BENCH] ?: fallback.showBench,
            confirmDelete = this[KEY_CONFIRM_DELETE] ?: fallback.confirmDelete,
            deleteAfterExport = this[KEY_DELETE_AFTER_EXPORT] ?: fallback.deleteAfterExport,
            autoExport = this[KEY_AUTO_EXPORT] ?: fallback.autoExport,
            debugLogging = this[KEY_DEBUG_LOGGING] ?: fallback.debugLogging,
        )
    }

    val settings: Flow<Settings>
        get() = dataStore.data
            .catch { emit(emptyPreferences()) }
            .map { prefs -> prefs.toSettings() }

    /**
     * Applies [transform] to the current [Settings] and writes every field back.
     * [Settings.clipPrefix] is re-sanitized via [sanitizePrefix] and
     * [Settings.freeSpaceReserveSeconds] is coerced into `5..120` on write, so callers
     * (and stray direct field mutations from a `copy()`) can never persist an invalid
     * prefix or an out-of-range reserve.
     */
    suspend fun update(transform: (Settings) -> Settings) {
        dataStore.edit { prefs ->
            val updated = transform(prefs.toSettings())
            val next = updated.copy(
                clipPrefix = sanitizePrefix(updated.clipPrefix),
                freeSpaceReserveSeconds = updated.freeSpaceReserveSeconds.coerceIn(5, 120),
            )
            prefs[KEY_STARTUP_METER] = next.startupMeter.name
            prefs[KEY_DEFAULT_KELVIN] = next.defaultKelvin
            prefs[KEY_DEFAULT_TINT] = next.defaultTint
            prefs[KEY_DEFAULT_ISO] = next.defaultIso
            prefs[KEY_DEFAULT_SHUTTER_DENOM] = next.defaultShutterDenom
            prefs[KEY_DEFAULT_FPS] = next.defaultFps
            prefs[KEY_DEFAULT_LENS_INDEX] = next.defaultLensIndex
            prefs[KEY_DEFAULT_SIZE_INDEX] = next.defaultSizeIndex
            prefs[KEY_REMEMBER_LAST_STATE] = next.rememberLastState
            prefs[KEY_FREE_SPACE_RESERVE_SECONDS] = next.freeSpaceReserveSeconds
            prefs[KEY_MAX_CLIP_LENGTH_SECONDS] = next.maxClipLengthSeconds
            prefs[KEY_THERMAL_AUTO_STOP] = next.thermalAutoStop
            prefs[KEY_MAINS_FREQ] = next.mainsFreq.name
            prefs[KEY_OIS_MODE] = next.oisMode.name
            prefs[KEY_CLIP_PREFIX] = next.clipPrefix
            prefs[KEY_METER_SCOPE] = next.meterScope.name
            prefs[KEY_METER_REGION] = next.meterRegion.name
            prefs[KEY_RETICLE_HOLD_MS] = next.reticleHoldMs
            prefs[KEY_GRID_ENABLED] = next.gridEnabled
            prefs[KEY_LEVEL_ENABLED] = next.levelEnabled
            prefs[KEY_SHUTTER_DISPLAY] = next.shutterDisplay.name
            prefs[KEY_SHOW_STATS_SIDEBAR] = next.showStatsSidebar
            prefs[KEY_SHOW_BENCH] = next.showBench
            prefs[KEY_CONFIRM_DELETE] = next.confirmDelete
            prefs[KEY_DELETE_AFTER_EXPORT] = next.deleteAfterExport
            prefs[KEY_AUTO_EXPORT] = next.autoExport
            prefs[KEY_DEBUG_LOGGING] = next.debugLogging
        }
    }

    /** Null when no capture state has been saved yet (guard key absent or false). */
    val captureState: Flow<CaptureState?>
        get() = dataStore.data
            .catch { emit(emptyPreferences()) }
            .map { prefs ->
                if (prefs[KEY_CS_SAVED] != true) {
                    null
                } else {
                    val fallback = Settings()
                    CaptureState(
                        iso = prefs[KEY_CS_ISO] ?: fallback.defaultIso,
                        shutterDenom = prefs[KEY_CS_SHUTTER_DENOM] ?: fallback.defaultShutterDenom,
                        focusDiopters = prefs[KEY_CS_FOCUS_DIOPTERS] ?: 0f,
                        kelvin = prefs[KEY_CS_KELVIN] ?: fallback.defaultKelvin,
                        tint = prefs[KEY_CS_TINT] ?: fallback.defaultTint,
                        fps = prefs[KEY_CS_FPS] ?: fallback.defaultFps,
                        lensIndex = prefs[KEY_CS_LENS_INDEX] ?: fallback.defaultLensIndex,
                        sizeIndex = prefs[KEY_CS_SIZE_INDEX] ?: fallback.defaultSizeIndex,
                        anchorR = prefs[KEY_CS_ANCHOR_R] ?: 0f,
                        anchorG = prefs[KEY_CS_ANCHOR_G] ?: 0f,
                        anchorB = prefs[KEY_CS_ANCHOR_B] ?: 0f,
                        anchorKelvin = prefs[KEY_CS_ANCHOR_KELVIN] ?: fallback.defaultKelvin,
                    )
                }
            }

    suspend fun saveCaptureState(s: CaptureState) {
        dataStore.edit { prefs ->
            prefs[KEY_CS_ISO] = s.iso
            prefs[KEY_CS_SHUTTER_DENOM] = s.shutterDenom
            prefs[KEY_CS_FOCUS_DIOPTERS] = s.focusDiopters
            prefs[KEY_CS_KELVIN] = s.kelvin
            prefs[KEY_CS_TINT] = s.tint
            prefs[KEY_CS_FPS] = s.fps
            prefs[KEY_CS_LENS_INDEX] = s.lensIndex
            prefs[KEY_CS_SIZE_INDEX] = s.sizeIndex
            prefs[KEY_CS_ANCHOR_R] = s.anchorR
            prefs[KEY_CS_ANCHOR_G] = s.anchorG
            prefs[KEY_CS_ANCHOR_B] = s.anchorB
            prefs[KEY_CS_ANCHOR_KELVIN] = s.anchorKelvin
            prefs[KEY_CS_SAVED] = true
        }
    }

    /** Reset-all: clears both [Settings] and [CaptureState] -- every key, unconditionally. */
    suspend fun clearAll() {
        dataStore.edit { it.clear() }
    }

    /** Filters to `[A-Za-z0-9_-]`, caps at 16 chars, and falls back to `"clip"` if that leaves nothing. */
    fun sanitizePrefix(raw: String): String =
        raw.filter { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '_' || it == '-' }
            .take(16)
            .ifEmpty { "clip" }
}
