# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

JBotWithUsV2 is a Java project using Gradle (Kotlin DSL) with JUnit 5 for testing. Group: `com.revonx`.

## Build Commands

```bash
./gradlew build          # Build the project
./gradlew test           # Run all tests
./gradlew test --tests "com.revonx.SomeTest.methodName"  # Run a single test
./gradlew clean build    # Clean and rebuild
```

## Architecture

- **Language**: Java
- **Build**: Gradle with Kotlin DSL (`build.gradle.kts`)
- **Testing**: JUnit 5 (Jupiter) with JUnit Platform launcher
- **Source layout**: Standard Gradle structure
  - `src/main/java/` — application code
  - `src/main/resources/` — application resources
  - `src/test/java/` — test code
  - `src/test/resources/` — test resources
- **Base package**: `com.revonx`
