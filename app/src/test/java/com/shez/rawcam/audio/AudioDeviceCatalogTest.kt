package com.shez.rawcam.audio

import android.media.AudioDeviceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioDeviceCatalogTest {

    private fun dev(id: Int, type: Int, name: String, ch: IntArray = intArrayOf(1, 2)) =
        AudioInputDevice(id = id, type = type, productName = name, channelCounts = ch)

    private val builtin = dev(1, AudioDeviceInfo.TYPE_BUILTIN_MIC, "Builtin")
    private val usb = dev(7, AudioDeviceInfo.TYPE_USB_DEVICE, "Scarlett Solo")
    private val wired = dev(3, AudioDeviceInfo.TYPE_WIRED_HEADSET, "Headset")
    private val sco = dev(9, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, "Buds")

    @Test
    fun `key combines type and product name`() {
        assertEquals("${AudioDeviceInfo.TYPE_USB_DEVICE}:Scarlett Solo", usb.key)
    }

    @Test
    fun `bluetooth types are excluded`() {
        assertTrue(AudioDeviceCatalog.isExcluded(AudioDeviceInfo.TYPE_BLUETOOTH_SCO))
        assertTrue(AudioDeviceCatalog.isExcluded(AudioDeviceInfo.TYPE_BLE_HEADSET))
        assertFalse(AudioDeviceCatalog.isExcluded(AudioDeviceInfo.TYPE_BUILTIN_MIC))
        assertFalse(AudioDeviceCatalog.isExcluded(AudioDeviceInfo.TYPE_USB_DEVICE))
    }

    @Test
    fun `selectable drops excluded devices and keeps order`() {
        assertEquals(listOf(builtin, usb), AudioDeviceCatalog.selectable(listOf(builtin, sco, usb)))
    }

    @Test
    fun `resolve finds an exact key match`() {
        assertEquals(usb, AudioDeviceCatalog.resolve(listOf(builtin, usb), usb.key))
    }

    @Test
    fun `resolve returns null for an absent device`() {
        assertNull(AudioDeviceCatalog.resolve(listOf(builtin), usb.key))
    }

    @Test
    fun `resolve returns null for a renamed device`() {
        val renamed = dev(7, AudioDeviceInfo.TYPE_USB_DEVICE, "Scarlett 2i2")
        assertNull(AudioDeviceCatalog.resolve(listOf(builtin, renamed), usb.key))
    }

    @Test
    fun `resolve ignores an unstable id change`() {
        // Same type and product name, new id after a reconnect: still a match.
        val reconnected = dev(42, AudioDeviceInfo.TYPE_USB_DEVICE, "Scarlett Solo")
        assertEquals(reconnected, AudioDeviceCatalog.resolve(listOf(reconnected), usb.key))
    }

    @Test
    fun `an empty key never resolves, meaning use the system default`() {
        assertNull(AudioDeviceCatalog.resolve(listOf(builtin, usb), ""))
    }

    @Test
    fun `resolve never returns an excluded device even on an exact key match`() {
        assertNull(AudioDeviceCatalog.resolve(listOf(sco), sco.key))
    }

    @Test
    fun `display names are human readable`() {
        assertEquals("Built-in mic", builtin.displayName)
        assertEquals("Wired headset", wired.displayName)
        assertEquals("USB: Scarlett Solo", usb.displayName)
    }

    @Test
    fun `preferred channels is stereo when offered, else mono`() {
        assertEquals(2, usb.preferredChannels)
        assertEquals(1, dev(5, AudioDeviceInfo.TYPE_BUILTIN_MIC, "Mono", intArrayOf(1)).preferredChannels)
    }
}
