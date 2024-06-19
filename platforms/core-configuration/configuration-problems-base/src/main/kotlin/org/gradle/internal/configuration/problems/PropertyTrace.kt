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

import org.gradle.internal.DisplayName
import org.gradle.internal.code.UserCodeSource
import org.gradle.problems.Location


/**
 * Subtypes are expected to support [PropertyTrace.equals] and [PropertyTrace.hashCode].
 *
 * Subclasses also must provide custom `toString()` implementations,
 * which should invoke [PropertyTrace.asString].
 */
/**
 * Subtypes are expected to support [PropertyTrace.equals] and [PropertyTrace.hashCode].
 *
 * Subclasses also must provide custom `toString()` implementations,
 * which should invoke [PropertyTrace.asString].
 */
sealed class PropertyTrace {

    object Unknown : PropertyTrace() {
        override fun toString(): String = asString()
        override fun equals(other: Any?): Boolean = other === this
        override fun hashCode(): Int = 0
        override fun describe(builder: StructuredMessage.Builder) {
            builder.text("unknown location")
        }
    }

    object Gradle : PropertyTrace() {
        override fun toString(): String = asString()
        override fun equals(other: Any?): Boolean = other === this
        override fun hashCode(): Int = 1
        override fun describe(builder: StructuredMessage.Builder) {
            builder.text("Gradle runtime")
        }
    }

    @Suppress("DataClassPrivateConstructor")
    data class BuildLogic private constructor(
        val source: DisplayName,
        val lineNumber: Int? = null
    ) : PropertyTrace() {
        constructor(location: Location) : this(location.sourceShortDisplayName, location.lineNumber)
        constructor(userCodeSource: UserCodeSource) : this(userCodeSource.displayName)
        override fun toString(): String = asString()
        override fun describe(builder: StructuredMessage.Builder) {
            with(builder) {
                text(source.displayName)
                lineNumber?.let {
                    text(": line $it")
                }
            }
        }
    }

    data class BuildLogicClass(
        val name: String
    ) : PropertyTrace() {
        override fun toString(): String = asString()
        override fun describe(builder: StructuredMessage.Builder) {
            with(builder) {
                text("class ")
                reference(name)
            }
        }
    }

    data class Task(
        val type: Class<*>,
        val path: String
    ) : PropertyTrace() {
        override fun toString(): String = asString()
        override fun describe(builder: StructuredMessage.Builder) {
            with(builder) {
                text("task ")
                reference(path)
                text(" of type ")
                reference(type.name)
            }
        }
    }

    data class Bean(
        val type: Class<*>,
        val trace: PropertyTrace
    ) : PropertyTrace() {
        override val containingUserCodeMessage: StructuredMessage
            get() = trace.containingUserCodeMessage
        override fun toString(): String = asString()
        override fun describe(builder: StructuredMessage.Builder) {
            with(builder) {
                reference(type.name)
                text(" bean found in ")
            }
        }
    }

    data class Property(
        val kind: PropertyKind,
        val name: String,
        val trace: PropertyTrace
    ) : PropertyTrace() {
        override val containingUserCodeMessage: StructuredMessage
            get() = trace.containingUserCodeMessage
        override fun toString(): String = asString()
        override fun describe(builder: StructuredMessage.Builder) {
            with(builder) {
                text("$kind ")
                reference(name)
                text(" of ")
            }
        }
    }

    data class Project(
        val path: String,
        val trace: PropertyTrace
    ) : PropertyTrace() {
        override val containingUserCodeMessage: StructuredMessage
            get() = trace.containingUserCodeMessage
        override fun toString(): String = asString()
        override fun describe(builder: StructuredMessage.Builder) {
            with(builder) {
                text("project ")
                reference(path)
                text(" in ")
            }
        }
    }

    data class SystemProperty(
        val name: String,
        val trace: PropertyTrace
    ) : PropertyTrace() {
        override val containingUserCodeMessage: StructuredMessage
            get() = trace.containingUserCodeMessage
        override fun toString(): String = asString()
        override fun describe(builder: StructuredMessage.Builder) {
            with(builder) {
                text("system property ")
                reference(name)
                text(" set at ")
            }
        }
    }

    abstract override fun equals(other: Any?): Boolean

    abstract override fun hashCode(): Int

    abstract override fun toString(): String

    /**
     * The shared logic for implementing `toString()` in subclasses.
     *
     * Renders this trace as a string (including a nested trace if it exists).
     */
    protected
    fun asString(): String = StructuredMessage.Builder().apply {
        sequence.forEach {
            it.describe(this)
        }
    }.build().render(BACKTICK)

    /**
     * Renders this trace using [BACKTICK] for wrapping symbols.
     */
    fun render(): String = asString()

    /**
     * The user code where the problem occurred. User code should generally be some coarse-grained entity such as a plugin or script.
     */
    open val containingUserCode: String
        get() = containingUserCodeMessage.render(BACKTICK)

    open val containingUserCodeMessage: StructuredMessage
        get() = StructuredMessage.Builder().also {
            describe(it)
        }.build()

    abstract fun describe(builder: StructuredMessage.Builder)

    val sequence: Sequence<PropertyTrace>
        get() = sequence {
            var trace = this@PropertyTrace
            while (true) {
                yield(trace)
                trace = trace.tail ?: break
            }
        }

    private
    val tail: PropertyTrace?
        get() = when (this) {
            is Bean -> trace
            is Property -> trace
            is SystemProperty -> trace
            is Project -> trace
            else -> null
        }
}
