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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
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
    private lateinit var currentStep: TextView
    private lateinit var progress: LinearProgressIndicator
    private lateinit var bestRoute: TextView
    private lateinit var tabs: TabLayout
    private val adapter = ResultAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.inflateMenu(R.menu.main)
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_settings -> showSettings()
                R.id.action_export -> exportCsv()
                R.id.action_dump -> dumpScreen()
                R.id.action_clear -> confirmClear()
                R.id.action_language -> chooseLanguage()
            }
            true
        }

        serviceStatus = findViewById(R.id.serviceStatus)
        planSummary = findViewById(R.id.planSummary)
        btnStart = findViewById(R.id.btnStart)
        currentStep = findViewById(R.id.currentStep)
        progress = findViewById(R.id.progress)
        bestRoute = findViewById(R.id.bestRoute)
        tabs = findViewById(R.id.tabs)

        serviceStatus.setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        findViewById<Button>(R.id.btnLocations).setOnClickListener { showLocations() }
        findViewById<Button>(R.id.btnProtocols).setOnClickListener { showProtocols() }
        btnStart.setOnClickListener { if (TestRunner.state.value.running) TestRunner.stop() else startTest() }

        tabs.addTab(tabs.newTab().setText(R.string.tab_best))
        tabs.addTab(tabs.newTab().setText(R.string.tab_results))
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) = renderResults(TestRunner.results.value)
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })

        val list = findViewById<RecyclerView>(R.id.list)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { TestRunner.state.collect { renderState(it) } }
                launch { TestRunner.results.collect { renderResults(it) } }
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
        val locations = if (selected.isEmpty()) app.pops.size else app.pops.count { it.key in selected }
        val routes = store.protocols.values.sumOf { it.size }
        val pending = TestRunner.plan(store, app.pops).size
        planSummary.text = getString(R.string.plan_summary, locations, routes, locations * routes, pending)
    }

    private fun renderState(s: RunState) {
        btnStart.setText(if (s.running) R.string.stop else R.string.start)
        progress.max = maxOf(1, s.total)
        progress.progress = s.index
        progress.visibility = if (s.running) View.VISIBLE else View.INVISIBLE
        val step = getString(s.step.res)
        currentStep.text = when {
            s.running -> getString(R.string.step_line, s.index, s.total, step, s.target)
            s.detail.isNotEmpty() -> "$step: ${s.detail}"
            else -> step
        }
        if (!s.running) {
            renderService()
            renderPlan()
        }
    }

    private fun renderResults(results: List<TestResult>) {
        val best = TestRunner.best(results)
        bestRoute.text = best.firstOrNull()?.let {
            getString(R.string.best_route, it.label, it.route.label, formatPing(it.ping))
        } ?: getString(R.string.no_best)
        adapter.submit(if (tabs.selectedTabPosition == 1) results.asReversed() else best)
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
        val tasks = TestRunner.plan(store, app.pops)
        if (tasks.isEmpty()) {
            Toast.makeText(this, R.string.nothing_to_test, Toast.LENGTH_LONG).show()
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        TestRunner.start(this, store, tasks)
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
                store.assisted = assisted.isChecked
                renderService()
                renderPlan()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun exportCsv() {
        val file = store.exportCsv()
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

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // ----------------------------------------------------------------- adapters

    private inner class ResultAdapter : RecyclerView.Adapter<ResultAdapter.Holder>() {
        private var items: List<TestResult> = emptyList()

        inner class Holder(v: View) : RecyclerView.ViewHolder(v) {
            val title: TextView = v.findViewById(R.id.title)
            val subtitle: TextView = v.findViewById(R.id.subtitle)
        }

        @android.annotation.SuppressLint("NotifyDataSetChanged")
        fun submit(list: List<TestResult>) {
            items = list
            notifyDataSetChanged()
        }

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_result, parent, false))

        override fun onBindViewHolder(h: Holder, position: Int) {
            val r = items[position]
            h.title.text = "${if (r.ok) "✅" else "❌"} ${r.label} · ${r.route.label}"
            h.subtitle.text = getString(
                R.string.result_detail, r.country, formatPing(r.ping),
                r.loss?.let { "%.0f".format(it) } ?: "-", "%.1f".format(r.duration),
            )
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
                    box.text = row
                    box.isChecked = keys.all { it in selected }
                    box.setOnCheckedChangeListener { _, c ->
                        if (c) selected.addAll(keys) else selected.removeAll(keys.toSet())
                        onChange()
                        notifyDataSetChanged()
                    }
                }
                is Pop -> {
                    box.text = row.label
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
    }
}
