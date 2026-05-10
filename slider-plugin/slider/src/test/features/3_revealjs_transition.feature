#noinspection CucumberUndefinedStep
@cucumber @slider @transition
Feature: Reveal.js transition configuration

  Scenario: Default transition is "slide" not "linear"
    Given a new Slider project
    When I am executing the task 'tasks'
    Then the build should succeed
    And the DeckContext "revealjs" should contain field "transition"

  Scenario: Transition attribute is no longer hardcoded as "linear"
    Given a new Slider project
    When I am executing the task 'tasks'
    Then the build should succeed
    And the build output should not contain "linear"

  Scenario: Per-slide transition override is exposed in SlideHint model
    Given a new Slider project
    When I am executing the task 'tasks'
    Then the build should succeed
    And the DeckContext "slides" should contain field "transition"
