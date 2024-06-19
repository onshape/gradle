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

import org.gradle.internal.problems.failure.Failure


/**
 * A problem that does not necessarily compromise the execution of the build.
 */
data class PropertyProblem(
    val trace: PropertyTrace,
    val message: StructuredMessage,
    val exception: Throwable? = null,
    /**
     * A failure containing stack tracing information.
     * The failure may be synthetic when the cause of the problem was not an exception.
     */
    val stackTracingFailure: Failure? = null,
    val documentationSection: DocumentationSection? = null
)


// TODO:configuration-cache extract interface and move enum back to :configuration-cache
enum class DocumentationSection(val anchor: String) {
    NotYetImplemented("config_cache:not_yet_implemented"),
    NotYetImplementedSourceDependencies("config_cache:not_yet_implemented:source_dependencies"),
    NotYetImplementedJavaSerialization("config_cache:not_yet_implemented:java_serialization"),
    NotYetImplementedTestKitJavaAgent("config_cache:not_yet_implemented:testkit_build_with_java_agent"),
    NotYetImplementedBuildServiceInFingerprint("config_cache:not_yet_implemented:build_services_in_fingerprint"),
    TaskOptOut("config_cache:task_opt_out"),
    RequirementsBuildListeners("config_cache:requirements:build_listeners"),
    RequirementsDisallowedTypes("config_cache:requirements:disallowed_types"),
    RequirementsExternalProcess("config_cache:requirements:external_processes"),
    RequirementsTaskAccess("config_cache:requirements:task_access"),
    RequirementsSysPropEnvVarRead("config_cache:requirements:reading_sys_props_and_env_vars"),
    RequirementsUseProjectDuringExecution("config_cache:requirements:use_project_during_execution")
}


typealias StructuredMessageBuilder = StructuredMessage.Builder.() -> Unit


enum class PropertyKind {
    Field {
        override fun toString() = "field"
    },
    PropertyUsage {
        override fun toString() = "property usage"
    },
    InputProperty {
        override fun toString() = "input property"
    },
    OutputProperty {
        override fun toString() = "output property"
    }
}
