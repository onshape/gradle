/*
 * Copyright 2024 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.internal.configuration.problems

import kotlin.reflect.KClass


public
const val BACKTICK = '`'


/**
 * @see prefer [BACKTICK] if wrapping strings that may already use single quotes
 */
private
const val SINGLE_QUOTE = '\''


data class StructuredMessage(val fragments: List<Fragment>) {

    /**
     * Renders a message as a string using the given delimiter for symbol references.
     *
     * We conventionally use either [BACKTICK] or [SINGLE_QUOTE] for wrapping symbol references.
     *
     * For the configuration cache report, we should favor [BACKTICK] over [SINGLE_QUOTE] as
     * quoted fragments may already contain single quotes which are used elsewhere.
     */
    fun render(quote: Char = SINGLE_QUOTE) = fragments.joinToString(separator = "") { fragment ->
        when (fragment) {
            is Fragment.Text -> fragment.text
            is Fragment.Reference -> "$quote${fragment.name}$quote"
        }
    }

    override fun toString(): String = render()

    sealed class Fragment {

        data class Text(val text: String) : Fragment()

        data class Reference(val name: String) : Fragment()
    }

    companion object {

        fun forText(text: String) = StructuredMessage(listOf(Fragment.Text(text)))

        fun build(builder: StructuredMessageBuilder) = StructuredMessage(
            Builder().apply(builder).fragments
        )
    }

    class Builder {

        internal
        val fragments = mutableListOf<Fragment>()

        fun text(string: String): Builder = apply {
            fragments.add(Fragment.Text(string))
        }

        fun reference(name: String): Builder = apply {
            fragments.add(Fragment.Reference(name))
        }

        fun reference(type: Class<*>): Builder = apply {
            reference(type.name)
        }

        fun reference(type: KClass<*>): Builder = apply {
            reference(type.qualifiedName!!)
        }

        fun message(message: StructuredMessage): Builder = apply {
            fragments.addAll(message.fragments)
        }

        fun build(): StructuredMessage = StructuredMessage(fragments.toList())
    }
}
