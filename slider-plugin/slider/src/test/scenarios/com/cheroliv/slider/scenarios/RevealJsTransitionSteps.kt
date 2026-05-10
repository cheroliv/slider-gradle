package com.cheroliv.slider.scenarios

import com.cheroliv.slider.DeckContext
import com.cheroliv.slider.RevealJsContext
import com.cheroliv.slider.SlideHint
import io.cucumber.java.en.Then
import org.assertj.core.api.Assertions.assertThat
import kotlin.reflect.KClass
import kotlin.reflect.full.declaredMemberProperties
import kotlin.reflect.full.memberProperties

class RevealJsTransitionSteps(private val world: SliderWorld) {

    @Then("the build output should not contain {string}")
    fun buildOutputShouldNotContain(unexpected: String) {
        val output = world.buildResult?.output ?: ""
        assertThat(output)
            .describedAs("Build output should NOT contain '$unexpected'")
            .doesNotContain(unexpected)
    }

    private fun fieldExistsOn(clazz: KClass<*>, field: String): Boolean =
        clazz.memberProperties.any { it.name == field }

    @Then("the DeckContext {string} should contain field {string}")
    fun deckContextShouldContainField(model: String, field: String) {
        val clazz: KClass<*> = when (model) {
            "slides" -> SlideHint::class
            "revealjs" -> RevealJsContext::class
            "deck" -> DeckContext::class
            else -> error("Unknown model: $model")
        }
        assertThat(fieldExistsOn(clazz, field))
            .describedAs("$model should have field '$field'")
            .isTrue()
    }

    @Then("the DeckContext model should be valid")
    fun deckContextModelShouldBeValid() {
        val requiredFields = listOf("subject", "audience", "duration", "language", "outputFile", "author", "revealjs", "notes")
        val props = DeckContext::class.declaredMemberProperties.map { it.name }
        requiredFields.forEach { field ->
            assertThat(props)
                .describedAs("DeckContext must have field '$field'")
                .contains(field)
        }
    }
}
