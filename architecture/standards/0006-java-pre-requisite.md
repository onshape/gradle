# ADR-0006 - Gradle and Java as a pre-requisite

## Date

2024-06-20

## Context

Gradle is built on the JVM technology stack.
This means that Gradle requires a Java runtime to run.

Gradle has different runtime components:
* The Gradle Wrapper
* The Gradle Launcher
* The Gradle Daemon
* The Gradle worker processes
* The Gradle Tooling API client

Users first interact with the Gradle Wrapper or Launcher.
There have been discussions on embedding a Java runtime in the Gradle distribution.
However, this would not resolve the problem of the Gradle Wrapper needing a Java runtime to run.

In addition, some of these discussion included conversation about having a single Java version supported by the Gradle runtime.
However, this would limit the ability of the Gradle ecosystem of plugin authors to take advantage of new Java features and improvements.

## Decision

1. The Gradle Daemon, worker processes, and Tooling API client will support running on more than one Java version.
The exact versions supported will be determined by the Gradle version.
2. The Gradle distribution will never include a Java runtime.
Instead, we will leverage Java toolchains for the Daemon and worker processes.
3. To stop requiring a pre-installed Java runtime for the Gradle Wrapper and Launcher, we will, in the future, develop a native version of those.
4. Regarding the Tooling API client, it is the responsibility of the application embedding it to provide the Java runtime.

## Status

PROPOSED

## Consequences

- Finalize Daemon JVM toolchain support, including auto-provisioning.
- Continue investigation into native options for the Gradle Wrapper and Launcher.
