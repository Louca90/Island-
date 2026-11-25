package com.louca.island

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.BatteryManager
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.louca.island.ui.IslandRoot
import com.louca.island.ui.compactH
import com.louca.island.ui.rememberIslandColors
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class OverlayOwner : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
    private val reg = LifecycleRegistry(this)
    private val ssc = SavedStateRegistryController.create(this)
    private val vms = ViewModelStore()
    override val lifecycle: Lifecycle get() = reg
    override val savedStateRegistry: SavedStateRegistry get() = ssc.savedStateRegistry
    override val viewModelStore: ViewModelStore get() = vms

    fun start() {
        ssc.performRestore(null)
        reg.currentState = Lifecycle.State.RESUMED
    }

    fun stop() {
        reg.currentState = Lifecycle.State.DESTROYED
        vms.clear()
    }

    fun attach(v: View) {
        v.setViewTreeLifecycleOwner(this)
        v.setViewTreeSavedStateRegistryOwner(this)
        v.setViewTreeViewModelStoreOwner(this)
    }
}

/** Invisible window sitting exactly over the island: receives touches and replays them into the visual window. */
class TouchFrame(ctx: Context) : android.widget.FrameLayout(ctx) {
    var target: View? = null
    var zone = Rect()
    var onOutside: () -> Unit = {}
    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (ev.actionMasked == android.view.MotionEvent.ACTION_OUTSIDE) { onOutside(); return true }
        val t = target ?: return false
        val e = android.view.MotionEvent.obtain(ev)
        e.offsetLocation(zone.left.toFloat(), zone.top.toFloat())
        t.dispatchTouchEvent(e)
        e.recycle()
        return true
    }
}

const val BT_BATTERY = "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED"

class IslandAccessibilityService : AccessibilityService() {

    private var wm: WindowManager? = null
    private var visual: android.widget.FrameLayout? = null
    private var touch: TouchFrame? = null
    private val owner = OverlayOwner()
    private val scope = MainScope()
    private lateinit var km: KeyguardManager

    private val fullscreen = MutableStateFlow(false)
    private val shadeOpen = MutableStateFlow(false)
    private val landscape = MutableStateFlow(false)
    private val shift = MutableStateFlow(0f)

