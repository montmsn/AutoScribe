package com.autoscribe.android

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.delay
import java.io.File

class AutomationException(message: String) : Exception(message)

/**
 * Drives the official Windscribe app through its UI, the way a person would.
 *
 * Windscribe has no public API on Android, so everything here depends on its screen layout.
 * All the view ids and labels we look for are in [Ui]; when a Windscribe update breaks
 * something, "Dump Windscribe screen" in the menu saves the current view tree to fix them.
 */
class WindscribeAccessibilityService : AccessibilityService() {

    object Ui {
        const val PACKAGE = "com.windscribe.vpn"
        val CONNECT_IDS = listOf("on_off_button", "connect_button", "btn_connect", "on_off_progress_bar")
        val SEARCH_IDS = listOf("img_search", "search_icon", "iv_search", "search_button", "search")
        val SEARCH_INPUT_IDS = listOf("search_src_text", "et_search", "search_input", "searchView")
        val SEARCH_DESCRIPTIONS = listOf("Search")
        val MENU_IDS = listOf("img_hamburger", "main_menu", "img_preferences", "preferences", "menu")
        val MENU_DESCRIPTIONS = listOf("Preferences", "Menu", "Settings")
        val CONNECTION_TEXTS = listOf("Connection")
        val CONNECTION_MODE_TEXTS = listOf("Connection Mode")
        val MANUAL_TEXTS = listOf("Manual")
        val PROTOCOL_LABEL_IDS = listOf("protocol_text", "tv_protocol", "protocol")
        val PORT_LABEL_IDS = listOf("port_text", "tv_port", "port")
        val PROTOCOL_SPINNER_IDS = listOf("protocol_dropdown", "spinner_protocol", "protocol_spinner")
        val PORT_SPINNER_IDS = listOf("port_dropdown", "spinner_port", "port_spinner")
    }

    override fun onServiceConnected() {
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    // ----------------------------------------------------------------- node helpers

    private fun roots(): List<AccessibilityNodeInfo> {
        val fromWindows = runCatching { windows.mapNotNull { it.root } }.getOrDefault(emptyList())
        return fromWindows.ifEmpty { listOfNotNull(rootInActiveWindow) }
    }

    private fun allNodes(): List<AccessibilityNodeInfo> {
        val out = mutableListOf<AccessibilityNodeInfo>()
        fun walk(n: AccessibilityNodeInfo?) {
            if (n == null) return
            out += n
            for (i in 0 until n.childCount) walk(n.getChild(i))
        }
        roots().forEach { walk(it) }
        return out
    }

    private fun AccessibilityNodeInfo.shortId(): String? = viewIdResourceName?.substringAfter(":id/")

    private fun AccessibilityNodeInfo.label(): String? =
        (text ?: contentDescription)?.toString()?.trim()?.takeIf { it.isNotEmpty() }

    private fun findById(ids: List<String>): AccessibilityNodeInfo? =
        allNodes().firstOrNull { it.isVisibleToUser && it.shortId() in ids }

    private fun findByLabel(labels: List<String>, exact: Boolean = true): AccessibilityNodeInfo? =
        allNodes().firstOrNull { n ->
            val l = n.label() ?: return@firstOrNull false
            n.isVisibleToUser && labels.any { if (exact) l.equals(it, true) else l.contains(it, true) }
        }

    private fun click(node: AccessibilityNodeInfo): Boolean {
        var n: AccessibilityNodeInfo? = node
        while (n != null) {
            if (n.isClickable && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            n = n.parent
        }
        return tap(node)
    }

    private fun tap(node: AccessibilityNodeInfo): Boolean {
        val r = Rect().also { node.getBoundsInScreen(it) }
        if (r.isEmpty) return false
        val path = Path().apply { moveTo(r.exactCenterX(), r.exactCenterY()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 60)).build()
        return dispatchGesture(gesture, null, null)
    }

    private suspend fun <T> waitFor(timeoutMs: Long, block: () -> T?): T? {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            block()?.let { return it }
            delay(250)
        }
        return block()
    }

    private suspend fun clickOrFail(what: String, timeoutMs: Long = 5000, find: () -> AccessibilityNodeInfo?) {
        val node = waitFor(timeoutMs, find) ?: throw AutomationException("could not find $what")
        if (!click(node)) throw AutomationException("could not tap $what")
        delay(400)
    }

    private fun windscribeInFront(): Boolean = rootInActiveWindow?.packageName?.toString() == Ui.PACKAGE

    // ----------------------------------------------------------------- Windscribe actions

    fun isWindscribeInstalled(): Boolean = packageManager.getLaunchIntentForPackage(Ui.PACKAGE) != null

    fun bringBack() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
    }

