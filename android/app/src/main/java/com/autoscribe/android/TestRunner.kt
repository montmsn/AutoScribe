package com.autoscribe.android

import android.app.KeyguardManager
import android.content.Context
import android.os.PowerManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

enum class Step(val res: Int) {
    IDLE(R.string.step_idle),
    PROTOCOL(R.string.step_protocol),
    DISCONNECT(R.string.step_disconnect),
    CONNECT(R.string.step_connect),
    WAIT_USER(R.string.step_wait_user),
    WAIT_USER_DISCONNECT(R.string.step_wait_user_disconnect),
    PING(R.string.step_ping),
    PAUSED(R.string.step_paused),
    DONE(R.string.step_done),
    KEPT(R.string.step_kept),
    ERROR(R.string.step_error),
}

data class RunState(
    val running: Boolean = false,
    val step: Step = Step.IDLE,
    val index: Int = 0,
    val total: Int = 0,
    val target: String = "",
    val detail: String = "",
)

/** Runs the test plan: route by route (so the protocol only changes once per route), server by server. */
object TestRunner {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    private val _state = MutableStateFlow(RunState())
    val state: StateFlow<RunState> = _state

    private val _results = MutableStateFlow<List<TestResult>>(emptyList())
    val results: StateFlow<List<TestResult>> = _results

    private val _sessions = MutableStateFlow<List<TestSession>>(emptyList())
    /** All sessions, newest first. */
    val sessions: StateFlow<List<TestSession>> = _sessions

    /** The session shown on the main screen. */
    private val _current = MutableStateFlow<String?>(null)
    val current: StateFlow<String?> = _current

    fun loadResults(store: Store) {
        val results = store.loadResults()
        val sessions = store.loadSessions().toMutableList()
        // Results saved before sessions existed become one finished "earlier results" session.
        val legacy = results.filter { it.session == TestSession.LEGACY }
        if (legacy.isNotEmpty() && sessions.none { it.id == TestSession.LEGACY }) {
            val protocols = LinkedHashMap<String, List<Int>>()
            legacy.groupBy { it.protocol }.forEach { (p, rs) -> protocols[p] = rs.map { it.port }.distinct() }
            val s = TestSession(
                TestSession.LEGACY, legacy.minOf { it.time }, legacy.map { it.popKey }.distinct(), protocols, finished = true,
            )
            store.saveSession(s)
            sessions += s
        }
        _results.value = results
        _sessions.value = sessions.sortedByDescending { it.created }
        if (_current.value == null || _sessions.value.none { it.id == _current.value }) {
            _current.value = _sessions.value.firstOrNull()?.id
        }
    }

    fun clear(store: Store) {
        store.clearResults()
        _results.value = emptyList()
        _sessions.value = emptyList()
        _current.value = null
    }

    fun select(id: String) {
        _current.value = id
    }

    fun delete(store: Store, id: String) {
        if (job?.isActive == true && _current.value == id) return
        store.deleteSession(id)
        _current.value = null
        loadResults(store)
    }

    fun session(id: String?): TestSession? = _sessions.value.firstOrNull { it.id == id }

    fun resultsOf(id: String?): List<TestResult> = _results.value.filter { it.session == id }

    /** A new session for the current selection; servers already tested anywhere are skipped if set. */
    fun newSession(store: Store, pops: List<Pop>): Pair<TestSession, List<Pair<Route, Pop>>> {
        val selected = store.selectedPops
        val available = if (store.freeAccount) pops.filter { !it.premium } else pops
        val targets = if (selected.isEmpty()) available else available.filter { it.key in selected }
        val session = TestSession(
            id = System.currentTimeMillis().toString(),
            created = System.currentTimeMillis(),
            pops = targets.map { it.key },
            protocols = store.protocols,
        )
        return session to plan(store, pops)
    }

