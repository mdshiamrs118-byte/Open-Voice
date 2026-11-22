package app.openvoice

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import rikka.shizuku.Shizuku

/** Hosts the three tabs (Home, Commands, Settings) with an icon-only bottom bar. */
class MainActivity : Activity() {

    private lateinit var home: HomePage
    private lateinit var commands: CommandsPage
    private lateinit var settings: SettingsPage
    private lateinit var pages: List<View>
    private lateinit var icons: List<ImageView>
    private lateinit var navRow: LinearLayout
    private lateinit var indicator: View
    private var tab = 0
    private val ui = Handler(Looper.getMainLooper())

    private val shizukuListener = Shizuku.OnRequestPermissionResultListener { _, _ ->
        runOnUiThread { refreshAll() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = 0xFF0A0A0A.toInt()

        home = HomePage(this)
        commands = CommandsPage(this)
        settings = SettingsPage(this)
        pages = listOf(home.view, commands.view, settings.view)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }
        val frame = FrameLayout(this)
        pages.forEach { frame.addView(it, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)) }
        root.addView(frame, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        root.addView(buildNav(), LinearLayout.LayoutParams(MATCH_PARENT, Ui.dp(this, 62)))
        setContentView(root)

        tab = intent?.getIntExtra("tab", 0)?.coerceIn(0, 2) ?: 0
        pages.forEachIndexed { i, v -> v.visibility = if (i == tab) View.VISIBLE else View.GONE }
        updateIcons(false)
        navRow.post { moveIndicator(false) }
        pageShown(tab)

        try {
            Shizuku.addRequestPermissionResultListener(shizukuListener)
        } catch (_: Throwable) {
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent?.getIntExtra("tab", -1)?.takeIf { it in 0..2 }?.let { select(it) }
    }

    override fun onDestroy() {
        try {
            Shizuku.removeRequestPermissionResultListener(shizukuListener)
        } catch (_: Throwable) {
        }
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        ListenService.onHeard = { msg -> runOnUiThread { home.showHeard(msg) } }
        ListenService.onState = { runOnUiThread { refreshAll() } }
        ListenService.onLevel = { db -> home.orb.pushLevel(db) }
        refreshAll()
        commands.render(false)
    }

    override fun onPause() {
        ListenService.onHeard = null
        ListenService.onState = null
        ListenService.onLevel = null
        super.onPause()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (tab != 0) select(0) else super.onBackPressed()
    }

    // ------------------------------------------------------------------
    // Bottom navigation (icons only)
    // ------------------------------------------------------------------
    private fun buildNav(): View {
        val bar = FrameLayout(this).apply { setBackgroundColor(0xFF0A0A0A.toInt()) }
        navRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val res = listOf(R.drawable.ic_nav_home, R.drawable.ic_nav_commands, R.drawable.ic_nav_settings)
        val names = listOf("Home", "Commands", "Settings")
        icons = res.mapIndexed { i, r ->
            ImageView(this).apply {
                setImageResource(r)
                contentDescription = names[i]
                scaleType = ImageView.ScaleType.CENTER
                setOnClickListener { select(i) }
                Anim.press(this)
            }
        }
        icons.forEach { navRow.addView(it, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f)) }
        bar.addView(navRow, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        // thin line on top
        bar.addView(View(this).apply { setBackgroundColor(0x33FFFFFF) }, FrameLayout.LayoutParams(MATCH_PARENT, Ui.dp(this, 1)))

        indicator = View(this).apply {
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = Ui.dp(this@MainActivity, 3).toFloat()
            }
        }
        bar.addView(
            indicator,
            FrameLayout.LayoutParams(Ui.dp(this, 30), Ui.dp(this, 3), Gravity.TOP or Gravity.START)
        )
        return bar
    }

    private fun moveIndicator(animate: Boolean) {
        val w = navRow.width
        if (w == 0) return
        val cell = w / 3f
        val x = tab * cell + (cell - indicator.width) / 2f
        if (animate && Anim.on(this)) {
            indicator.animate().translationX(x).setDuration(320).setInterpolator(OvershootInterpolator(1.4f)).start()
        } else {
            indicator.translationX = x
        }
    }

    private fun updateIcons(animate: Boolean) {
        icons.forEachIndexed { i, v ->
            val sel = i == tab
            v.setColorFilter(if (sel) Color.WHITE else 0x77FFFFFF)
            val s = if (sel) 1.18f else 1f
            if (animate && Anim.on(this)) {
                v.animate().scaleX(s).scaleY(s).setDuration(260).setInterpolator(OvershootInterpolator(3f)).start()
            } else {
                v.scaleX = s
                v.scaleY = s
            }
        }
    }

    fun select(i: Int) {
        if (i == tab) return
        val oldIdx = tab
        val old = pages[oldIdx]
        val new = pages[i]
        val dir = if (i > oldIdx) 1 else -1
        tab = i
        updateIcons(true)
        moveIndicator(true)

        if (!Anim.on(this)) {
            pages.forEachIndexed { idx, v -> v.visibility = if (idx == i) View.VISIBLE else View.GONE }
        } else {
            val shift = Ui.dp(this, 40).toFloat()
            old.animate().cancel()
            new.animate().cancel()
            new.visibility = View.VISIBLE
            new.alpha = 0f
            new.translationX = dir * shift
            new.animate().alpha(1f).translationX(0f).setDuration(280)
                .setInterpolator(DecelerateInterpolator(1.5f)).start()
            old.animate().alpha(0f).translationX(-dir * shift).setDuration(180).withEndAction {
                if (tab != oldIdx) old.visibility = View.GONE
                old.translationX = 0f
                old.alpha = 1f
            }.start()
        }
        pageShown(i)
    }

    private fun pageShown(i: Int) {
        when (i) {
            0 -> home.onShow()
            1 -> commands.onShow()
            else -> settings.onShow()
        }
    }

    fun toggleAiMode() {
        settings.setAiMode(!Prefs.aiMode(this))
    }

    fun refreshAll() {
        home.refresh()
        settings.refresh()
    }

    // ------------------------------------------------------------------
    // Shared actions for the pages
    // ------------------------------------------------------------------
    fun hasMic() =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun askPermissions() {
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        requestPermissions(perms.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        refreshAll()
    }

    /** Orb tap: start the service, or turn listening off / on while keeping the notification. */
    fun toggleListening() {
        if (!hasMic()) {
            askPermissions()
            return
        }
        if (!ListenService.running) ListenService.start(this) else ListenService.toggle(this)
        ui.postDelayed({ refreshAll() }, 350)
    }

    fun grantShizuku() {
        try {
            when {
                !Shizuku.pingBinder() ->
                    Toast.makeText(this, "Shizuku isn't running. Open the Shizuku app and start it first.", Toast.LENGTH_LONG).show()
                Shizuku.isPreV11() ->
                    Toast.makeText(this, "Please update Shizuku.", Toast.LENGTH_LONG).show()
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED ->
                    Toast.makeText(this, "Shizuku permission already granted", Toast.LENGTH_SHORT).show()
                else -> Shizuku.requestPermission(1001)
            }
        } catch (e: Throwable) {
            Toast.makeText(this, "Shizuku isn't available on this device", Toast.LENGTH_LONG).show()
        }
        refreshAll()
    }
}
