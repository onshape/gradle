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

import org.gradle.api.internal.file.temp.TemporaryFileProvider
import org.gradle.api.logging.Logger
import org.gradle.api.logging.Logging
import org.gradle.internal.concurrent.ManagedExecutor
import org.gradle.internal.configuration.problems.DecoratedReportProblem
import org.gradle.internal.configuration.problems.PropertyProblem
import org.gradle.internal.hash.Hashing
import org.gradle.internal.hash.HashingOutputStream
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Callable
import java.util.concurrent.TimeUnit


val logger: Logger = Logging.getLogger("org.gradle.problem.reporting")


sealed class State {

    open fun onDiagnostic(kind: DiagnosticKind, problem: PropertyProblem): State =
        illegalState()

    /**
     * Writes the report file to the given [outputDirectory] if and only if
     * there are diagnostics to report.
     *
     * @return a pair with the new [State] and the written [File], which will be `null` when there are no diagnostics.
     */
    open fun commitReportTo(
        outputDirectory: File,
        details: ConfigurationCacheReportDetails
    ): Pair<State, File?> =
        illegalState()

    open fun close(): State =
        illegalState()

    private
    fun illegalState(): Nothing =
        throw IllegalStateException("Operation is not valid in ${javaClass.simpleName} state.")

    class Idle(
        private val onFirstDiagnostic: (kind: DiagnosticKind, problem: PropertyProblem) -> State
    ) : State() {

        /**
         * There's nothing to write, return null.
         */
        override fun commitReportTo(
            outputDirectory: File,
            details: ConfigurationCacheReportDetails
        ): Pair<State, File?> =
            this to null

        override fun onDiagnostic(kind: DiagnosticKind, problem: PropertyProblem): State =
            onFirstDiagnostic(kind, problem)

        override fun close(): State =
            this
    }

    class Spooling(
        spoolFileProvider: TemporaryFileProvider,
        val reportFileName: String,
        val executor: ManagedExecutor,
        /**
         * [JsonModelWriter] uses Groovy's [CharBuf] for fast json encoding.
         */
        val groovyJsonClassLoader: ClassLoader,
        val decorate: (PropertyProblem, ProblemSeverity) -> DecoratedReportProblem
    ) : State() {

        private
        val spoolFile = spoolFileProvider.createTemporaryFile(reportFileName, ".html")

        private
        val htmlReportTemplate = HtmlReportTemplate()

        private
        val hashingStream = HashingOutputStream(Hashing.md5(), spoolFile.outputStream().buffered())

        private
        val writer = HtmlReportWriter(hashingStream.writer(), htmlReportTemplate)

        init {
            executor.submit {
                Thread.currentThread().contextClassLoader = groovyJsonClassLoader
                writer.beginHtmlReport()
            }
        }

        override fun onDiagnostic(kind: DiagnosticKind, problem: PropertyProblem): State {
            executor.submit {
                val severity = when (kind) {
                    DiagnosticKind.PROBLEM -> ProblemSeverity.Failure
                    DiagnosticKind.INCOMPATIBLE_TASK -> ProblemSeverity.Warning
                    DiagnosticKind.INPUT -> ProblemSeverity.Info
                }
                writer.writeDiagnostic(kind, decorate(problem, severity))
            }
            return this
        }

        override fun commitReportTo(
            outputDirectory: File,
            details: ConfigurationCacheReportDetails
        ): Pair<State, File?> {

            val reportFile = try {
                executor
                    .submit(Callable {
                        closeHtmlReport(details)
                        moveSpoolFileTo(outputDirectory)
                    })
                    .get(30, TimeUnit.SECONDS)
            } finally {
                executor.shutdownAndAwaitTermination()
            }
            return Closed to reportFile
        }

        override fun close(): State {
            if (executor.isShutdown) {
                writer.close()
            } else {
                executor.submit {
                    writer.close()
                }
                executor.shutdown()
            }
            return Closed
        }

        private
        fun closeHtmlReport(details: ConfigurationCacheReportDetails) {
            writer.endHtmlReport(details)
            writer.close()
        }

        private
        fun ManagedExecutor.shutdownAndAwaitTermination() {
            shutdown()
            if (!awaitTermination(1, TimeUnit.SECONDS)) {
                val unfinishedTasks = shutdownNow()
                logger.warn(
                    "Configuration cache report is taking too long to write... "
                        + "The build might finish before the report has been completely written."
                )
                logger.info("Unfinished tasks: {}", unfinishedTasks)
            }
        }

        private
        fun moveSpoolFileTo(outputDirectory: File): File {
            val reportDir = outputDirectory.resolve(reportHash())
            val reportFile = reportDir.resolve("$reportFileName.html")
            if (!reportFile.exists()) {
                require(reportDir.mkdirs()) {
                    "Could not create configuration cache report directory '$reportDir'"
                }
                Files.move(spoolFile.toPath(), reportFile.toPath())
            }
            return reportFile
        }

        private
        fun reportHash() =
            hashingStream.hash().toCompactString()
    }

    object Closed : State() {
        override fun close(): State = this
    }
}
