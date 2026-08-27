package com.shez.rawcam.preview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxyStoreTest {

    @Test fun `short takes use the default stride`() {
        assertEquals(5, ProxyStore.strideFor(152))
        assertEquals(31, ProxyStore.proxyCountFor(152))
    }

    @Test fun `a forty second take samples every fifth frame`() {
        assertEquals(5, ProxyStore.strideFor(960))
        assertEquals(192, ProxyStore.proxyCountFor(960))
    }

    @Test fun `the cap raises the stride rather than truncating the clip`() {
        val frames = 14_400
        val stride = ProxyStore.strideFor(frames)
        val count = ProxyStore.proxyCountFor(frames)
        assertEquals(12, stride)
        assertTrue("proxy count must respect the cap", count <= ProxyStore.MAX_PROXIES)
        // The whole-clip guarantee: the last sample lands within one stride of
        // the end, so sampling spans the take instead of stopping early.
        val last = ProxyStore.sourceIndexOf(count - 1, stride)
        assertEquals(14_388L, last)
        assertTrue("last sample must be near the end", last >= frames - stride)
    }

    @Test fun `the cap boundary keeps the default stride`() {
        // 6000 frames is exactly MAX_PROXIES * MIN_STRIDE.
        assertEquals(5, ProxyStore.strideFor(6_000))
        assertEquals(1_200, ProxyStore.proxyCountFor(6_000))
    }

    @Test fun `an empty clip yields no proxies`() {
        assertEquals(5, ProxyStore.strideFor(0))
        assertEquals(0, ProxyStore.proxyCountFor(0))
    }

    @Test fun `source index maps by stride`() {
        assertEquals(0L, ProxyStore.sourceIndexOf(0, 5))
        assertEquals(190L, ProxyStore.sourceIndexOf(38, 5))
    }
}
