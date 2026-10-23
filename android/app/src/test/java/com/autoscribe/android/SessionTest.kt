package com.autoscribe.android

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SessionTest {
    private val pops = listOf(
        Pop("Canada", "CA", "North America", "Toronto - Comfort Zone"),
        Pop("Kenya", "KE", "Africa", "Nairobi - Hakuna Matata"),
    )

    private fun result(session: String, pop: Pop, port: Int) = TestResult(
        session = session, popKey = pop.key, label = pop.label, country = pop.country,
        protocol = "wireguard", port = port, status = TestResult.STATUS_SUCCESS,
        duration = 1.0, ping = 50.0, loss = 0.0, time = 1L,
    )

    @Test
    fun resumeSkipsOnlyWhatThisSessionTested() {
        val store = Store(ApplicationProvider.getApplicationContext())
        store.clearResults()
        val session = TestSession("s1", 1L, pops.map { it.key }, linkedMapOf("wireguard" to listOf(443, 80)))
        store.saveSession(session)
        store.append(result("s1", pops[0], 443))
        store.append(result("other", pops[1], 443)) // another session's result must not count
        TestRunner.loadResults(store)

        val left = TestRunner.remaining(session, pops).map { it.second.key to it.first.port }
        assertEquals(3, left.size)
        assertTrue((pops[0].key to 443) !in left)
        assertTrue((pops[1].key to 443) in left)
    }

    @Test
    fun deleteRemovesSessionAndItsResults() {
        val store = Store(ApplicationProvider.getApplicationContext())
        store.clearResults()
        store.saveSession(TestSession("a", 1L, listOf(pops[0].key), linkedMapOf("wireguard" to listOf(443))))
        store.saveSession(TestSession("b", 2L, listOf(pops[1].key), linkedMapOf("wireguard" to listOf(443))))
        store.append(result("a", pops[0], 443))
        store.append(result("b", pops[1], 443))

        store.deleteSession("a")

        assertEquals(listOf("b"), store.loadSessions().map { it.id })
        assertEquals(listOf("b"), store.loadResults().map { it.session })
    }

    @Test
    fun oldResultsBecomeAnEarlierResultsSession() {
        val store = Store(ApplicationProvider.getApplicationContext())
        store.clearResults()
        store.append(result(TestSession.LEGACY, pops[0], 443))
        TestRunner.loadResults(store)
        val legacy = TestRunner.session(TestSession.LEGACY)!!
        assertTrue(legacy.finished)
        assertEquals(listOf(pops[0].key), legacy.pops)
    }
}
