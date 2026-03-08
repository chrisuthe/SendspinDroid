package com.sendspindroid.ui.compose

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.sendspindroid.ui.main.ServerListScreen
import com.sendspindroid.ui.theme.SendSpinTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests that ServerListScreen displays the empty state when no servers exist.
 *
 * The empty state shows a welcome hero card with:
 * - Welcome title
 * - Welcome subtitle
 * - "Add Your First Server" button
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ServerListEmptyStateTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun serverListScreen_noServers_showsWelcomeTitle() {
        composeTestRule.setContent {
            SendSpinTheme {
                ServerListScreen(
                    savedServers = emptyList(),
                    discoveredServers = emptyList(),
                    onlineSavedServerIds = emptySet(),
                    isScanning = false,
                    serverStatuses = emptyMap(),
                    reconnectInfo = emptyMap(),
                    onServerClick = {},
                    onServerLongClick = {},
                    onQuickConnectClick = {},
                    onAddServerClick = {}
                )
            }
        }

        composeTestRule.onNodeWithText("Welcome to SendspinDroid").assertIsDisplayed()
    }

    @Test
    fun serverListScreen_noServers_showsAddFirstServerButton() {
        composeTestRule.setContent {
            SendSpinTheme {
                ServerListScreen(
                    savedServers = emptyList(),
                    discoveredServers = emptyList(),
                    onlineSavedServerIds = emptySet(),
                    isScanning = false,
                    serverStatuses = emptyMap(),
                    reconnectInfo = emptyMap(),
                    onServerClick = {},
                    onServerLongClick = {},
                    onQuickConnectClick = {},
                    onAddServerClick = {}
                )
            }
        }

        composeTestRule.onNodeWithText("Add Your First Server").assertIsDisplayed()
    }

    @Test
    fun serverListScreen_noServers_showsSubtitle() {
        composeTestRule.setContent {
            SendSpinTheme {
                ServerListScreen(
                    savedServers = emptyList(),
                    discoveredServers = emptyList(),
                    onlineSavedServerIds = emptySet(),
                    isScanning = false,
                    serverStatuses = emptyMap(),
                    reconnectInfo = emptyMap(),
                    onServerClick = {},
                    onServerLongClick = {},
                    onQuickConnectClick = {},
                    onAddServerClick = {}
                )
            }
        }

        composeTestRule.onNodeWithText(
            "Stream high-quality audio from your SendSpin or Music Assistant server"
        ).assertIsDisplayed()
    }
}
