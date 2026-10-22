/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * The two rules a row that the remote has to reach has to obey.
 *
 * Neither of them is a style question, and neither of them shows up in a unit test of
 * behaviour: a button that cannot be pressed still looks exactly like a button that can.
 * On a television, where the d-pad is the only input, the difference between the two is
 * the whole interface — and the only way it shows is on a box, in a living room, with
 * somebody watching.
 *
 * So they are checked here, over the source itself.
 */
class RemoteFocusRulesTest {

    @Test
    fun `every focusable row puts its clickable first`() {
        val offences = chains().filter { (_, chain) ->
            val focusable = chain.indexOf(".focusable()")
            val clickable = chain.indexOf(".clickable(")
            focusable >= 0 && clickable >= 0 && clickable > focusable
        }.map { it.first }

        assertThat(offences).isEmpty()
    }

    @Test
    fun `no focusable row changes its chain when it gains the focus`() {
        val offences = chains().filter { (_, chain) ->
            if (chain.indexOf(".focusable()") < 0) return@filter false
            // A modifier added conditionally — a border that only exists while the row is
            // focused, say — changes the shape of the chain, and a chain that changes
            // under a focus target takes the focus with it. The row is then a picture:
            // the arrows walk straight past it.
            val conditional = conditionalThens(chain)
            if (conditional.isEmpty()) return@filter false
            // A conditional before the focus state is watched is fine: that is the focus
            // requester, which is how the row is told to take the focus at all. One after
            // it is not.
            val watched = chain.indexOf(".onFocusChanged")
            conditional.any { watched < 0 || it > watched }
        }.map { it.first }

        assertThat(offences).isEmpty()
    }

    @Test
    fun `the scan finds the rows it is meant to check`() {
        // A scan that finds nothing passes every rule, which is the worst possible
        // outcome for a test like this one.
        assertThat(chains().size).isGreaterThan(5)
    }

    /** Every modifier chain with a focus target in it, as (where, chain) pairs. */
    private fun chains(): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        for (file in sources()) {
            val text = file.readText()
            var at = 0
            while (true) {
                val focus = text.indexOf(".focusable()", at)
                if (focus < 0) break
                out += Pair("${file.name}:${text.take(focus).count { it == '\n' } + 1}", chainAt(text, focus))
                at = focus + 1
            }
        }
        return out
    }

    /**
     * The modifier chain the focusable at [focus] belongs to.
     *
     * A chain is the run of calls on one modifier — `Modifier.a().b().c()` — which the
     * file may spread over several lines and which may itself be an argument of something
     * else. Parenthesis counting is what tells where the chain begins and ends: the chain
     * starts at the `Modifier` that heads it and ends where its last call closes.
     */
    private fun chainAt(text: String, focus: Int): String {
        var depth = 0
        var start = focus
        while (start > 0) {
            start--
            when (text[start]) {
                ')' -> depth++
                '(' -> if (depth == 0) { start++; break } else depth--
                else -> if (depth == 0 && text.startsWith("Modifier", start)) {
                    start += "Modifier".length
                    break
                }
            }
        }
        var end = focus
        depth = 0
        while (end < text.length) {
            when (text[end]) {
                '(' -> depth++
                ')' -> if (depth == 0) break else depth--
            }
            end++
        }
        return text.substring(start, end)
    }

    /** Every Kotlin source file the app is made of. */
    private fun sources(): List<File> {
        val roots = listOf(File("src/main/java"), File("src/main/kotlin")).filter { it.isDirectory }
        return roots.flatMap { dir -> dir.walkTopDown().filter { it.extension == "kt" }.toList() }
    }

    private companion object {
        /**
         * Where the modifiers that are added conditionally begin.
         *
         * A modifier that comes and goes with the state is a modifier that changes the
         * shape of the chain, and a chain that changes under a focus target takes the
         * focus with it.
         */
        fun conditionalThens(chain: String): List<Int> {
            val found = mutableListOf<Int>()
            var search = 0
            while (true) {
                val at = chain.indexOf(".then(", search)
                if (at < 0) return found
                if (argumentOf(chain, at + ".then".length).contains("if (")) found += at
                search = at + 1
            }
        }

        /** The text inside the parentheses that start at [open]. */
        private fun argumentOf(chain: String, open: Int): String {
            var depth = 0
            var index = open
            while (index < chain.length) {
                when (chain[index]) {
                    '(' -> depth++
                    ')' -> {
                        depth--
                        if (depth == 0) return chain.substring(open + 1, index)
                    }
                }
                index++
            }
            return ""
        }
    }
}
