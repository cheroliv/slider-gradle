#noinspection CucumberUndefinedStep
@cucumber @slider
Feature: proposeDeckContext — RAG-assisted deck context generation

  # ---------------------------------------------------------------------------
  # Parameter validation
  # ---------------------------------------------------------------------------

  Scenario: Missing subject property causes build failure
    Given a new Slider project
    When I execute the task 'proposeDeckContext' without any properties
    Then the build should fail
    And the build output should contain "Missing required property -Psubject"

  # ---------------------------------------------------------------------------
  # Output file naming convention
  # ---------------------------------------------------------------------------

  Scenario: Output file is named after the subject slug
    Given a new Slider project
    And a mock LLM that returns a valid DeckContext JSON for subject "Kotlin Coroutines"
    When I execute the task 'proposeDeckContext' with properties:
      | subject  | Kotlin Coroutines |
      | language | fr                |
    Then the build should succeed
    And the file "slides/misc/kotlin-coroutines-deck-context.yml" should exist

  #noinspection NonAsciiCharacters
  Scenario: Subject with accents is slugified correctly
    Given a new Slider project
    And a mock LLM that returns a valid DeckContext JSON for subject "Programmation réactive"
    When I execute the task 'proposeDeckContext' with properties:
      | subject  | Programmation réactive |
      | language | fr                     |
    Then the build should succeed
    And the file "slides/misc/programmation-reactive-deck-context.yml" should exist

  Scenario: Language code does not appear in the context filename
    Given a new Slider project
    And a mock LLM that returns a valid DeckContext JSON for subject "Spring Boot"
    When I execute the task 'proposeDeckContext' with properties:
      | subject  | Spring Boot |
      | language | en          |
    Then the build should succeed
    And the file "slides/misc/spring-boot-deck-context.yml" should exist
    And no file matching "slides/misc/*_en-deck-context.yml" should exist

  Scenario: Custom output path overrides the default naming
    Given a new Slider project
    And a mock LLM that returns a valid DeckContext JSON for subject "Kotlin Coroutines"
    When I execute the task 'proposeDeckContext' with properties:
      | subject  | Kotlin Coroutines              |
      | language | fr                             |
      | output   | slides/misc/custom-context.yml |
    Then the build should succeed
    And the file "slides/misc/custom-context.yml" should exist

  # ---------------------------------------------------------------------------
  # YAML content — valid and well-formed DeckContext
  # ---------------------------------------------------------------------------

  Scenario: Generated deck-context.yml is parseable as a valid DeckContext
    Given a new Slider project
    And a mock LLM that returns a valid DeckContext JSON for subject "Kotlin Coroutines"
    When I execute the task 'proposeDeckContext' with properties:
      | subject  | Kotlin Coroutines |
      | language | fr                |
    Then the build should succeed
    And the file "slides/misc/kotlin-coroutines-deck-context.yml" should be a valid DeckContext

  Scenario: Generated DeckContext contains the expected subject
    Given a new Slider project
    And a mock LLM that returns a valid DeckContext JSON for subject "Kotlin Coroutines"
    When I execute the task 'proposeDeckContext' with properties:
      | subject  | Kotlin Coroutines |
      | language | fr                |
    Then the build should succeed
    And the DeckContext field "subject" should equal "Kotlin Coroutines"

  Scenario: Generated DeckContext contains the expected language
    Given a new Slider project
    And a mock LLM that returns a valid DeckContext JSON for subject "Kotlin Coroutines"
    When I execute the task 'proposeDeckContext' with properties:
      | subject  | Kotlin Coroutines |
      | language | fr                |
    Then the build should succeed
    And the DeckContext field "language" should equal "fr"

  Scenario: Generated DeckContext outputFile follows the naming convention
    Given a new Slider project
    And a mock LLM that returns a valid DeckContext JSON for subject "Kotlin Coroutines"
    When I execute the task 'proposeDeckContext' with properties:
      | subject  | Kotlin Coroutines |
      | language | fr                |
    Then the build should succeed
    And the DeckContext "outputFile" should match the pattern "<slug>_<lang>-deck.adoc"

  # ---------------------------------------------------------------------------
  # Author
  # ---------------------------------------------------------------------------

  Scenario: Author is taken from explicit properties
    Given a new Slider project
    And a mock LLM that returns a valid DeckContext JSON for subject "Kotlin Coroutines"
    When I execute the task 'proposeDeckContext' with properties:
      | subject      | Kotlin Coroutines    |
      | language     | fr                   |
      | author.name  | cheroliv             |
      | author.email | cheroliv@example.com |
    Then the build should succeed
    And the DeckContext author name should equal "cheroliv"
    And the DeckContext author email should equal "cheroliv@example.com"

  # ---------------------------------------------------------------------------
  # Full pipeline — @integration tag, excluded from the normal check
  # ---------------------------------------------------------------------------


  @integration
  Scenario: Full pipeline with Ollama produces a valid deck-context file
    Given a new Slider project
    And an Ollama instance is available
    When I execute the task 'proposeDeckContext' with properties:
      | subject      | Kotlin Coroutines    |
      | language     | fr                   |
      | author.name  | cheroliv             |
      | author.email | cheroliv@example.com |
    Then the build should succeed
    And the file "slides/misc/kotlin-coroutines-deck-context.yml" should exist
    And the file "slides/misc/kotlin-coroutines-deck-context.yml" should be a valid DeckContext
