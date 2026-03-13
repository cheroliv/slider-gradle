#noinspection CucumberUndefinedStep
@cucumber @slider
Feature: Minimal Slider configuration

  Scenario: Canary
    Given a new Slider project
    When I am executing the task 'tasks'
    Then the build should succeed
