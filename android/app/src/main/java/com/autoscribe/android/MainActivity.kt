package com.autoscribe.android

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.os.LocaleListCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {
    private val app get() = application as App
    private val store get() = app.store

    private lateinit var serviceStatus: TextView
    private lateinit var planSummary: TextView
    private lateinit var btnStart: MaterialButton
    private lateinit var stepTitle: TextView
    private lateinit var stepTarget: TextView
    private lateinit var ring: CircularProgressIndicator
    private lateinit var ringText: TextView
    private lateinit var locationsValue: TextView
    private lateinit var protocolsValue: TextView
    private lateinit var bestCard: View
    private lateinit var bestTitle: TextView
    private lateinit var bestSubtitle: TextView
    private lateinit var empty: TextView
    private lateinit var phases: LinearLayout
    private lateinit var statTested: TextView
    private lateinit var statWorking: TextView
    private lateinit var statRate: TextView
    private lateinit var statBest: TextView
    private lateinit var tabs: TabLayout
    private val adapter = ResultAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // After a crash, show the error on its own screen once, so it can be read even if the
        // normal screen crashes again. The next launch opens normally.
        app.takeCrash()?.let { showCrash(it); return }
        setContentView(R.layout.activity_main)
        val list = findViewById<RecyclerView>(R.id.list)
        // Must be set before inflating the header against the list (it builds RecyclerView.LayoutParams).
        list.layoutManager = LinearLayoutManager(this)
        // The header scrolls with the results, so the whole screen is one list.
        val header = layoutInflater.inflate(R.layout.header_main, list, false)

        val toolbar = header.findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.inflateMenu(R.menu.main)
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_history -> showHistory()
                R.id.action_settings -> showSettings()
                R.id.action_export -> exportCsv()
                R.id.action_dump -> dumpScreen()
                R.id.action_clear -> confirmClear()
                R.id.action_language -> chooseLanguage()
            }
            true
        }

        serviceStatus = header.findViewById(R.id.serviceStatus)
        planSummary = header.findViewById(R.id.planSummary)
        btnStart = header.findViewById(R.id.btnStart)
        stepTitle = header.findViewById(R.id.stepTitle)
        stepTarget = header.findViewById(R.id.stepTarget)
        ring = header.findViewById(R.id.ring)
        ringText = header.findViewById(R.id.ringText)
        locationsValue = header.findViewById(R.id.locationsValue)
        protocolsValue = header.findViewById(R.id.protocolsValue)
        bestCard = header.findViewById(R.id.bestCard)
        bestTitle = header.findViewById(R.id.bestTitle)
        bestSubtitle = header.findViewById(R.id.bestSubtitle)
        empty = header.findViewById(R.id.empty)
        phases = header.findViewById(R.id.phases)
        statTested = header.findViewById(R.id.statTested)
        statWorking = header.findViewById(R.id.statWorking)
        statRate = header.findViewById(R.id.statRate)
        statBest = header.findViewById(R.id.statBest)
        buildPhases()
        tabs = header.findViewById(R.id.tabs)

        serviceStatus.setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        header.findViewById<View>(R.id.cardLocations).setOnClickListener { showLocations() }
        header.findViewById<View>(R.id.cardProtocols).setOnClickListener { showProtocols() }
        header.findViewById<View>(R.id.btnConnectBest).setOnClickListener {
            TestRunner.best(TestRunner.resultsOf(TestRunner.current.value)).firstOrNull()?.let { connectToResult(it) }
        }
        btnStart.setOnClickListener { if (TestRunner.state.value.running) TestRunner.stop() else startTest() }

        tabs.addTab(tabs.newTab().setText(R.string.tab_best))
        tabs.addTab(tabs.newTab().setText(R.string.tab_results))
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) = renderResults()
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })

        list.adapter = ConcatAdapter(HeaderAdapter(header), adapter)
        val hero = header.findViewById<View>(R.id.hero)
        val heroTop = hero.paddingTop
        // targetSdk 35 draws edge to edge: keep the last card above the navigation bar.
        val basePadding = list.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(list) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            hero.setPadding(hero.paddingLeft, heroTop + bars.top, hero.paddingRight, hero.paddingBottom)
            val bottom = bars.bottom
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, basePadding + bottom)
            insets
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { TestRunner.state.collect { renderState(it) } }
                launch { TestRunner.results.collect { renderResults() } }
                launch { TestRunner.current.collect { renderResults(); renderState(TestRunner.state.value) } }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        renderService()
        renderPlan()
    }

    // ----------------------------------------------------------------- rendering

    private fun renderService() {
        val installed = packageManager.getLaunchIntentForPackage(WindscribeAccessibilityService.Ui.PACKAGE) != null
        serviceStatus.text = when {
            !installed -> getString(R.string.windscribe_missing)
            store.assisted -> getString(R.string.assisted_on)
            WindscribeAccessibilityService.instance != null -> getString(R.string.service_on)
            else -> getString(R.string.service_off)
        }
    }

    private fun renderPlan() {
        val selected = store.selectedPops
        val usable = if (store.freeAccount) app.pops.filter { !it.premium } else app.pops
        val locations = if (selected.isEmpty()) usable.size else usable.count { it.key in selected }
        val routes = store.protocols.values.sumOf { it.size }
        val pending = TestRunner.newSession(store, app.pops).second.size
        locationsValue.text = if (selected.isEmpty()) getString(R.string.locations_all, locations)
            else getString(R.string.locations_some, locations)
        protocolsValue.text = getString(R.string.protocols_value, store.protocols.size, routes)
        planSummary.text = getString(R.string.plan_summary, locations * routes, pending)
    }

    private fun renderState(s: RunState) {
        btnStart.setText(if (s.running) R.string.stop else R.string.start)
        btnStart.setIconResource(if (s.running) R.drawable.ic_stop else R.drawable.ic_play)
        ring.max = maxOf(1, s.total)
        ring.setProgressCompat(if (s.total == 0) 0 else s.index, true)
        ring.isIndeterminate = false
        ringText.text = when {
            s.running -> getString(R.string.ring_count, s.index, s.total)
            s.total > 0 && s.step == Step.DONE -> "✓"
            else -> "—"
        }
        stepTitle.text = getString(s.step.res)
        stepTarget.text = when {
            s.running -> s.target
            s.detail.isNotEmpty() -> s.detail
            else -> TestRunner.session(TestRunner.current.value)
                ?.let { getString(R.string.session_label, sessionName(it), sessionStatus(it)) }
                ?: getString(R.string.hero_hint)
        }
        renderPhases(s)
        if (!s.running) {
            renderService()
            renderPlan()
        }
    }

    /** Shows the results of the selected session. */
    private fun renderResults() {
        val results = TestRunner.resultsOf(TestRunner.current.value)
        val best = TestRunner.best(results)
        val working = results.count { it.ok }
        statTested.text = results.size.toString()
        statWorking.text = working.toString()
        statRate.text = if (results.isEmpty()) "—" else "${working * 100 / results.size}%"
        statBest.text = best.firstOrNull()?.ping?.let { "%.0f".format(it) } ?: "—"
        val top = best.firstOrNull()
        bestCard.visibility = if (top == null) View.GONE else View.VISIBLE
        if (top != null) {
            bestTitle.text = "${flag(top.countryCode)}  ${top.label} · ${top.route.label}"
            bestSubtitle.text = getString(R.string.best_detail, top.country, formatPing(top.ping))
        }
        val shown = if (tabs.selectedTabPosition == 1) results.asReversed() else best
        adapter.submit(shown, ranked = tabs.selectedTabPosition != 1)
        empty.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun formatPing(v: Double?) = v?.let { "%.0f".format(it) } ?: "-"

    // ----------------------------------------------------------------- start

    private fun startTest() {
        val service = WindscribeAccessibilityService.instance
        if (!store.assisted && service == null) {
            MaterialAlertDialogBuilder(this)
                .setMessage(R.string.need_service)
                .setPositiveButton(R.string.open_settings) { _, _ ->
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
            return
        }
        // An interrupted session can be picked up where it stopped.
        val last = TestRunner.session(TestRunner.current.value)
        val left = last?.takeIf { !it.finished }?.let { TestRunner.remaining(it, app.pops) }.orEmpty()
        if (last != null && left.isNotEmpty()) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.resume)
                .setMessage(getString(R.string.resume_or_new, sessionName(last), left.size))
                .setPositiveButton(R.string.resume) { _, _ -> launchRun(last, left) }
                .setNegativeButton(R.string.new_test) { _, _ -> startNew() }
                .setNeutralButton(R.string.cancel, null)
                .show()
        } else {
            startNew()
        }
    }

    private fun startNew() {
        val (session, tasks) = TestRunner.newSession(store, app.pops)
        if (tasks.isEmpty()) {
            Toast.makeText(this, R.string.nothing_to_test, Toast.LENGTH_LONG).show()
            return
        }
        launchRun(session, tasks)
    }

    private fun launchRun(session: TestSession, tasks: List<Pair<Route, Pop>>) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        TestRunner.start(this, store, session, tasks)
        ContextCompat.startForegroundService(this, Intent(this, TestService::class.java))
    }

    // ----------------------------------------------------------------- locations

    private fun showLocations() {
        val selected = store.selectedPops.toMutableSet()
        val pops = app.pops
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), 0)
        }
        val search = EditText(this).apply { hint = getString(R.string.search); setSingleLine() }
        val count = TextView(this)
        val list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(380))
        }
        root.addView(search)
        root.addView(count)
        root.addView(list)

        val locAdapter = LocationAdapter(pops, selected) {
            count.text = getString(R.string.selected_count, selected.size)
        }
        list.adapter = locAdapter
        count.text = getString(R.string.selected_count, selected.size)
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = locAdapter.filter(s?.toString().orEmpty())
        })

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.locations)
            .setView(root)
            .setPositiveButton(R.string.ok) { _, _ ->
                store.selectedPops = selected
                renderPlan()
            }
            .setNegativeButton(R.string.select_none) { _, _ ->
                store.selectedPops = emptySet()
                renderPlan()
            }
            .setNeutralButton(R.string.update_list) { _, _ -> updateList() }
            .show()
    }

    private fun updateList() {
        lifecycleScope.launch {
            val error = withContext(Dispatchers.IO) {
                runCatching { Locations.download(this@MainActivity) }.exceptionOrNull()
            }
            if (error == null) {
                app.reloadPops()
                renderPlan()
                Toast.makeText(this@MainActivity, getString(R.string.list_updated, app.pops.size), Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this@MainActivity, getString(R.string.list_failed, error.message), Toast.LENGTH_LONG).show()
            }
        }
    }

    // ----------------------------------------------------------------- protocols

    private fun showProtocols() {
        val enabled = store.protocols
        // All protocols in the user's order, followed by the disabled ones.
        val order = (enabled.keys + Protocols.DEFAULTS.keys).distinct().toMutableList()
        val ports = Protocols.DEFAULTS.mapValues { (id, d) -> (enabled[id] ?: d).toMutableList() }.toMutableMap()
        val on = enabled.keys.toMutableSet()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), 0)
        }

        fun rebuild() {
            root.removeAllViews()
            order.forEachIndexed { i, id ->
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                val check = CheckBox(this).apply {
                    text = Protocols.displayName(id)
                    isChecked = id in on
                    setOnCheckedChangeListener { _, c -> if (c) on += id else on -= id }
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }
                val portBtn = Button(this, null, android.R.attr.borderlessButtonStyle).apply {
                    text = ports[id]!!.joinToString(",").ifEmpty { "-" }
                    maxWidth = dp(120)
                    setSingleLine()
                    setOnClickListener {
                        val all = Protocols.DEFAULTS[id]!!
                        val checked = BooleanArray(all.size) { all[it] in ports[id]!! }
                        MaterialAlertDialogBuilder(this@MainActivity)
                            .setTitle(getString(R.string.ports_for, Protocols.displayName(id)))
                            .setMultiChoiceItems(all.map { it.toString() }.toTypedArray(), checked) { _, w, c -> checked[w] = c }
                            .setPositiveButton(R.string.ok) { _, _ ->
                                ports[id] = all.filterIndexed { k, _ -> checked[k] }.toMutableList()
                                rebuild()
                            }
                            .show()
                    }
                }
                val up = Button(this, null, android.R.attr.borderlessButtonStyle).apply {
                    text = "▲"
                    isEnabled = i > 0
                    setOnClickListener { order.add(i - 1, order.removeAt(i)); rebuild() }
                }
                val down = Button(this, null, android.R.attr.borderlessButtonStyle).apply {
                    text = "▼"
                    isEnabled = i < order.size - 1
                    setOnClickListener { order.add(i + 1, order.removeAt(i)); rebuild() }
                }
                listOf(up, down).forEach { it.minWidth = 0; it.minimumWidth = dp(40) }
                row.addView(check); row.addView(portBtn); row.addView(up); row.addView(down)
                root.addView(row)
            }
        }
        rebuild()

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.protocols)
            .setView(root)
            .setPositiveButton(R.string.ok) { _, _ ->
                val out = LinkedHashMap<String, List<Int>>()
                order.filter { it in on && ports[it]!!.isNotEmpty() }.forEach { out[it] = ports[it]!! }
                store.protocols = out
                renderPlan()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ----------------------------------------------------------------- settings & menu

    private fun showSettings() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        fun field(label: Int, value: String, number: Boolean): EditText {
            root.addView(TextView(this).apply { setText(label) })
            return EditText(this).apply {
                setText(value)
                setSingleLine()
                if (number) inputType = InputType.TYPE_CLASS_NUMBER
                root.addView(this)
            }
        }
        fun switch(label: Int, value: Boolean) = SwitchCompat(this).apply {
            setText(label)
            isChecked = value
            root.addView(this)
        }
        val host = field(R.string.ping_host, store.pingHost, false)
        val countField = field(R.string.ping_count, store.pingCount.toString(), true)
        val timeout = field(R.string.connect_timeout, store.connectTimeout.toString(), true)
        val stopFirst = switch(R.string.stop_on_success, store.stopOnSuccess)
        val skip = switch(R.string.skip_tested, store.skipTested)
        val free = switch(R.string.free_account, store.freeAccount)
        root.addView(TextView(this).apply { setText(R.string.free_account_hint); textSize = 12f })
        val keepOn = switch(R.string.keep_screen_on, store.keepScreenOn)
        root.addView(TextView(this).apply { setText(R.string.keep_screen_on_hint); textSize = 12f })
        val assisted = switch(R.string.assisted_mode, store.assisted)
        root.addView(TextView(this).apply { setText(R.string.assisted_hint); textSize = 12f })

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings)
            .setView(root)
            .setPositiveButton(R.string.ok) { _, _ ->
                store.pingHost = host.text.toString().trim().ifEmpty { "1.1.1.1" }
                store.pingCount = countField.text.toString().toIntOrNull()?.coerceIn(1, 50) ?: 5
                store.connectTimeout = timeout.text.toString().toIntOrNull()?.coerceIn(5, 120) ?: 25
                store.stopOnSuccess = stopFirst.isChecked
                store.skipTested = skip.isChecked
                store.freeAccount = free.isChecked
                store.keepScreenOn = keepOn.isChecked
                store.assisted = assisted.isChecked
                renderService()
                renderPlan()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun exportCsv() {
        val file = store.exportCsv(TestRunner.current.value)
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, getString(R.string.export_csv)))
    }

    /** Opens Windscribe, waits while the user goes to the screen in question, then saves its view tree. */
    private fun dumpScreen() {
        val service = WindscribeAccessibilityService.instance
        if (service == null) {
            Toast.makeText(this, R.string.need_service, Toast.LENGTH_LONG).show()
            return
        }
        val launch = packageManager.getLaunchIntentForPackage(WindscribeAccessibilityService.Ui.PACKAGE) ?: return
        Toast.makeText(this, R.string.dump_hint, Toast.LENGTH_LONG).show()
        startActivity(launch)
        lifecycleScope.launch {
            delay(DUMP_DELAY_MS)
            val dir = File(cacheDir, "exports").apply { mkdirs() }
            val file = File(dir, "windscribe_screen_${System.currentTimeMillis()}.txt")
            service.dumpScreen(file)
            service.bringBack()
            val uri = FileProvider.getUriForFile(this@MainActivity, "$packageName.files", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, getString(R.string.dump_screen)))
        }
    }

    private fun showCrash(trace: String) {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("crash", trace))
        val text = TextView(this).apply {
            text = getString(R.string.crash_title) + "\n\n" + trace
            setTextIsSelectable(true)
            textSize = 12f
            setPadding(dp(16), dp(48), dp(16), dp(16))
        }
        setContentView(android.widget.ScrollView(this).apply { addView(text) })
    }

    private fun sessionName(s: TestSession): String =
        if (s.id == TestSession.LEGACY) getString(R.string.earlier_results)
        else java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT)
            .format(java.util.Date(s.created))

    private fun sessionStatus(s: TestSession): String {
        val running = TestRunner.state.value.running && TestRunner.current.value == s.id
        val left = TestRunner.remaining(s, app.pops).size
        return when {
            running -> getString(R.string.session_running)
            s.finished || left == 0 -> getString(R.string.session_finished)
            else -> getString(R.string.session_paused, left)
        }
    }

    /** Every saved test run, newest first: open it, resume it, export it or delete it. */
    private fun showHistory() {
        val sessions = TestRunner.sessions.value
        if (sessions.isEmpty()) {
            Toast.makeText(this, R.string.no_history, Toast.LENGTH_LONG).show()
            return
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.history)
            .setView(android.widget.ScrollView(this).apply { addView(column) })
            .setNegativeButton(R.string.cancel, null)
            .create()
        for (s in sessions) {
            val results = TestRunner.resultsOf(s.id)
            val best = TestRunner.best(results).firstOrNull()
            val card = com.google.android.material.card.MaterialCardView(this).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = dp(8) }
                if (s.id == TestRunner.current.value) {
                    strokeColor = ContextCompat.getColor(this@MainActivity, R.color.brand)
                    strokeWidth = dp(2)
                }
                setOnClickListener { dialog.dismiss(); sessionActions(s) }
            }
            val body = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
            }
            body.addView(TextView(this).apply {
                text = sessionName(s)
                setTextAppearance(R.style.Value)
            })
            body.addView(TextView(this).apply {
                text = getString(
                    R.string.session_line, results.size, s.total, results.count { it.ok }, formatPing(best?.ping),
                )
                setTextAppearance(R.style.Label)
            })
            body.addView(TextView(this).apply {
                text = sessionStatus(s)
                textSize = 12f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(ContextCompat.getColor(this@MainActivity, if (s.finished) R.color.good else R.color.warn))
            })
            card.addView(body)
            column.addView(card)
        }
        dialog.show()
    }

    private fun sessionActions(s: TestSession) {
        val running = TestRunner.state.value.running
        val left = TestRunner.remaining(s, app.pops)
        val actions = mutableListOf<Pair<Int, () -> Unit>>()
        actions += R.string.open to { TestRunner.select(s.id) }
        if (!running && !s.finished && left.isNotEmpty()) actions += R.string.resume to { launchRun(s, left) }
        actions += R.string.export_csv to { TestRunner.select(s.id); exportCsv() }
        if (!(running && TestRunner.current.value == s.id)) actions += R.string.delete to { confirmDelete(s) }
        MaterialAlertDialogBuilder(this)
            .setTitle(sessionName(s))
            .setItems(actions.map { getString(it.first) }.toTypedArray()) { _, i -> actions[i].second() }
            .show()
    }

    private fun confirmDelete(s: TestSession) {
        MaterialAlertDialogBuilder(this)
            .setMessage(getString(R.string.delete_confirm, sessionName(s)))
            .setPositiveButton(R.string.delete) { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { TestRunner.delete(store, s.id) }
                    renderResults()
                    renderPlan()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmClear() {
        MaterialAlertDialogBuilder(this)
            .setMessage(R.string.clear_confirm)
            .setPositiveButton(R.string.ok) { _, _ -> TestRunner.clear(store); renderPlan() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun chooseLanguage() {
        val tags = arrayOf("", "en", "fa", "ru")
        val names = arrayOf(getString(R.string.language_system), "English", "فارسی", "Русский")
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.language)
            .setItems(names) { _, i ->
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tags[i]))
            }
            .show()
    }

    private fun connectToResult(r: TestResult) {
        val service = WindscribeAccessibilityService.instance
        val pop = app.pops.firstOrNull { it.key == r.popKey }
        if (service == null || pop == null || TestRunner.state.value.running) {
            Toast.makeText(this, getString(R.string.connect_manually, r.label, r.route.label), Toast.LENGTH_LONG).show()
            return
        }
        lifecycleScope.launch {
            try {
                if (Vpn.isUp(this@MainActivity)) {
                    service.toggleConnection()
                    delay(2500)
                }
                service.setRoute(r.route)
                service.connectTo(pop)
            } catch (e: AutomationException) {
                Toast.makeText(this@MainActivity, "${getString(R.string.step_error)}: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun copyResult(r: TestResult) {
        val text = "${r.label} (${r.country}) · ${r.route.label} · ${formatPing(r.ping)} ms"
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("route", text))
        Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
    }

    /** Builds the Disconnect → Route → Connect → Ping pills in the header. */
    private fun buildPhases() {
        PHASES.forEachIndexed { i, (_, label) ->
            phases.addView(TextView(this).apply {
                setText(label)
                textSize = 11f
                gravity = Gravity.CENTER
                setPadding(dp(4), dp(5), dp(4), dp(5))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (i > 0) marginStart = dp(6)
                }
            })
        }
    }

    private fun renderPhases(s: RunState) {
        phases.visibility = if (s.running) View.VISIBLE else View.GONE
        val active = PHASES.indexOfFirst { it.first.contains(s.step) }
        for (i in 0 until phases.childCount) {
            val pill = phases.getChildAt(i) as TextView
            val on = i == active
            pill.setBackgroundResource(if (on) R.drawable.bg_pill else R.drawable.bg_chip)
            pill.setTextColor(ContextCompat.getColor(this, if (on) R.color.brand_dark else R.color.on_hero))
            pill.alpha = if (on || i < active) 1f else 0.6f
            pill.setTypeface(null, if (on) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        }
    }

    /** Turns a two-letter country code into its flag emoji. */
    private fun flag(code: String): String {
        if (code.length != 2 || !code.all { it.isLetter() }) return "🌐"
        return code.uppercase().map { String(Character.toChars(0x1F1E6 + (it - 'A'))) }.joinToString("")
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // ----------------------------------------------------------------- adapters

    /** A single fixed item: the header view built in onCreate. */
    private class HeaderAdapter(private val view: View) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = 1
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = object : RecyclerView.ViewHolder(view) {}
        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {}
    }

    private inner class ResultAdapter : RecyclerView.Adapter<ResultAdapter.Holder>() {
        private var items: List<TestResult> = emptyList()

        private var ranked = true

        inner class Holder(v: View) : RecyclerView.ViewHolder(v) {
            val flag: TextView = v.findViewById(R.id.flag)
            val rank: TextView = v.findViewById(R.id.rank)
            val route: TextView = v.findViewById(R.id.route)
            val title: TextView = v.findViewById(R.id.title)
            val subtitle: TextView = v.findViewById(R.id.subtitle)
            val badge: TextView = v.findViewById(R.id.badge)
        }

        @android.annotation.SuppressLint("NotifyDataSetChanged")
        fun submit(list: List<TestResult>, ranked: Boolean) {
            items = list
            this.ranked = ranked
            notifyDataSetChanged()
        }

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_result, parent, false))

        override fun onBindViewHolder(h: Holder, position: Int) {
            val r = items[position]
            h.flag.text = flag(r.countryCode)
            h.rank.visibility = if (ranked) View.VISIBLE else View.GONE
            h.rank.text = (position + 1).toString()
            h.title.text = r.label
            h.route.text = r.route.label
            h.subtitle.text = if (r.loss != null) getString(
                R.string.result_detail, r.country, "%.0f".format(r.loss), "%.1f".format(r.duration),
            ) else r.country
            // Green under 120 ms, amber under 250 ms, red above or when the route failed.
            val (fg, bg) = when {
                !r.ok || r.ping == null -> R.color.bad to R.color.bad_bg
                r.ping < 120 -> R.color.good to R.color.good_bg
                r.ping < 250 -> R.color.warn to R.color.warn_bg
                else -> R.color.bad to R.color.bad_bg
            }
            h.badge.text = if (r.ok) getString(R.string.ms, formatPing(r.ping)) else getString(R.string.failed)
            h.badge.setTextColor(ContextCompat.getColor(this@MainActivity, fg))
            h.badge.background.mutate().setTint(ContextCompat.getColor(this@MainActivity, bg))
            h.itemView.setOnClickListener { if (r.ok) connectToResult(r) }
            h.itemView.setOnLongClickListener { copyResult(r); true }
        }
    }

    /** Countries as headers (tick to select all their servers) with their servers underneath. */
    private inner class LocationAdapter(
        private val pops: List<Pop>,
        private val selected: MutableSet<String>,
        private val onChange: () -> Unit,
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private var rows: List<Any> = build("")

        private fun build(query: String): List<Any> {
            val q = query.trim()
            val match = pops.filter {
                q.isEmpty() || it.name.contains(q, true) || it.country.contains(q, true) ||
                    it.continent.contains(q, true) || it.countryCode.equals(q, true)
            }
            val out = mutableListOf<Any>()
            match.groupBy { it.country }.forEach { (country, list) -> out += country; out.addAll(list) }
            return out
        }

        @android.annotation.SuppressLint("NotifyDataSetChanged")
        fun filter(q: String) {
            rows = build(q)
            notifyDataSetChanged()
        }

        override fun getItemCount() = rows.size

        override fun getItemViewType(position: Int) = if (rows[position] is String) 0 else 1

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val box = CheckBox(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                if (viewType == 0) setTypeface(typeface, android.graphics.Typeface.BOLD) else setPaddingRelative(dp(24), 0, 0, 0)
            }
            return object : RecyclerView.ViewHolder(box) {}
        }

        @android.annotation.SuppressLint("NotifyDataSetChanged")
        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val box = holder.itemView as CheckBox
            box.setOnCheckedChangeListener(null)
            when (val row = rows[position]) {
                is String -> {
                    val keys = pops.filter { it.country == row }.map { it.key }
                    box.text = "${flag(pops.firstOrNull { it.country == row }?.countryCode.orEmpty())}  $row"
                    box.isChecked = keys.all { it in selected }
                    box.setOnCheckedChangeListener { _, c ->
                        if (c) selected.addAll(keys) else selected.removeAll(keys.toSet())
                        onChange()
                        notifyDataSetChanged()
                    }
                }
                is Pop -> {
                    box.text = if (row.premium) "${row.label}  ⭐ Pro" else row.label
                    box.isChecked = row.key in selected
                    box.setOnCheckedChangeListener { _, c ->
                        if (c) selected += row.key else selected -= row.key
                        onChange()
                    }
                }
            }
        }
    }

    companion object {
        private const val DUMP_DELAY_MS = 10_000L
        private val PHASES = listOf(
            setOf(Step.DISCONNECT, Step.WAIT_USER_DISCONNECT) to R.string.phase_disconnect,
            setOf(Step.PROTOCOL) to R.string.phase_route,
            setOf(Step.CONNECT, Step.WAIT_USER) to R.string.phase_connect,
            setOf(Step.PING) to R.string.phase_ping,
        )
    }
}
