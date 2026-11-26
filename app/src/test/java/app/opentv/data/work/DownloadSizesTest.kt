/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.work

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DownloadSizesTest {
    @Test fun `total comes from the content range`() {
        assertEquals(1234567L, DownloadSizes.contentRangeTotal("bytes 0-0/1234567"))
        assertNull(DownloadSizes.contentRangeTotal("bytes 0-0/*"))
        assertNull(DownloadSizes.contentRangeTotal("bytes 0-0"))
        assertNull(DownloadSizes.contentRangeTotal(null))
    }

    @Test fun `sizes print in the units people read`() {
        assertEquals("1.4 GB", DownloadSizes.format(1_400_000_000L))
        assertEquals("700 MB", DownloadSizes.format(700_000_000L))
        assertEquals("512 KB", DownloadSizes.format(512_000L))
        assertEquals("12 B", DownloadSizes.format(12L))
    }

    @Test fun `a season is the sample times its episodes`() {
        assertEquals(8_000_000_000L, DownloadSizes.seasonEstimate(800_000_000L, 10))
        assertNull(DownloadSizes.seasonEstimate(null, 10))
        assertNull(DownloadSizes.seasonEstimate(800L, 0))
    }

    @Test fun `the name fragment is the longest real word`() {
        assertEquals("Interstellar", DownloadSizes.nameFragment("4K - Interstellar (2014)"))
        assertEquals("Blade", DownloadSizes.nameFragment("Blade 2049"))
        assertNull(DownloadSizes.nameFragment("Up"))
    }
}
