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

    /**
     * Windscribe's Compose UI exposes its test tags as view ids (testTagsAsResourceId).
     * These are the tags its own Maestro UI tests use, so they are the most stable handles we have.
     */
    object Ui {
        const val PACKAGE = "com.windscribe.vpn"
        const val VPN_DIALOGS = "com.android.vpndialogs"
        val CONNECT_IDS = listOf("home_connect_button")
        const val STATUS_CONNECTED = "connection_status_connected"
        val SEARCH_IDS = listOf("search_open")
        val SEARCH_INPUT_IDS = listOf("search_input")
        val SEARCH_CLOSE_IDS = listOf("search_close")
        val MENU_IDS = listOf("main_menu_button")
        val CONNECTION_MENU_IDS = listOf("menu_item_connection")
        const val DROPDOWN_ID = "dropdown_selector"
        val POPUP_DISMISS_IDS = listOf("preferred_protocol_cancel", "battery_dont_ask_again")
        val AUTO_TEXTS = listOf("Auto")
        val MANUAL_TEXTS = listOf("Manual")
        const val PROTOCOL_TITLE = "Protocol"
        const val PORT_TITLE = "Port"
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

    private fun allRoots(): List<AccessibilityNodeInfo> {
        val fromWindows = runCatching { windows.mapNotNull { it.root } }.getOrDefault(emptyList())
        return fromWindows.ifEmpty { listOfNotNull(rootInActiveWindow) }
    }

    private fun roots(): List<AccessibilityNodeInfo> =
        allRoots().filter { it.packageName?.toString() in setOf(Ui.PACKAGE, Ui.VPN_DIALOGS) }

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

    fun isConnectedOnScreen(): Boolean = findById(listOf(Ui.STATUS_CONNECTED)) != null

    /** Closes the popups Windscribe (and Android's first-time VPN consent) may show while connecting. */
    fun dismissPopups() {
        findById(Ui.POPUP_DISMISS_IDS)?.let { click(it) }
        allNodes().firstOrNull {
            it.isVisibleToUser && it.label().equals("OK", true) &&
                it.packageName?.toString() in setOf(Ui.PACKAGE, Ui.VPN_DIALOGS)
        }?.let { click(it) }
    }

    /** Searches the location list for [pop] and taps it; Windscribe connects on tap. */
    suspend fun connectTo(pop: Pop) {
        openMainScreen()
        clickOrFail("the search button") { findById(Ui.SEARCH_IDS) }
        val input = waitFor(4000) {
            findById(Ui.SEARCH_INPUT_IDS) ?: allNodes().firstOrNull { it.isEditable && it.isVisibleToUser }
        } ?: throw AutomationException("could not find the search field")
        click(input)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, pop.city)
        }
        // The Compose text field is a descendant of the tagged node.
        val editable = findEditable(input) ?: input
        editable.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        delay(900)

        // Search rows read "<city> <nickname>", e.g. "Toronto Comfort Zone".
        val rowText = listOf("${pop.city} ${pop.nick}".trim())
        fun find() = allNodes().firstOrNull { n ->
            val l = n.label() ?: return@firstOrNull false
            n.isVisibleToUser && !n.isEditable &&
                (rowText.any { l.equals(it, true) } || (pop.nick.isNotEmpty() && l.endsWith(pop.nick, true)))
        }
        var target = waitFor(2500) { find() }
        if (target == null) {
            // Results may be grouped under a collapsed country row.
            findByLabel(listOf(pop.country))?.let { click(it); delay(600) }
            target = waitFor(2500) { find() }
        }
        if (target == null) {
            findById(Ui.SEARCH_CLOSE_IDS)?.let { click(it) }
            throw AutomationException("could not find ${pop.label} in Windscribe's list")
        }
        if (!click(target)) throw AutomationException("could not tap ${pop.label}")
    }

    private fun findEditable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable) return node
        for (i in 0 until node.childCount) findEditable(node.getChild(i) ?: continue)?.let { return it }
        return null
    }

    /** The dropdown on the Connection screen whose row is titled [title] ("Protocol" or "Port"). */
    private fun findDropdown(title: String): AccessibilityNodeInfo? =
        allNodes().firstOrNull { n ->
            n.isVisibleToUser && n.shortId() == Ui.DROPDOWN_ID && n.parent?.let { p ->
                (0 until p.childCount).any { p.getChild(it)?.label().equals(title, true) }
            } == true
        }

    private fun isInside(node: AccessibilityNodeInfo, id: String): Boolean {
        var n: AccessibilityNodeInfo? = node
        while (n != null) {
            if (n.shortId() == id) return true
            n = n.parent
        }
        return false
    }

    /** Taps the open dropdown menu item labelled [label] (not the dropdown's own current value). */
    private suspend fun pickMenuItem(label: String) {
        clickOrFail(label, 3000) {
            allNodes().firstOrNull {
                it.isVisibleToUser && it.label().equals(label, true) && !isInside(it, Ui.DROPDOWN_ID)
            }
        }
    }

    /** Preferences > Connection > Connection Mode: Manual, then the protocol and port dropdowns. */
    suspend fun setRoute(route: Route) {
        val name = Protocols.displayName(route.protocol)
        openMainScreen()
        clickOrFail("the Windscribe menu") { findById(Ui.MENU_IDS) }
        clickOrFail("Connection settings") { findById(Ui.CONNECTION_MENU_IDS) }

        if (waitFor(2000) { findDropdown(Ui.PROTOCOL_TITLE) } == null) {
            clickOrFail("the connection mode selector") { findByLabel(Ui.AUTO_TEXTS) }
            pickMenuItem(Ui.MANUAL_TEXTS.first())
        }
        val proto = waitFor(3000) { findDropdown(Ui.PROTOCOL_TITLE) }
            ?: throw AutomationException("could not find the protocol selector")
        if (!proto.hasLabelInside(name)) {
            click(proto)
            pickMenuItem(name)
            delay(500)
        }
        val port = waitFor(3000) { findDropdown(Ui.PORT_TITLE) }
            ?: throw AutomationException("could not find the port selector")
        if (!port.hasLabelInside(route.port.toString())) {
            click(port)
            delay(400)
            try {
                pickMenuItem(route.port.toString())
            } catch (e: AutomationException) {
                performGlobalAction(GLOBAL_ACTION_BACK)
                throw AutomationException("Windscribe does not offer port ${route.port} for $name")
            }
        }
        delay(400)
        openMainScreen()
    }

    private fun AccessibilityNodeInfo.hasLabelInside(text: String): Boolean {
        if (label().equals(text, true)) return true
        for (i in 0 until childCount) if (getChild(i)?.hasLabelInside(text) == true) return true
        return false
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
        allRoots().forEach { sb.append("=== window ").append(it.packageName).append('\n'); walk(it, 0) }
        file.writeText(sb.toString())
    }

    companion object {
        @Volatile
        var instance: WindscribeAccessibilityService? = null
            private set
    }
}
