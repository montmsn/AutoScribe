package com.autoscribe.android

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Opens the main screen the way a launch does, so a crash on start fails the build. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LaunchTest {
    @Test
    fun mainScreenOpens() {
        Robolectric.buildActivity(MainActivity::class.java).setup()
    }
}
