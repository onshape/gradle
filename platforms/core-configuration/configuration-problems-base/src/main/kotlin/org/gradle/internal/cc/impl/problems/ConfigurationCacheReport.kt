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

package org.gradle.internal.cc.impl.problems

import org.apache.groovy.json.internal.CharBuf
import org.gradle.api.internal.file.temp.TemporaryFileProvider
import org.gradle.internal.buildoption.InternalFlag
import org.gradle.internal.buildoption.InternalOptions
import org.gradle.internal.concurrent.ExecutorFactory
import org.gradle.internal.configuration.problems.DecoratedFailure
import org.gradle.internal.configuration.problems.DecoratedReportProblem
import org.gradle.internal.configuration.problems.FailureDecorator
import org.gradle.internal.configuration.problems.PropertyProblem
import org.gradle.internal.configuration.problems.StructuredMessage
import org.gradle.internal.hash.HashCode
import org.gradle.internal.hash.Hashing
import org.gradle.internal.problems.failure.Failure
import org.gradle.internal.service.scopes.Scope
import org.gradle.internal.service.scopes.ServiceScope
import java.io.Closeable
import java.io.File
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.contract


@ServiceScope(Scope.BuildTree::class)
class ConfigurationCacheReport(
    executorFactory: ExecutorFactory,
    temporaryFileProvider: TemporaryFileProvider,
    internalOptions: InternalOptions,
    reportFileName: String = "configuration-cache-report"
) : Closeable {

    companion object {
        private
        val stacktraceHashes = InternalFlag("org.gradle.configuration-cache.internal.report.stacktrace-hashes", false)
    }

    private
    val isStacktraceHashes = internalOptions.getOption(stacktraceHashes).get()

    private
    var state: State = State.Idle { kind, problem ->
        State.Spooling(
            temporaryFileProvider,
            reportFileName,
            executorFactory.create("Configuration cache report writer", 1),
            CharBuf::class.java.classLoader,
            ::decorateProblem
        ).onDiagnostic(kind, problem)
    }

    private
    val stateLock = Object()

    private
    val failureDecorator = FailureDecorator()

    private
    fun decorateProblem(problem: PropertyProblem, severity: ProblemSeverity): DecoratedReportProblem {
        val failure = problem.stackTracingFailure
        return DecoratedReportProblem(
            problem.trace,
            decorateMessage(problem, failure),
            decoratedFailureFor(failure, severity),
            problem.documentationSection
        )
    }

    private
    fun decoratedFailureFor(failure: Failure?, severity: ProblemSeverity): DecoratedFailure? {
        return when {
            failure != null -> failureDecorator.decorate(failure)
            severity == ProblemSeverity.Failure -> DecoratedFailure.MARKER
            else -> null
        }
    }

    private
    fun decorateMessage(problem: PropertyProblem, failure: Failure?): StructuredMessage {
        if (!isStacktraceHashes || failure == null) {
            return problem.message
        }

        val failureHash = failure.hashWithoutMessages()
        return StructuredMessage.build {
            reference("[${failureHash.toCompactString()}]")
            text(" ")
            message(problem.message)
        }
    }

    override fun close() {
        modifyState {
            close()
        }
    }

    fun onProblem(problem: PropertyProblem) {
        modifyState {
            onDiagnostic(DiagnosticKind.PROBLEM, problem)
        }
    }

    fun onIncompatibleTask(problem: PropertyProblem) {
        modifyState {
            onDiagnostic(DiagnosticKind.INCOMPATIBLE_TASK, problem)
        }
    }

    fun onInput(input: PropertyProblem) {
        modifyState {
            onDiagnostic(DiagnosticKind.INPUT, input)
        }
    }

    /**
     * Writes the report file to [outputDirectory].
     *
     * The file is laid out in such a way as to allow extracting the pure JSON model,
     * see [HtmlReportWriter].
     */

    fun writeReportFileTo(outputDirectory: File, details: ConfigurationCacheReportDetails): File? {
        var reportFile: File?
        modifyState {
            val (newState, outputFile) = commitReportTo(outputDirectory, details)
            reportFile = outputFile
            newState
        }
        return reportFile
    }

    @OptIn(ExperimentalContracts::class)
    private
    inline fun modifyState(f: State.() -> State) {
        contract {
            callsInPlace(f, kotlin.contracts.InvocationKind.EXACTLY_ONCE)
        }
        synchronized(stateLock) {
            state = state.f()
        }
    }

    /**
     * A heuristic to get the same hash for different instances of an exception
     * occurring at the same location.
     */
    private
    fun Failure.hashWithoutMessages(): HashCode {
        val root = this@hashWithoutMessages
        val hasher = Hashing.newHasher()
        for (failure in sequence { visitFailures(root) }) {
            hasher.putString(failure.exceptionType.name)
            for (element in failure.stackTrace) {
                hasher.putString(element.toString())
            }
        }
        return hasher.hash()
    }

    private
    suspend fun SequenceScope<Failure>.visitFailures(failure: Failure) {
        yield(failure)
        failure.suppressed.forEach { visitFailures(it) }
        failure.causes.forEach { visitFailures(it) }
    }
}
