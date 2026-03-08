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
 * Tests that track metadata (title, artist) renders correctly in the MiniPlayer.
 *
 * Uses MiniPlayer as the testable surface because NowPlayingScreen requires a
 * ViewModel. MiniPlayer accepts the same TrackMetadata and renders title/artist.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NowPlayingMetadataTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun miniPlayer_displaysTitle() {
        composeTestRule.setContent {
            SendSpinTheme {
                MiniPlayer(
                    metadata = TrackMetadata(
                        title = "Bohemian Rhapsody",
                        artist = "Queen",
                        album = "A Night at the Opera"
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

        composeTestRule.onNodeWithText("Bohemian Rhapsody").assertIsDisplayed()
    }

    @Test
    fun miniPlayer_displaysArtist() {
        composeTestRule.setContent {
            SendSpinTheme {
                MiniPlayer(
                    metadata = TrackMetadata(
                        title = "Bohemian Rhapsody",
                        artist = "Queen",
                        album = "A Night at the Opera"
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

        composeTestRule.onNodeWithText("Queen").assertIsDisplayed()
    }

    @Test
    fun miniPlayer_emptyMetadata_showsNotPlaying() {
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

        // When title is empty, MiniPlayer shows "Not Playing" from string resource
        composeTestRule.onNodeWithText("Not Playing").assertIsDisplayed()
    }

    @Test
    fun miniPlayer_displaysTimestamps_whenDurationProvided() {
        composeTestRule.setContent {
            SendSpinTheme {
                MiniPlayer(
                    metadata = TrackMetadata(
                        title = "Test Track",
                        artist = "Test Artist",
                        album = "Test Album"
                    ),
                    artworkSource = null,
                    isPlaying = false,
                    onCardClick = {},
                    onPlayPauseClick = {},
                    onPreviousClick = {},
                    onNextClick = {},
                    positionMs = 83000,
                    durationMs = 296000
                )
            }
        }

        // 83000ms = 1:23, 296000ms = 4:56
        composeTestRule.onNodeWithText("1:23 / 4:56").assertIsDisplayed()
    }
}
