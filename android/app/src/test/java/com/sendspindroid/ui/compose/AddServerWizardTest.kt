package com.sendspindroid.ui.compose

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import com.sendspindroid.ui.theme.SendSpinTheme
import com.sendspindroid.ui.wizard.AddServerWizardScreen
import com.sendspindroid.ui.wizard.WizardState
import com.sendspindroid.ui.wizard.WizardStep
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests that AddServerWizardScreen renders the correct step content
 * as the wizard state progresses forward and backward.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AddServerWizardTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun wizard_clientTypeStep_showsTitle() {
        composeTestRule.setContent {
            SendSpinTheme {
                AddServerWizardScreen(
                    state = WizardState(currentStep = WizardStep.ClientType),
                    onClose = {},
                    onBack = {},
                    onNext = {},
                    onSkip = {},
                    onSave = {},
                    onStepAction = {}
                )
            }
        }

        // The top bar shows "Add Server" title for ClientType step
        composeTestRule.onNodeWithText("Add Server").assertIsDisplayed()
    }

    @Test
    fun wizard_findServerStep_showsFindServerTitle() {
        composeTestRule.setContent {
            SendSpinTheme {
                AddServerWizardScreen(
                    state = WizardState(currentStep = WizardStep.SS_FindServer),
                    onClose = {},
                    onBack = {},
                    onNext = {},
                    onSkip = {},
                    onSave = {},
                    onStepAction = {}
                )
            }
        }

        // The top bar shows "Find Server" title
        composeTestRule.onAllNodesWithText("Find Your Server")[0].assertIsDisplayed()
    }

    @Test
    fun wizard_finishStep_showsSaveButton() {
        composeTestRule.setContent {
            SendSpinTheme {
                AddServerWizardScreen(
                    state = WizardState(currentStep = WizardStep.SS_Finish),
                    onClose = {},
                    onBack = {},
                    onNext = {},
                    onSkip = {},
                    onSave = {},
                    onStepAction = {}
                )
            }
        }

        // Finish step shows "Save" button instead of "Next"
        composeTestRule.onNodeWithText("Save").assertIsDisplayed()
    }

    @Test
    fun wizard_finishStep_showsBackButton() {
        composeTestRule.setContent {
            SendSpinTheme {
                AddServerWizardScreen(
                    state = WizardState(currentStep = WizardStep.SS_Finish),
                    onClose = {},
                    onBack = {},
                    onNext = {},
                    onSkip = {},
                    onSave = {},
                    onStepAction = {}
                )
            }
        }

        composeTestRule.onNodeWithText("Back").assertIsDisplayed()
    }

    @Test
    fun wizard_stepNavigation_forward_updatesContent() {
        var state by mutableStateOf(WizardState(currentStep = WizardStep.ClientType))

        composeTestRule.setContent {
            SendSpinTheme {
                AddServerWizardScreen(
                    state = state,
                    onClose = {},
                    onBack = {},
                    onNext = {},
                    onSkip = {},
                    onSave = {},
                    onStepAction = {}
                )
            }
        }

        // Initially on ClientType
        composeTestRule.onNodeWithText("Add Server").assertIsDisplayed()

        // Navigate forward to FindServer
        state = state.copy(currentStep = WizardStep.SS_FindServer)
        composeTestRule.waitForIdle()

        composeTestRule.onAllNodesWithText("Find Your Server")[0].assertIsDisplayed()
    }

    @Test
    fun wizard_stepNavigation_backward_updatesContent() {
        var state by mutableStateOf(WizardState(currentStep = WizardStep.SS_Finish))

        composeTestRule.setContent {
            SendSpinTheme {
                AddServerWizardScreen(
                    state = state,
                    onClose = {},
                    onBack = {},
                    onNext = {},
                    onSkip = {},
                    onSave = {},
                    onStepAction = {}
                )
            }
        }

        // Initially on Finish -- "Save Server" appears in both the top bar and step heading
        composeTestRule.onAllNodesWithText("Save Server")[0].assertIsDisplayed()

        // Navigate backward to FindServer
        state = state.copy(currentStep = WizardStep.SS_FindServer)
        composeTestRule.waitForIdle()

        composeTestRule.onAllNodesWithText("Find Your Server")[0].assertIsDisplayed()
    }

    @Test
    fun wizard_configStep_showsNextButton() {
        composeTestRule.setContent {
            SendSpinTheme {
                AddServerWizardScreen(
                    state = WizardState(currentStep = WizardStep.SS_FindServer),
                    onClose = {},
                    onBack = {},
                    onNext = {},
                    onSkip = {},
                    onSave = {},
                    onStepAction = {}
                )
            }
        }

        // Config steps show "Next" button
        composeTestRule.onNodeWithText("Next").assertIsDisplayed()
    }
}
