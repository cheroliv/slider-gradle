package com.cheroliv.slider.scenarios

import io.cucumber.junit.platform.engine.Constants.*
import org.junit.platform.suite.api.ConfigurationParameter
import org.junit.platform.suite.api.IncludeEngines
import org.junit.platform.suite.api.SelectClasspathResource
import org.junit.platform.suite.api.Suite

/**
 * Test runner for Cucumber
 *
 * Tags excluded from the normal check:
 * - @wip        : scenarios under development
 * - @integration: scenarios requiring a real LLM (Ollama) — run manually
 */
@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("features")
@ConfigurationParameter(key = GLUE_PROPERTY_NAME, value = "com.cheroliv.slider.scenarios")
@ConfigurationParameter(
    key = PLUGIN_PROPERTY_NAME,
    value = "pretty, html:build/reports/cucumber.html, json:build/reports/cucumber.json"
)
@ConfigurationParameter(key = FEATURES_PROPERTY_NAME, value = "src/test/features")
@ConfigurationParameter(key = FILTER_TAGS_PROPERTY_NAME, value = "not @wip and not @integration")
class CucumberTestRunner