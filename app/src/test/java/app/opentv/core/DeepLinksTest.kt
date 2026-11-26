/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeepLinksTest {
    @Test fun `reads the query from a search link`() {
        assertEquals("bbc one", DeepLinks.searchQuery("opentv://search?q=bbc%20one"))
        assertEquals("bbc one", DeepLinks.searchQuery("opentv://search?q=bbc+one"))
        assertEquals("sky news", DeepLinks.searchQuery("OpenTV://Search?query=sky%20news"))
        assertEquals("film", DeepLinks.searchQuery("opentv://search/?x=1&q=film"))
    }

    @Test fun `a bare search link opens an empty search`() {
        assertEquals("", DeepLinks.searchQuery("opentv://search"))
        assertEquals("", DeepLinks.searchQuery("opentv://search?q="))
    }

    @Test fun `other links are not search links`() {
        assertNull(DeepLinks.searchQuery(null))
        assertNull(DeepLinks.searchQuery("opentv://searching?q=a"))
        assertNull(DeepLinks.searchQuery("opentv://play?q=a"))
        assertNull(DeepLinks.searchQuery("https://search?q=a"))
    }

    @Test fun `the query is tidied and capped`() {
        assertEquals("a b", DeepLinks.searchQuery("opentv://search?q=%20a%0A%09%20b%20"))
        assertEquals(DeepLinks.MAX_QUERY, DeepLinks.searchQuery("opentv://search?q=" + "x".repeat(500))!!.length)
        // A malformed escape must not crash the launch.
        DeepLinks.searchQuery("opentv://search?q=%zz")
    }
}
