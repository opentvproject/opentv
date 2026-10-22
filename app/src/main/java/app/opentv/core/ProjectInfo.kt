/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.core

/**
 * Who this build is, where its source lives, and under what licence.
 *
 * Every URL the app can open used to be written by hand in the screen that opens it, which meant
 * a fork had to hunt through the code to find them all. They are all here now.
 *
 * ## The source link is a licence obligation, not a convenience
 *
 * The GPL asks that anyone given the object code can also get the source it was built from. For
 * an Android app that means the link on the About screen has to point at the repository of *this*
 * build — not at the project it was forked from. A fork that ships an APK and points at someone
 * else's repository has not offered the corresponding source, and the copyleft has quietly
 * stopped holding.
 *
 * So [REPO_SLUG] is the fork this code lives in, and [UPSTREAM_REPO_SLUG] is the project it came
 * from. The first is what the app links to; the second is credited, not shipped from.
 */
object ProjectInfo {
    /** The repository that holds the source of *this* build. */
    const val REPO_SLUG = "T3lo5/opentv"

    /** The project this one is forked from. It is credited, not shipped from. */
    const val UPSTREAM_REPO_SLUG = "opentvproject/opentv"

    /**
     * The SPDX identifier.
     *
     * GitHub's badge may say "GPL-3.0" instead: it reads the licence text, and the text cannot
     * say "or later" without ceasing to be the licence verbatim.
     */
    const val LICENSE_SPDX = "GPL-3.0-or-later"

    /** The licence as a person reads it. */
    const val LICENSE_NAME = "GNU General Public License v3.0 or later"

    /** Where the licence itself is written down. */
    const val LICENSE_URL = "https://www.gnu.org/licenses/gpl-3.0.html"

    /** Where the source of this build is. This is the link the GPL requires. */
    val SOURCE_URL = "https://github.com/$REPO_SLUG"

    /** Where a bug in this build belongs. */
    val ISSUES_URL = "https://github.com/$REPO_SLUG/issues"

    /** Where the APKs are. There is no install page of our own — the releases are the one. */
    val RELEASES_URL = "https://github.com/$REPO_SLUG/releases"

    /** The project this one is forked from. */
    val UPSTREAM_URL = "https://github.com/$UPSTREAM_REPO_SLUG"
}
