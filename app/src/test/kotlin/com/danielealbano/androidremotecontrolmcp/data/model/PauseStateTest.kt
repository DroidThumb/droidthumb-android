package com.danielealbano.androidremotecontrolmcp.data.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("PauseState")
class PauseStateTest {
    @Nested
    @DisplayName("default values")
    inner class DefaultValues {
        @Test
        fun `default state is not paused`() {
            assertFalse(PauseState().isPaused)
        }
    }

    @Nested
    @DisplayName("isEffectivePause")
    inner class EffectivePause {
        @Test
        fun `not paused is never effectively paused`() {
            val state = PauseState(isPaused = false, resumeAtEpochMs = null)
            assertFalse(state.isEffectivePause(nowEpochMs = 1_000L))
        }

        @Test
        fun `paused indefinitely is always effectively paused`() {
            val state = PauseState(isPaused = true, resumeAtEpochMs = null)
            assertTrue(state.isEffectivePause(nowEpochMs = Long.MAX_VALUE))
        }

        @Test
        fun `timed pause is effective before its deadline`() {
            val state = PauseState(isPaused = true, resumeAtEpochMs = 10_000L)
            assertTrue(state.isEffectivePause(nowEpochMs = 9_999L))
        }

        @Test
        fun `timed pause is not effective once its deadline has passed`() {
            val state = PauseState(isPaused = true, resumeAtEpochMs = 10_000L)
            assertFalse(state.isEffectivePause(nowEpochMs = 10_000L))
        }
    }

    @Nested
    @DisplayName("serialization")
    inner class Serialization {
        @Test
        fun `toJson and fromJson round-trip, indefinite pause`() {
            val state = PauseState(isPaused = true, resumeAtEpochMs = null)
            assertEquals(state, PauseState.fromJson(state.toJson()))
        }

        @Test
        fun `toJson and fromJson round-trip, timed pause`() {
            val state = PauseState(isPaused = true, resumeAtEpochMs = 123_456L)
            assertEquals(state, PauseState.fromJson(state.toJson()))
        }

        @Test
        fun `fromJsonOrDefault returns default on invalid JSON`() {
            assertEquals(PauseState(), PauseState.fromJsonOrDefault("invalid json {{{"))
        }
    }
}
