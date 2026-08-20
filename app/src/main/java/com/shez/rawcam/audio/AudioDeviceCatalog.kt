package com.shez.rawcam.audio

/**
 * A selectable audio input, flattened out of Android's AudioDeviceInfo so the
 * selection logic below is testable without a device. [type] is the numeric
 * value of an AudioDeviceInfo.TYPE_* constant -- passed in as a plain Int so
 * this file has no Android framework import and can run under plain JUnit.
 *
 * [id] is deliberately NOT part of [key]: AudioDeviceInfo ids are not stable
 * across disconnect/reconnect, so persisting one would silently lose the
 * user's chosen mic the first time they unplugged it.
 */
data class AudioInputDevice(
    val id: Int,
    val type: Int,
    val productName: String,
    val channelCounts: IntArray,
) {
    val key: String get() = AudioDeviceCatalog.keyOf(type, productName)
    val displayName: String get() = AudioDeviceCatalog.displayNameOf(type, productName)

    /** Stereo when the device offers it, else mono. Pinned policy, not a user option. */
    val preferredChannels: Int get() = if (channelCounts.contains(2)) 2 else 1

    // channelCounts is an array, so the generated equals/hashCode would compare by
    // referential identity and break every list/value assertion this type appears in.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AudioInputDevice) return false
        return id == other.id && type == other.type && productName == other.productName &&
            channelCounts.contentEquals(other.channelCounts)
    }

    override fun hashCode(): Int {
        var r = id
        r = 31 * r + type
        r = 31 * r + productName.hashCode()
        r = 31 * r + channelCounts.contentHashCode()
        return r
    }
}

/**
 * Pure input-selection policy: which devices are offered, how a persisted choice
 * is encoded, and how it is resolved against the currently connected set.
 *
 * No Android framework imports on purpose -- the Android-facing half
 * (AudioManager.getDevices) is a thin adapter inside AudioRecorder (Task 6);
 * all decision-making lives in these pure functions so it is JVM-testable.
 *
 * The AudioDeviceInfo.TYPE_* values used below are its documented numeric
 * constants (stable API surface), spelled out as plain Ints for the same reason.
 */
object AudioDeviceCatalog {

    private const val TYPE_BUILTIN_MIC = 15
    private const val TYPE_WIRED_HEADSET = 3
    private const val TYPE_USB_DEVICE = 11
    private const val TYPE_USB_HEADSET = 22
    private const val TYPE_USB_ACCESSORY = 12
    private const val TYPE_BLUETOOTH_SCO = 7
    private const val TYPE_BLUETOOTH_A2DP = 8
    private const val TYPE_BLE_HEADSET = 26

    /**
     * Bluetooth is excluded outright. SCO and LE Audio have variable,
     * uncharacterizable latency, so a Bluetooth mic would produce a clip whose
     * sync claim is false -- worse than not offering the input at all.
     */
    private val EXCLUDED_TYPES = setOf(
        TYPE_BLUETOOTH_SCO,
        TYPE_BLUETOOTH_A2DP,
        TYPE_BLE_HEADSET,
    )

    fun isExcluded(type: Int): Boolean = type in EXCLUDED_TYPES

    fun keyOf(type: Int, productName: String): String = "$type:$productName"

    fun displayNameOf(type: Int, productName: String): String = when (type) {
        TYPE_BUILTIN_MIC -> "Built-in mic"
        TYPE_WIRED_HEADSET -> "Wired headset"
        TYPE_USB_DEVICE,
        TYPE_USB_HEADSET,
        TYPE_USB_ACCESSORY -> "USB: $productName"
        else -> productName
    }

    /** The devices worth offering, in the order the platform reported them. */
    fun selectable(devices: List<AudioInputDevice>): List<AudioInputDevice> =
        devices.filter { !isExcluded(it.type) }

    /**
     * Resolves a persisted [key] against the live device list. Null means "use
     * the system default" -- which covers an empty key, a device since
     * unplugged, one whose product name changed, and one that is now excluded
     * (e.g. a policy change). Never throws: an unmatched or malformed key is
     * just a miss, not an error.
     */
    fun resolve(devices: List<AudioInputDevice>, key: String): AudioInputDevice? {
        if (key.isEmpty()) return null
        return selectable(devices).firstOrNull { it.key == key }
    }
}
