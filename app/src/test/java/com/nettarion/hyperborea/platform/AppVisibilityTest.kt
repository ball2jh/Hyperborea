package com.nettarion.hyperborea.platform

import android.app.Activity
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AppVisibilityTest {
    @Test fun `late service observes an already started activity without inventing another start`() {
        val visibility = AppVisibility()
        val activity = Activity()
        visibility.onActivityStarted(activity)
        val observedByService = visibility.foreground
        assertThat(observedByService.value).isTrue()
        visibility.onActivityStopped(activity)
        assertThat(observedByService.value).isFalse()
        visibility.onActivityStarted(activity)
        visibility.onActivityStopped(activity)
        assertThat(observedByService.value).isFalse()
    }

    @Test fun `activity handoff remains foreground until the last activity stops`() {
        val visibility = AppVisibility()
        val first = Activity()
        val second = Activity()
        visibility.onActivityStarted(first)
        visibility.onActivityStarted(second)
        visibility.onActivityStopped(first)
        visibility.onActivityDestroyed(first)
        assertThat(visibility.foreground.value).isTrue()
        visibility.onActivityStopped(second)
        assertThat(visibility.foreground.value).isFalse()
    }

    @Test fun `duplicate lifecycle delivery cannot leave a phantom foreground activity`() {
        val visibility = AppVisibility()
        val activity = Activity()
        visibility.onActivityStarted(activity)
        visibility.onActivityStarted(activity)
        visibility.onActivityStopped(activity)
        assertThat(visibility.foreground.value).isFalse()
    }
}
