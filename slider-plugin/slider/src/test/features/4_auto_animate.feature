#noinspection CucumberUndefinedStep
@cucumber @slider @auto-animate
Feature: Auto-Animate support in SlideHint and prompts

  Scenario: Auto-Animate fields are exposed in SlideHint data class
    Given a new Slider project
    When I am executing the task 'tasks'
    Then the build should succeed
    And the DeckContext "slides" should contain field "autoAnimate"

  Scenario: Model migration does not break existing deck generation
    Given a new Slider project
    When I am executing the task 'tasks'
    Then the build should succeed
    And the DeckContext model should be valid