    /** What [session] still has to test. */
    fun remaining(session: TestSession, pops: List<Pop>): List<Pair<Route, Pop>> {
        val byKey = pops.associateBy { it.key }
        val done = resultsOf(session.id).map { Triple(it.popKey, it.protocol, it.port) }.toSet()
        val out = mutableListOf<Pair<Route, Pop>>()
        for ((proto, ports) in session.protocols) for (port in ports) for (key in session.pops) {
            val pop = byKey[key] ?: continue // no longer in the server list
            if (Triple(key, proto, port) !in done) out += Route(proto, port) to pop
        }
        return out
    }

    fun plan(store: Store, pops: List<Pop>): List<Pair<Route, Pop>> {
        val selected = store.selectedPops
        val available = if (store.freeAccount) pops.filter { !it.premium } else pops
        val targets = if (selected.isEmpty()) available else available.filter { it.key in selected }
        val tested = if (store.skipTested) _results.value.map { Triple(it.popKey, it.protocol, it.port) }.toSet() else emptySet()
        val out = mutableListOf<Pair<Route, Pop>>()
        for ((proto, ports) in store.protocols) for (port in ports) for (pop in targets) {
            if (Triple(pop.key, proto, port) !in tested) out += Route(proto, port) to pop
        }
        return out
    }

