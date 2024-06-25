/*
 * Copyright 2021 the original author or authors.
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

package org.gradle.api.internal.project;

import org.gradle.api.Project;
import org.gradle.api.internal.GradleInternal;
import org.gradle.api.internal.tasks.TaskDependencyUsageTracker;
import org.gradle.execution.taskgraph.TaskExecutionGraphInternal;
import org.gradle.internal.metaobject.DynamicObject;
import org.gradle.invocation.IsolatedProjectEvaluationListenerProvider;

import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

public class DefaultCrossProjectModelAccess implements CrossProjectModelAccess {
    private final GradleInternal gradle;
    private final ProjectRegistry<ProjectInternal> projectRegistry;
    private final IsolatedProjectEvaluationListenerProvider isolatedProjectEvaluationListenerProvider;

    public DefaultCrossProjectModelAccess(
        ProjectRegistry<ProjectInternal> projectRegistry,
        GradleInternal gradle,
        IsolatedProjectEvaluationListenerProvider isolatedProjectEvaluationListenerProvider
    ) {
        this.gradle = gradle;
        this.projectRegistry = projectRegistry;
        this.isolatedProjectEvaluationListenerProvider = isolatedProjectEvaluationListenerProvider;
    }

    @Override
    public ProjectInternal access(ProjectInternal referrer, ProjectInternal project) {
        return project;
    }

    @Override
    public ProjectInternal findProject(ProjectInternal referrer, ProjectInternal relativeTo, String path) {
        return projectRegistry.getProject(relativeTo.absoluteProjectPath(path));
    }

    @Override
    public Map<String, Project> getChildProjects(ProjectInternal referrer, ProjectInternal relativeTo) {
        return relativeTo.getChildProjectsUnchecked().entrySet().stream().collect(
            Collectors.toMap(
                Map.Entry::getKey,
                entry -> access(referrer, (ProjectInternal) entry.getValue())
            )
        );
    }

    @Override
    public Set<? extends ProjectInternal> getSubprojects(ProjectInternal referrer, ProjectInternal relativeTo) {
        return projectRegistry.getSubProjects(relativeTo.getPath()).stream()
            .map(this::asAllprojectsAwareProject)
            .collect(Collectors.toCollection(TreeSet::new));
    }

    @Override
    public Set<? extends ProjectInternal> getAllprojects(ProjectInternal referrer, ProjectInternal relativeTo) {
        return projectRegistry.getAllProjects(relativeTo.getPath()).stream()
            .map(this::asAllprojectsAwareProject)
            .collect(Collectors.toCollection(TreeSet::new));
    }

    @Override
    public GradleInternal gradleInstanceForProject(ProjectInternal referrerProject, GradleInternal gradle) {
        return gradle;
    }

    @Override
    public TaskDependencyUsageTracker taskDependencyUsageTracker(ProjectInternal referrerProject) {
        return null;
    }

    @Override
    public TaskExecutionGraphInternal taskGraphForProject(ProjectInternal referrerProject, TaskExecutionGraphInternal taskGraph) {
        return taskGraph;
    }

    @Override
    public DynamicObject parentProjectDynamicInheritedScope(ProjectInternal referrerProject) {
        ProjectInternal parent = referrerProject.getParent();
        return parent != null ? parent.getInheritedScope() : null;
    }

    private ProjectInternal asAllprojectsAwareProject(ProjectInternal project) {
        return project instanceof AllprojectsAwareProject
            ? project
            : new AllprojectsAwareProject(project, isolatedProjectEvaluationListenerProvider, gradle);
    }
}