    /** Opens Windscribe and backs out until its main screen (with the connect button) shows. */
    suspend fun openMainScreen() {
        val launch = packageManager.getLaunchIntentForPackage(Ui.PACKAGE)
            ?: throw AutomationException("Windscribe is not installed")
        if (!windscribeInFront()) {
            startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            waitFor(8000) { if (windscribeInFront()) true else null }
                ?: throw AutomationException("Windscribe did not open")
        }
        repeat(5) {
            if (waitFor(1500) { findById(Ui.CONNECT_IDS) } != null) return
            performGlobalAction(GLOBAL_ACTION_BACK)
            delay(500)
            if (!windscribeInFront()) startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        throw AutomationException("could not reach the Windscribe main screen")
    }

    suspend fun toggleConnection() {
        openMainScreen()
        clickOrFail("the connect button") { findById(Ui.CONNECT_IDS) }
    }

    /** Searches the location list for [pop] and taps it; Windscribe connects on tap. */
    suspend fun connectTo(pop: Pop) {
        openMainScreen()
        clickOrFail("the search button") { findById(Ui.SEARCH_IDS) ?: findByLabel(Ui.SEARCH_DESCRIPTIONS) }
        val input = waitFor(4000) {
            findById(Ui.SEARCH_INPUT_IDS) ?: allNodes().firstOrNull { it.isEditable && it.isVisibleToUser }
        } ?: throw AutomationException("could not find the search field")
        input.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, pop.city)
        }
        input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        delay(800)

        val wanted = if (pop.nick.isNotEmpty()) listOf(pop.nick, pop.name) else listOf(pop.city)
        var target = waitFor(2500) { findByLabel(wanted)?.takeIf { !it.isEditable } }
        if (target == null) {
            // Results may be grouped under a collapsed country row.
            findByLabel(listOf(pop.country))?.let { click(it); delay(600) }
            target = waitFor(2500) { findByLabel(wanted)?.takeIf { !it.isEditable } }
        }
        if (target == null) throw AutomationException("could not find ${pop.label} in Windscribe's list")
        if (!click(target)) throw AutomationException("could not tap ${pop.label}")
    }

    /**
     * Selects protocol and port. Tries the protocol picker on the main screen first,
     * then Preferences > Connection > Connection Mode: Manual.
     */
    suspend fun setRoute(route: Route) {
        val name = Protocols.displayName(route.protocol)
        openMainScreen()
        val label = findById(Ui.PROTOCOL_LABEL_IDS)
        if (label != null && click(label)) {
            val item = waitFor(3000) { findByLabel(listOf(name)) }
            if (item != null && click(item)) {
                delay(600)
                pickPort(route.port)
                return
            }
            performGlobalAction(GLOBAL_ACTION_BACK)
            delay(500)
        }
        setRouteInPreferences(name, route.port)
    }

    private suspend fun pickPort(port: Int) {
        val portText = port.toString()
        if (findByLabel(listOf(portText)) != null && findById(Ui.PORT_SPINNER_IDS) == null) {
            findByLabel(listOf(portText))?.let { click(it) }
            delay(400)
            return
        }
        val spinner = findById(Ui.PORT_SPINNER_IDS) ?: findById(Ui.PORT_LABEL_IDS) ?: return
        click(spinner)
        clickOrFail("port $portText", 3000) { findByLabel(listOf(portText)) }
    }

    private suspend fun setRouteInPreferences(protocolName: String, port: Int) {
        openMainScreen()
        clickOrFail("the Windscribe menu") { findById(Ui.MENU_IDS) ?: findByLabel(Ui.MENU_DESCRIPTIONS) }
        clickOrFail("Connection settings") { findByLabel(Ui.CONNECTION_TEXTS) }
        val mode = waitFor(3000) { findByLabel(Ui.CONNECTION_MODE_TEXTS) }
        if (mode != null && findByLabel(Ui.MANUAL_TEXTS) == null) {
            click(mode)
            clickOrFail("Manual mode", 3000) { findByLabel(Ui.MANUAL_TEXTS) }
        } else {
            findByLabel(Ui.MANUAL_TEXTS)?.let { click(it); delay(400) }
        }
        val protoSpinner = waitFor(3000) { findById(Ui.PROTOCOL_SPINNER_IDS) }
            ?: throw AutomationException("could not find the protocol selector")
        click(protoSpinner)
        clickOrFail(protocolName, 3000) { findByLabel(listOf(protocolName)) }
        val portSpinner = waitFor(3000) { findById(Ui.PORT_SPINNER_IDS) }
            ?: throw AutomationException("could not find the port selector")
        click(portSpinner)
        clickOrFail("port $port", 3000) { findByLabel(listOf(port.toString())) }
        openMainScreen()
    }

    /** Writes the view tree of what is on screen now, for fixing [Ui] after a Windscribe update. */
    fun dumpScreen(file: File) {
        val sb = StringBuilder()
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null) return
            val r = Rect().also { n.getBoundsInScreen(it) }
            sb.append("  ".repeat(depth))
                .append(n.className).append(" id=").append(n.viewIdResourceName)
                .append(" text=").append(n.text).append(" desc=").append(n.contentDescription)
                .append(" click=").append(n.isClickable).append(" visible=").append(n.isVisibleToUser)
                .append(" bounds=").append(r.toShortString()).append('\n')
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
        }
        roots().forEach { sb.append("=== window ").append(it.packageName).append('\n'); walk(it, 0) }
        file.writeText(sb.toString())
    }

    companion object {
        @Volatile
        var instance: WindscribeAccessibilityService? = null
            private set
    }
}