    fun start(context: Context, store: Store, session: TestSession, tasks: List<Pair<Route, Pop>>) {
        if (job?.isActive == true || tasks.isEmpty()) return
        val app = context.applicationContext
        store.saveSession(session)
        _sessions.value = (listOf(session) + _sessions.value.filter { it.id != session.id }).sortedByDescending { it.created }
        _current.value = session.id
        _state.value = RunState(running = true, total = tasks.size)
        job = scope.launch {
            var finalStep = Step.DONE
            var detail = ""
            try {
                finalStep = run(app, store, session.id, tasks)
                if (finalStep == Step.DONE) {
                    val done = session.copy(finished = true)
                    store.saveSession(done)
                    _sessions.value = _sessions.value.map { if (it.id == done.id) done else it }
                }
            } catch (e: AutomationException) {
                finalStep = Step.ERROR
                detail = e.message ?: ""
            } catch (e: CancellationException) {
                finalStep = Step.IDLE
                throw e
            } finally {
                _state.value = _state.value.copy(running = false, step = finalStep, detail = detail)
                withContext(NonCancellable + Dispatchers.Main) {
                    WindscribeAccessibilityService.instance?.bringBack()
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
    }

    private fun update(step: Step, detail: String = "") {
        _state.value = _state.value.copy(step = step, detail = detail)
    }

    private suspend fun waitVpn(context: Context, up: Boolean, timeoutMs: Long): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (Vpn.isUp(context) == up) return true
            val failed = WindscribeAccessibilityService.instance
                ?.let { s -> withContext(Dispatchers.Main) { s.dismissPopups() } } == true
            // Windscribe gave up on this route itself; no point waiting out the timeout.
            if (failed && up) return false
            delay(400)
        }
        return Vpn.isUp(context) == up
    }

    private fun isLocked(context: Context): Boolean {
        val power = context.getSystemService(PowerManager::class.java)
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        return power?.isInteractive == false || keyguard?.isKeyguardLocked == true
    }

    /** Pauses while the phone is locked: Windscribe cannot be operated behind the lock screen. */
    private suspend fun awaitUnlocked(context: Context) {
        if (!isLocked(context)) return
        val previous = _state.value.step
        update(Step.PAUSED)
        while (isLocked(context)) delay(1000)
        delay(1500) // let the launcher or the previous app settle after unlocking
        update(previous)
    }

    /** Runs one Windscribe UI action; if the phone got locked meanwhile, waits for unlock and retries once. */
    private suspend fun automate(context: Context, action: suspend () -> Unit) {
        awaitUnlocked(context)
        try {
            withContext(Dispatchers.Main) { action() }
        } catch (e: AutomationException) {
            if (!isLocked(context)) throw e
            awaitUnlocked(context)
            withContext(Dispatchers.Main) { action() }
        }
    }

    private suspend fun run(context: Context, store: Store, sessionId: String, tasks: List<Pair<Route, Pop>>): Step {
        val assisted = store.assisted
        val service = WindscribeAccessibilityService.instance
        if (!assisted && service == null) throw AutomationException("the accessibility service is off")
        if (store.keepScreenOn) withContext(Dispatchers.Main) { service?.setKeepScreenOn(true) }
        try {
            return runTasks(context, store, sessionId, tasks, assisted, service)
        } finally {
            withContext(NonCancellable + Dispatchers.Main) { service?.setKeepScreenOn(false) }
        }
    }

    private suspend fun runTasks(
        context: Context,
        store: Store,
        sessionId: String,
        tasks: List<Pair<Route, Pop>>,
        assisted: Boolean,
        service: WindscribeAccessibilityService?,
    ): Step {
        var currentRoute: Route? = null

        for ((i, task) in tasks.withIndex()) {
            if (!coroutineContext.isActive) return Step.IDLE
            val (route, pop) = task
            _state.value = _state.value.copy(index = i + 1, target = "${pop.label} (${pop.country}) · ${route.label}")

            val t0: Long
            val connected: Boolean
            if (assisted) {
                update(Step.WAIT_USER)
                if (Vpn.isUp(context)) waitVpn(context, false, 120_000)
                update(Step.WAIT_USER)
                t0 = System.currentTimeMillis()
                connected = waitVpn(context, true, 180_000)
            } else {
                service!!
                if (Vpn.isUp(context)) {
                    update(Step.DISCONNECT)
                    automate(context) { service.toggleConnection() }
                    if (!waitVpn(context, false, 15_000)) throw AutomationException("Windscribe did not disconnect")
                }
                if (route != currentRoute) {
                    update(Step.PROTOCOL)
                    automate(context) { service.setRoute(route) }
                    currentRoute = route
                }
                update(Step.CONNECT)
                t0 = System.currentTimeMillis()
                connected = try {
                    automate(context) { service.connectTo(pop) }
                    waitVpn(context, true, store.connectTimeout * 1000L)
                } catch (e: LocationMissingException) {
                    false // recorded as a failure below; move on to the next server
                }
            }
            val duration = (System.currentTimeMillis() - t0) / 1000.0

            // A VPN interface can be up while nothing passes through it (WireGuard always looks "up"),
            // so the ping decides whether the route really works.
            var ping: PingResult? = null
            if (connected) {
                update(Step.PING)
                delay(1000)
                ping = withContext(Dispatchers.IO) { Pinger.run(store.pingHost, store.pingCount) }
            }
            val ok = connected && ping != null && ping.loss < 100.0
            val result = TestResult(
                session = sessionId,
                popKey = pop.key, label = pop.label, country = pop.country,
                protocol = route.protocol, port = route.port,
                status = if (ok) TestResult.STATUS_SUCCESS else TestResult.STATUS_FAILURE,
                duration = duration, ping = ping?.avg, loss = ping?.loss, time = System.currentTimeMillis(),
            )
            withContext(Dispatchers.IO) { store.append(result) }
            _results.value = _results.value + result

            if (ok && store.stopOnSuccess) return Step.KEPT
            if (assisted) {
                if (i < tasks.size - 1) {
                    update(Step.WAIT_USER_DISCONNECT)
                    waitVpn(context, false, 120_000)
                }
            } else if (Vpn.isUp(context)) {
                update(Step.DISCONNECT)
                automate(context) { service!!.toggleConnection() }
                waitVpn(context, false, 15_000)
            }
        }
        return Step.DONE
    }

    fun best(results: List<TestResult>): List<TestResult> =
        results.filter { it.ok && it.ping != null }
            .sortedWith(compareBy<TestResult> { it.loss ?: 100.0 }.thenBy { it.ping })
}
