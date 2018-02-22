package com.sourceforgery.tachikoma.maildelivery

import org.jsoup.helper.StringUtil
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.select.NodeTraversor
import org.jsoup.select.NodeVisitor

/**
 * HTML to plain-text. This example program demonstrates the use of jsoup to convert HTML input to lightly-formatted
 * plain-text. That is divergent from the general goal of jsoup's .text() methods, which is to get clean data from a
 * scrape.
 * @author Jonathan Hedley, jonathan@hedley.net
 */
class HtmlToPlainText {

    /**
     * Format an Element to plain-text
     * @param element the root element to format
     * @return formatted text
     */
    fun getPlainText(element: Element): String {
        val formatter = FormattingVisitor()
        NodeTraversor.traverse(formatter, element) // walk the DOM, and call .head() and .tail() for each node

        return formatter.toString()
    }

    // the formatting rules, implemented in a breadth-first DOM traverse
    private inner class FormattingVisitor : NodeVisitor {
        private var width = 0
        private val accum = StringBuilder() // holds the accumulated text

        // hit when the node is first seen
        override fun head(node: Node, depth: Int) {
            val name = node.nodeName()
            when {
                node is TextNode -> append(node.text()) // TextNodes carry all user-readable text in the DOM.
                name == "li" -> append("\n * ")
                name == "dt" -> append("  ")
                StringUtil.`in`(name, "p", "h1", "h2", "h3", "h4", "h5", "tr") -> append("\n")
            }
        }

        // hit when all of the node's children (if any) have been visited
        override fun tail(node: Node, depth: Int) {
            val name = node.nodeName()
            if (StringUtil.`in`(name, "br", "dd", "dt", "p", "h1", "h2", "h3", "h4", "h5")) {
                append("\n")
            } else if (name == "a") {
                append(String.format(" <%s>", node.absUrl("href")))
            }
        }

        // appends text to the string builder with a simple word wrap method
        private fun append(text: String) {
            if (text.startsWith("\n")) {
                // reset counter if starts with a newline. only from formats above, not in natural text
                width = 0
            }
            if (text == " " && (accum.isEmpty() || StringUtil.`in`(accum.substring(accum.length - 1), " ", "\n"))) {
                // don't accumulate long runs of empty spaces
                return
            }

            if (text.length + width > maxWidth) { // won't fit, needs to wrap
                val words = text.split("\\s+".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
                for (i in words.indices) {
                    var word = words[i]
                    val last = i == words.size - 1
                    if (!last) {
                        // insert a space if not the last word
                        word += " "
                    }
                    if (word.length + width > maxWidth) { // wrap and reset counter
                        if (accum.isNotEmpty() && accum.last() == ' ') {
                            // If last char was a space, remove it as we are adding a line break
                            accum.deleteCharAt(accum.length - 1)
                        }
                        accum.append("\n").append(word)
                        width = word.length
                    } else {
                        accum.append(word)
                        width += word.length
                    }
                }
            } else {
                // fits as is, without need to wrap text
                accum.append(text)
                width += text.length
            }
        }

        override fun toString(): String {
            return accum.toString()
        }
    }

    companion object {
        private const val maxWidth = 80
    }
}