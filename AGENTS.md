# AGENT GUIDELINES for slider-gradle

This document outlines the conventions and commands for agentic coding in the `slider-gradle` repository. Adhering to these guidelines ensures consistency, maintainability, and efficient collaboration.

## 1. Build, Lint, and Test Commands

This project primarily uses Gradle with Kotlin DSL.

### General Commands

*   **Build Slides**:
    ```bash
    ./gradlew -i asciidoctorRevealJs
    ```
    _Compiles AsciiDoc sources into a Reveal.js HTML presentation._

*   **Clean Generated Slides**:
    ```bash
    ./gradlew cleanSlidesBuild
    ```
    _Deletes presentation artifacts from the `build` directory._

*   **Serve Slides Locally**:
    ```bash
    ./gradlew -i serveSlides
    ```
    _Serves the generated slides using `npx serve` for local preview._

*   **Publish Slides**:
    ```bash
    ./gradlew publishSlides
    ```
    _Deploys generated slides to the configured remote repository (e.g., GitHub Pages)._

### Testing Commands

The project defines three main test tasks: `test` (unit tests), `functionalTest` (functional tests using Gradle Test Kit), and `cucumberTest` (Cucumber BDD tests).

*   **Run All Tests (Unit, Functional, Cucumber)**:
    ```bash
    ./gradlew check
    ```
    _Executes all defined test tasks (`test`, `functionalTest`, `cucumberTest`) and other verification tasks._

*   **Run Unit Tests**:
    ```bash
    ./gradlew test
    ```
    _Runs standard JUnit Platform unit tests, excluding Cucumber and functional tests._

*   **Run Functional Tests**:
    ```bash
    ./gradlew functionalTest
    ```
    _Executes functional tests designed to verify plugin behavior using `GradleTestKit`._

*   **Run Cucumber Tests**:
    ```bash
    ./gradlew cucumberTest
    ```
    _Executes Cucumber BDD feature files and step definitions._

### Running a Single Test

For JUnit Platform-based tests (used by `test` and `functionalTest` tasks):

*   **Run a specific test method**:
    ```bash
    ./gradlew test --tests "com.cheroliv.slider.SliderPluginTest.someTestMethod"
    ./gradlew functionalTest --tests "com.cheroliv.slider.SliderPluginFunctionalTests.anotherFeatureTest"
    ```
    _Replace `com.cheroliv.slider.SliderPluginTest` with the fully qualified class name and `someTestMethod` with the method name._

*   **Run all tests in a specific class**:
    ```bash
    ./gradlew test --tests "com.cheroliv.slider.SliderPluginTest"
    ```

For Cucumber tests (`cucumberTest`):
Running single scenarios or features directly via Gradle command line requires more specific Cucumber configurations not explicitly defined for easy single execution in `build.gradle.kts`. It's generally recommended to run the full `cucumberTest` task.

### Linting / Code Style Checks

Explicit linting tasks (like `ktlint` or `detekt`) are not currently configured as separate Gradle tasks. Basic linting and style checks are performed implicitly during the Kotlin compilation process. Agents should strive to adhere to the code style guidelines outlined below.

## 2. Code Style Guidelines

The project follows standard Kotlin coding conventions, with specific adaptations for Gradle Kotlin DSL.

### Imports

*   **Explicit Imports**: Always use explicit import statements. Wildcard imports (`*`) should be avoided.
*   **Ordering**: Group imports by package, typically with standard library/Kotlin imports first, followed by third-party libraries, and then project-specific imports. Ensure consistency with existing files.

### Formatting

*   **Indentation**: Use 4 spaces for indentation, not tabs.
*   **Braces**: Opening curly braces (`{`) for classes, functions, and control structures should be on the same line as the declaration.
*   **Chained Calls**: When chaining multiple method calls, place each call on a new line and indent it to improve readability.
    ```kotlin
    project.layout.buildDirectory.get().asFile
        .resolve("docs")
        .resolve("asciidocRevealJs")
        .absolutePath
    ```
*   **Blank Lines**: Use blank lines to separate logical blocks of code for better readability.

### Types

*   **Type Inference**: Leverage Kotlin's type inference capabilities where the type is obvious from the context. Explicit type declarations are only necessary when clarity is improved or inference is not possible.
    ```kotlin
    val localConf: SlidesConfiguration = /* ... */ // Explicit for complex types
    val slidesDir = listOf(...) // Inferred for simple collections
    ```

### Naming Conventions

*   **Packages**: `lowercase.with.dots` (e.g., `slides`, `com.cheroliv.slider`).
*   **Classes and Objects**: `UpperCamelCase` (e.g., `SlidesPlugin`, `SlidesConfiguration`).
*   **Functions and Variables**: `lowerCamelCase` (e.g., `apply`, `deckFile`, `adocFiles`).
*   **Constants**: `SCREAMING_SNAKE_CASE` (e.g., `GROUP_TASK_SLIDER`, `TASK_ASCIIDOCTOR_REVEALJS`).
*   **Enum Entries**: `SCREAMING_SNAKE_CASE`.

### Error Handling

*   **Gradle Context**: Errors within Gradle tasks are typically handled by Gradle's execution model, which reports exceptions.
*   **File Operations**: When interacting with the filesystem, perform checks (e.g., `file.exists()`) before attempting operations that might fail (e.g., `file.delete()`).
*   **Logging**: Use `project.logger.info`, `project.logger.warn`, or `project.logger.error` for logging relevant information or potential issues.
*   **Exception Handling**: Use `try-catch` blocks only when specific recovery logic is required. Avoid broad `catch (Exception)` blocks. `Result` types are not commonly used for error propagation in the existing codebase for simple error cases.

### General Guidelines

*   **Readability**: Prioritize clear and readable code. If a complex logic needs to be implemented, consider breaking it down into smaller, well-named functions.
*   **Immutability**: Prefer immutable variables (`val`) over mutable ones (`var`) whenever possible.
*   **Extension Functions**: Utilize Kotlin extension functions to add functionality to existing classes without modifying their source code, especially for Gradle `Project` or `File` objects, as seen in `SlidesManager.kt`.
*   **Comments**: Add comments to explain *why* a particular piece of code is written, especially for non-obvious logic or workarounds. Avoid commenting on *what* the code does if it's self-explanatory.

## 3. Cursor/Copilot Rules

No specific `.cursor/rules/` directory, `.cursorrules` file, or `.github/copilot-instructions.md` file were found in this repository. Agents should follow the general code style guidelines provided above.