    // Full-width, never resized, never touchable: no more jumps when the island grows or shrinks.
    private val vlp = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT, 1,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.LEFT
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        fitInsetsTypes = 0
        windowAnimations = 0
        title = "Island"
    }

    private val tlp = WindowManager.LayoutParams(
        1, 1,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.LEFT
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        fitInsetsTypes = 0
        windowAnimations = 0
        title = "IslandTouch"
    }

    private val rx = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val s = Prefs.state.value
            when (i.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    IslandStore.locked.value = true
                    IslandStore.expanded.value = false
                    IslandStore.remove("notif")
                }
                Intent.ACTION_SCREEN_ON -> {
                    IslandStore.wallTick.value++
                    IslandStore.locked.value = km.isKeyguardLocked
                    scope.launch { kotlinx.coroutines.delay(400); maybeAutoStandby() }
                    if (s.lockAnim && km.isKeyguardLocked) {
                        IslandStore.upsert(Act(id = "lock", kind = Kind.LOCK, title = "Verrouillé"))
                    }
                }
                Intent.ACTION_USER_PRESENT -> {
                    IslandStore.locked.value = false
                    // Auto StandBy behaves like the iPhone: unlocking closes it.
                    if (IslandStore.standbyAuto && s.standbyLockOnly) IslandStore.closeStandby?.invoke()
                    val lock = IslandStore.acts.value.find { it.id == "lock" }
                    if (lock != null) IslandStore.upsert(lock.copy(unlocked = true), ttl = 1150)
                    checkShade()
                }
                android.media.AudioManager.RINGER_MODE_CHANGED_ACTION -> {
                    if (isInitialStickyBroadcast || !s.showSystem) return
                    val mode = i.getIntExtra(android.media.AudioManager.EXTRA_RINGER_MODE, 2)
                    val (v, t) = when (mode) {
                        android.media.AudioManager.RINGER_MODE_SILENT -> 2 to "Silencieux"
                        android.media.AudioManager.RINGER_MODE_VIBRATE -> 1 to "Vibreur"
                        else -> 0 to "Sonnerie"
                    }
                    IslandStore.showStatus(Act(id = "sys", kind = Kind.SYSTEM, title = t, variant = v, stamp = System.currentTimeMillis()))
                }
                android.app.NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED -> {
                    if (!s.showSystem) return
                    val f = getSystemService(android.app.NotificationManager::class.java).currentInterruptionFilter
                    val on = f != android.app.NotificationManager.INTERRUPTION_FILTER_ALL
                    IslandStore.showStatus(
                        Act(id = "sys", kind = Kind.SYSTEM, title = if (on) "Ne pas déranger" else "Notifications activées",
                            variant = if (on) 3 else 4, stamp = System.currentTimeMillis())
                    )
                }
                Intent.ACTION_BATTERY_LOW -> {
                    if (!s.showSystem) return
                    val lvl = getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                    IslandStore.showStatus(Act(id = "sys", kind = Kind.SYSTEM, title = "Batterie faible · $lvl %", variant = 5, level = lvl))
                }
                Intent.ACTION_POWER_CONNECTED -> {
                    showCharge(first = true)
                    scope.launch { kotlinx.coroutines.delay(1500); maybeAutoStandby() }
                }
                android.bluetooth.BluetoothDevice.ACTION_ACL_CONNECTED,
                android.bluetooth.BluetoothDevice.ACTION_ACL_DISCONNECTED,
                BT_BATTERY -> onBluetooth(i, s)
            }
        }
    }

    override fun onServiceConnected() {
        km = getSystemService(KeyguardManager::class.java)
        wm = getSystemService(WindowManager::class.java)
        landscape.value = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        IslandStore.locked.value = km.isKeyguardLocked
        owner.start()

        val v = android.widget.FrameLayout(this)
        owner.attach(v)
        v.addView(ComposeView(this).apply { setContent { Overlay() } })
        v.setOnApplyWindowInsetsListener { _, ins ->
            fullscreen.value = !ins.isVisible(WindowInsets.Type.statusBars())
            ins
        }
        val t = TouchFrame(this).apply {
            target = v
            onOutside = {
                IslandStore.replying.value = null
                IslandStore.expanded.value = false
            }
        }
        vlp.height = (500 * resources.displayMetrics.density).toInt()
        updatePosition()
        runCatching { wm?.addView(v, vlp) }
        runCatching { wm?.addView(t, tlp) }
        visual = v; touch = t

        registerReceiver(rx, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_BATTERY_LOW)
            addAction(android.bluetooth.BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(android.bluetooth.BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BT_BATTERY)
            addAction(android.media.AudioManager.RINGER_MODE_CHANGED_ACTION)
            addAction(android.app.NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)
        }, Context.RECEIVER_EXPORTED)

        scope.launch {
            Prefs.state.collect { updatePosition(); relayout() }
        }
        // Upcoming calendar event
        scope.launch {
            while (true) { runCatching { checkCalendar() }; kotlinx.coroutines.delay(30_000) }
        }
        setupTorch()
        setupScreenshots()

        // After the full-screen landscape player closes, re-read orientation and system-bar state:
        // otherwise the island can stay hidden, thinking it is still full screen / landscape.
        scope.launch {
            IslandStore.standby.collect { on ->
                if (!on) {
                    kotlinx.coroutines.delay(350)
                    refreshScreenState()
                    kotlinx.coroutines.delay(600)
                    refreshScreenState()
                }
            }
        }
    }

    private fun isLandscapeNow(): Boolean {
        val b = wm?.currentWindowMetrics?.bounds ?: return resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        return b.width() > b.height()
    }

    private fun refreshScreenState() {
        landscape.value = isLandscapeNow()
        fullscreen.value = false
        visual?.requestApplyInsets()
        updatePosition()
        relayout()
    }

    @androidx.compose.runtime.Composable
    private fun Overlay() {
        val acts by IslandStore.acts.collectAsState()
        val pick by IslandStore.pick.collectAsState()
        val expanded by IslandStore.expanded.collectAsState()
        val s by Prefs.state.collectAsState()
        val fs by fullscreen.collectAsState()
        val shade by shadeOpen.collectAsState()
        val land by landscape.collectAsState()
        val sx by shift.collectAsState()
        val replying by IslandStore.replying.collectAsState()
        val locked by IslandStore.locked.collectAsState()
        val zones = if (locked) s.deadZonesLock else s.deadZones
        val standby by IslandStore.standby.collectAsState()
        val hidden = land || (s.hideFullscreen && fs) || (s.hideShade && shade) || replying != null || standby
        val colors = rememberIslandColors(s)
        IslandRoot(
            acts = acts, pick = pick, expandedFlag = expanded, s = s, c = colors, hidden = hidden,
            maxW = LocalConfiguration.current.screenWidthDp.dp,
            shiftX = sx.dp,
            zones = zones,
            onTouchZone = ::setZone,
        )
        if (s.showZones) com.louca.island.ui.ZonesOverlay(zones, compactH(s))
    }

    /** Opens the StandBy screen if the user's conditions are met (charging, wireless only, lock screen only). */
    private fun maybeAutoStandby() {
        val s = Prefs.state.value
        if (!s.standbyOnCharge || IslandStore.standby.value) return
        val st = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return
        val plugged = st.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        if (plugged == 0) return
        if (s.standbyWirelessOnly && plugged != BatteryManager.BATTERY_PLUGGED_WIRELESS) return
        if (s.standbyLockOnly && !km.isKeyguardLocked) return
        IslandStore.openStandby(auto = true)
    }

    // ---- screenshots: watch MediaStore for a new image in a Screenshots folder
    private var lastShotId = -1L

    private fun setupScreenshots() {
        val obs = object : android.database.ContentObserver(android.os.Handler(mainLooper)) {
            override fun onChange(selfChange: Boolean, uri: android.net.Uri?) {
                scope.launch { kotlinx.coroutines.delay(350); runCatching { checkScreenshot() } }
            }
        }
        runCatching {
            contentResolver.registerContentObserver(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, obs)
        }
    }

    private fun checkScreenshot() {
        if (!Prefs.state.value.showShots) return
        if (checkSelfPermission(android.Manifest.permission.READ_MEDIA_IMAGES) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        val M = android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val proj = arrayOf(
            android.provider.MediaStore.Images.Media._ID, android.provider.MediaStore.Images.Media.DISPLAY_NAME,
            android.provider.MediaStore.Images.Media.RELATIVE_PATH, android.provider.MediaStore.Images.Media.DATE_ADDED,
            android.provider.MediaStore.Images.Media.IS_PENDING,
        )
        contentResolver.query(M, proj, null, null, android.provider.MediaStore.Images.Media.DATE_ADDED + " DESC")?.use { c ->
            if (!c.moveToFirst()) return
            val id = c.getLong(0)
            val name = c.getString(1) ?: ""; val path = c.getString(2) ?: ""
            val added = c.getLong(3) * 1000; val pending = c.getInt(4) == 1
            if (pending || id == lastShotId) return
            if (System.currentTimeMillis() - added > 15_000) return
            if (!path.contains("screenshot", true) && !name.startsWith("screenshot", true)) return
            lastShotId = id
            val uri = android.content.ContentUris.withAppendedId(M, id)
            val thumb = runCatching { contentResolver.loadThumbnail(uri, android.util.Size(360, 780), null) }.getOrNull()
            IslandStore.showStatus(
                Act(id = "shot", kind = Kind.SHOT, title = "Capture d'écran", art = thumb, uri = uri.toString(),
                    stamp = System.currentTimeMillis()),
                bigMs = 3_000, smallMs = 5_000,
            )
        }
    }

    private var camId: String? = null
    private var torchMax = 1

    private fun setupTorch() {
        val cm = getSystemService(android.hardware.camera2.CameraManager::class.java) ?: return
        camId = runCatching {
            cm.cameraIdList.firstOrNull {
                cm.getCameraCharacteristics(it).get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        }.getOrNull()
        val id = camId ?: return
        torchMax = runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 33)
                cm.getCameraCharacteristics(id).get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_STRENGTH_MAXIMUM_LEVEL) ?: 1
            else 1
        }.getOrDefault(1)
        IslandStore.torchOff = { runCatching { cm.setTorchMode(id, false) } }
        IslandStore.torchLevel = { lvl ->
            runCatching {
                if (android.os.Build.VERSION.SDK_INT >= 33 && torchMax > 1) cm.turnOnTorchWithStrengthLevel(id, lvl.coerceIn(1, torchMax))
            }
        }
        cm.registerTorchCallback(object : android.hardware.camera2.CameraManager.TorchCallback() {
            override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                if (cameraId != id) return
                if (enabled) pushTorch(cm, id) else IslandStore.remove("torch")
            }
            override fun onTorchStrengthLevelChanged(cameraId: String, newStrengthLevel: Int) {
                if (cameraId == id && IslandStore.acts.value.any { it.id == "torch" }) pushTorch(cm, id)
            }
        }, android.os.Handler(mainLooper))
    }

    private fun pushTorch(cm: android.hardware.camera2.CameraManager, id: String) {
        val lvl = runCatching { if (android.os.Build.VERSION.SDK_INT >= 33) cm.getTorchStrengthLevel(id) else 1 }.getOrDefault(1)
        IslandStore.upsert(Act(id = "torch", kind = Kind.TORCH, title = "Lampe torche", level = lvl, duration = torchMax.toLong()))
    }

    private var budsAt = 0L
    private var budsAddr: String? = null

    private fun onBluetooth(i: Intent, s: Settings) {
        if (!s.showBuds) return
        @Suppress("DEPRECATION")
        val dev = i.getParcelableExtra<android.bluetooth.BluetoothDevice>(android.bluetooth.BluetoothDevice.EXTRA_DEVICE) ?: return
        val audio = runCatching {
            dev.bluetoothClass?.majorDeviceClass == android.bluetooth.BluetoothClass.Device.Major.AUDIO_VIDEO
        }.getOrDefault(false)
        if (!audio) return
        val name = runCatching { dev.alias ?: dev.name }.getOrNull() ?: "Écouteurs"
        val now = System.currentTimeMillis()
        when (i.action) {
            android.bluetooth.BluetoothDevice.ACTION_ACL_CONNECTED -> {
                budsAt = now; budsAddr = dev.address
                IslandStore.showStatus(Act(id = "buds", kind = Kind.AUDIO, title = name, level = -1, stamp = now))
            }
            android.bluetooth.BluetoothDevice.ACTION_ACL_DISCONNECTED ->
                IslandStore.showStatus(Act(id = "buds", kind = Kind.AUDIO, title = name, variant = 1, level = -1, stamp = now))
            BT_BATTERY -> {
                val lvl = i.getIntExtra("android.bluetooth.device.extra.BATTERY_LEVEL", -1)
                if (lvl < 0 || dev.address != budsAddr || now - budsAt > 15_000) return
                IslandStore.showStatus(Act(id = "buds", kind = Kind.AUDIO, title = name, level = lvl, stamp = budsAt))
            }
        }
    }

    private fun showCharge(first: Boolean) {
        val bm = getSystemService(BatteryManager::class.java)
        val lvl = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val rem = bm.computeChargeTimeRemaining()
        val sub = if (rem > 0) {
            val m = rem / 60000
            if (m >= 60) "plein dans ${m / 60} h ${"%02d".format(m % 60)}" else "plein dans $m min"
        } else if (lvl >= 100) "chargé" else ""
        val plugged = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val wireless = plugged == BatteryManager.BATTERY_PLUGGED_WIRELESS
        IslandStore.showStatus(Act(id = "charge", kind = Kind.CHARGE, title = if (wireless) "Charge sans fil" else "En charge",
            level = lvl, sub = sub, variant = if (wireless) 1 else 0))
        if (first && rem <= 0 && lvl < 100) scope.launch { kotlinx.coroutines.delay(1500); if (IslandStore.acts.value.any { it.id == "charge" }) showCharge(false) }
    }

    private fun checkCalendar() {
        val s = Prefs.state.value
        if (!s.showEvents || checkSelfPermission(android.Manifest.permission.READ_CALENDAR) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            IslandStore.removeIf { it.kind == Kind.EVENT }; return
        }
        val now = System.currentTimeMillis()
        val lead = (s.eventLead * 60_000).toLong()
        val uri = android.provider.CalendarContract.Instances.CONTENT_URI.buildUpon()
            .let { android.content.ContentUris.appendId(it, now - 5 * 60_000) }
            .let { android.content.ContentUris.appendId(it, now + lead) }.build()
        val proj = arrayOf(
            android.provider.CalendarContract.Instances.EVENT_ID, android.provider.CalendarContract.Instances.TITLE,
            android.provider.CalendarContract.Instances.BEGIN, android.provider.CalendarContract.Instances.END,
            android.provider.CalendarContract.Instances.EVENT_LOCATION, android.provider.CalendarContract.Instances.ALL_DAY,
        )
        var found: Act? = null
        contentResolver.query(uri, proj, null, null, android.provider.CalendarContract.Instances.BEGIN + " ASC")?.use { c ->
            while (c.moveToNext()) {
                if (c.getInt(5) == 1) continue
                val begin = c.getLong(2)
                if (begin < now - 5 * 60_000 || begin > now + lead) continue
                val id = c.getLong(0)
                val fmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.FRANCE)
                val mins = ((begin - now) / 60_000).toInt()
                val intent = Intent(Intent.ACTION_VIEW, android.content.ContentUris.withAppendedId(android.provider.CalendarContract.Events.CONTENT_URI, id))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val pi = android.app.PendingIntent.getActivity(this, id.toInt(), intent,
                    android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
                found = Act(
                    id = "event", kind = Kind.EVENT, title = c.getString(1) ?: "Événement",
                    text = listOfNotNull("${fmt.format(java.util.Date(begin))} – ${fmt.format(java.util.Date(c.getLong(3)))}",
                        c.getString(4)?.takeIf { it.isNotBlank() }).joinToString(" · "),
                    short = if (mins <= 0) "maintenant" else "$mins min", base = begin, contentIntent = pi,
                )
                break
            }
        }
        val f = found
        if (f != null) IslandStore.upsert(f) else IslandStore.remove("event")
    }

    private fun setZone(r: Rect?) {
        val t = touch ?: return
        if (r == null || r.width() <= 0 || r.height() <= 0) {
            tlp.width = 1; tlp.height = 1
            tlp.flags = tlp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        } else {
            t.zone = Rect(r)
            tlp.x = r.left; tlp.y = vlp.y + r.top
            tlp.width = r.width(); tlp.height = r.height()
            tlp.flags = tlp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        }
        runCatching { wm?.updateViewLayout(t, tlp) }
    }

    private fun relayout() {
        visual?.let { runCatching { wm?.updateViewLayout(it, vlp) } }
    }

    private fun updatePosition() {
        val m = wm?.currentWindowMetrics ?: return
        val d = resources.displayMetrics.density
        val s = Prefs.state.value
        val hPx = compactH(s).value * d
        val cut = m.windowInsets.displayCutout?.boundingRects?.minByOrNull { it.top }
        val sb = m.windowInsets.getInsets(WindowInsets.Type.statusBars()).top
        val centerY = if (cut != null && cut.top < sb + 1) cut.exactCenterY() else sb / 2f
        vlp.y = (centerY - hPx / 2f + s.offsetY * d).toInt().coerceAtLeast(0)
        val cutShift = if (cut != null) cut.exactCenterX() - m.bounds.width() / 2f else 0f
        shift.value = cutShift / d + s.offsetX
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        landscape.value = isLandscapeNow()
        updatePosition()
        relayout()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val t = event?.eventType ?: return
        if (t == AccessibilityEvent.TYPE_WINDOWS_CHANGED || t == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) checkShade()
    }

    private fun checkShade() {
        if (!Prefs.state.value.hideShade || km.isKeyguardLocked) { shadeOpen.value = false; return }
        val screenH = resources.displayMetrics.heightPixels
        val r = Rect()
        shadeOpen.value = runCatching {
            windows.any {
                it.type == AccessibilityWindowInfo.TYPE_SYSTEM && run { it.getBoundsInScreen(r); r.height() > screenH / 2 }
            }
        }.getOrDefault(false)
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        runCatching { unregisterReceiver(rx) }
        visual?.let { runCatching { wm?.removeView(it) } }
        touch?.let { runCatching { wm?.removeView(it) } }
        visual = null; touch = null
        owner.stop()
        scope.cancel()
        super.onDestroy()
    }
}
