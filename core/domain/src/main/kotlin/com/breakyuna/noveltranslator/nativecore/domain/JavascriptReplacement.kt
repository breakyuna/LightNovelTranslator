package com.breakyuna.noveltranslator.nativecore.domain

import com.google.re2j.Matcher

/** JavaScript GetSubstitution semantics, separate from rule-file backslash replacement syntax. */
internal object JavascriptReplacement {
    fun append(template: String, source: String, match: Matcher, named: Map<String, Int>,
        checkActive: () -> Unit, output: (String) -> Unit) {
        var index = 0
        while (index < template.length) {
            if (index % 256 == 0) checkActive()
            if (template[index] != '$' || index + 1 >= template.length) {
                output(template[index++].toString()); continue
            }
            val next = template[index + 1]
            when (next) {
                '$' -> { output("$"); index += 2 }
                '&' -> { output(match.group()); index += 2 }
                '`' -> { output(source.substring(0, match.start())); index += 2 }
                '\'' -> { output(source.substring(match.end())); index += 2 }
                '<' -> {
                    val end = template.indexOf('>', index + 2)
                    if (named.isNotEmpty() && end >= 0) {
                        output(named[template.substring(index + 2, end)]?.let { match.group(it) }.orEmpty())
                        index = end + 1
                    } else { output("$"); index++ }
                }
                in '0'..'9' -> {
                    val first = next - '0'
                    val second = template.getOrNull(index + 2)?.takeIf { it in '0'..'9' }?.minus('0')
                    val two = second?.let { first * 10 + it }
                    when {
                        two != null && two in 1..match.groupCount() -> { output(match.group(two).orEmpty()); index += 3 }
                        first in 1..match.groupCount() -> { output(match.group(first).orEmpty()); index += 2 }
                        else -> { output("$"); index++ }
                    }
                }
                else -> { output("$"); index++ }
            }
        }
    }
}
