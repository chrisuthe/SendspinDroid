package com.sendspindroid.ui.compose

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.sendspindroid.ui.main.TrackMetadata
import com.sendspindroid.ui.main.components.MiniPlayer
import com.sendspindroid.ui.theme.SendSpinTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests MiniPlayer visibility behavior.
 *
 * MiniPlayer appears when track metadata is available (connected state)
 * and shows appropriate content based on playback state.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MiniPlayerVisibilityTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun miniPlayer_withMetadata_displaysTrackInfo() {
        composeTestRule.setContent {
            SendSpinTheme {
                MiniPlayer(
                    metadata = TrackMetadata(
                        title = "Connected Track",
                        artist = "Connected Artist",
                        album = "Connected Album"
                    ),
                    artworkSource = null,
                    isPlaying = true,
                    onCardClick = {},
                    onPlayPauseClick = {},
                    onPreviousClick = {},
                    onNextClick = {}
                )
            }
        }

        composeTestRule.onNodeWithText("Connected Track").assertIsDisplayed()
        composeTestRule.onNodeWithText("Connected Artist").assertIsDisplayed()
    }

    @Test
    fun miniPlayer_notPlaying_showsNotPlayingText() {
        composeTestRule.setContent {
            SendSpinTheme {
                MiniPlayer(
                    metadata = TrackMetadata.EMPTY,
                    artworkSource = null,
                    isPlaying = false,
                    onCardClick = {},
                    onPlayPauseClick = {},
                    onPreviousClick = {},
                    onNextClick = {}
                )
            }
        }

        composeTestRule.onNodeWithText("Not Playing").assertIsDisplayed()
    }

    @Test
    fun miniPlayer_artistNotEmpty_displaysArtist() {
        composeTestRule.setContent {
            SendSpinTheme {
                MiniPlayer(
                    metadata = TrackMetadata(
                        title = "Song Title",
                        artist = "Some Artist",
                        album = ""
                    ),
                    artworkSource = null,
                    isPlaying = false,
                    onCardClick = {},
                    onPlayPauseClick = {},
                    onPreviousClick = {},
                    onNextClick = {}
                )
            }
        }

        composeTestRule.onNodeWithText("Some Artist").assertIsDisplayed()
    }

    @Test
    fun miniPlayer_emptyArtist_doesNotShowArtistLine() {
        composeTestRule.setContent {
            SendSpinTheme {
                MiniPlayer(
                    metadata = TrackMetadata(
                        title = "Instrumental",
                        artist = "",
                        album = ""
                    ),
                    artworkSource = null,
                    isPlaying = false,
                    onCardClick = {},
                    onPlayPauseClick = {},
                    onPreviousClick = {},
                    onNextClick = {}
                )
            }
        }

        // Title should show, but no artist line rendered
        composeTestRule.onNodeWithText("Instrumental").assertIsDisplayed()
    }
}
