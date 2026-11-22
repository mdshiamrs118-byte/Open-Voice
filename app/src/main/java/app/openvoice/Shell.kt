package app.openvoice

import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import rikka.shizuku.Shizuku
import java.io.OutputStream
import java.util.concurrent.Executors

/** Runs shell commands as root (su) or through Shizuku, using one long-lived shell per mode. */
object Shell {

    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())

    @Volatile
    private var rootOk: Boolean? = null

    @Volatile
    var lastError: String = ""

    @Volatile
    var lastOutput: String = ""

    private class Session(val factory: () -> Process) {
        private var proc: Process? = null
        private var out: OutputStream? = null

        @Synchronized
        fun run(cmd: String): Boolean {
            repeat(2) {
                try {
                    if (proc == null) open()
                    out!!.write((cmd + "\n").toByteArray())
                    out!!.flush()
                    return true
                } catch (e: Exception) {
                    lastError = e.message ?: e.toString()
                    close()
                }
            }
            return false
        }

        private fun open() {
            val p = factory()
            proc = p
            out = p.outputStream
            for (s in listOf(p.inputStream, p.errorStream)) {
                Thread {
                    try {
                        s.bufferedReader().forEachLine { if (it.isNotBlank()) lastOutput = it }
                    } catch (_: Exception) {
                    }
                }.apply { isDaemon = true }.start()
            }
        }

        @Synchronized
        fun close() {
            try {
                out?.close()
            } catch (_: Exception) {
            }
            try {
                proc?.destroy()
            } catch (_: Exception) {
            }
            proc = null
            out = null
        }
    }

    private val rootSession = Session { Runtime.getRuntime().exec("su") }
    private val shizukuSession = Session { newShizukuProcess() }

    /** Shizuku 13+ hides newProcess(), so call it by reflection. */
    private fun newShizukuProcess(): Process {
        val m = Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java
        )
        m.isAccessible = true
        return m.invoke(null, arrayOf("sh"), null, null) as Process
    }

    // ---- status ----
    fun shizukuRunning(): Boolean = try {
        Shizuku.pingBinder()
    } catch (e: Throwable) {
        false
    }

    fun shizukuReady(): Boolean = try {
        Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (e: Throwable) {
        false
    }

    fun rootStatus(): String = when (rootOk) {
        true -> "available"
        false -> "not available"
        null -> "not tested"
    }

    fun shizukuStatus(): String = when {
        !shizukuRunning() -> "not running"
        shizukuReady() -> "ready"
        else -> "permission needed"
    }

    private fun testRoot(): Boolean = try {
        val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
        val txt = p.inputStream.bufferedReader().readText()
        p.waitFor()
        txt.contains("uid=0")
    } catch (e: Exception) {
        false
    }

    fun testRootAsync(cb: (Boolean) -> Unit) {
        io.execute {
            val ok = testRoot()
            rootOk = ok
            ui.post { cb(ok) }
        }
    }

    /** Verifies the mode can be used; the callback runs on the main thread. */
    fun check(mode: Mode, cb: (Boolean, String) -> Unit) {
        when (mode) {
            Mode.NORMAL -> cb(true, "")
            Mode.SHIZUKU -> when {
                !shizukuRunning() -> cb(false, "Shizuku isn't running")
                !shizukuReady() -> cb(false, "Shizuku permission not granted")
                else -> cb(true, "")
            }
            Mode.ROOT -> {
                val known = rootOk
                if (known != null) {
                    cb(known, if (known) "" else "Root not available")
                } else {
                    testRootAsync { ok -> cb(ok, if (ok) "" else "Root not available") }
                }
            }
        }
    }

    /** Fire-and-forget; runs on a background thread. */
    fun exec(mode: Mode, cmd: String) {
        io.execute {
            when (mode) {
                Mode.ROOT -> rootSession.run(cmd)
                Mode.SHIZUKU -> shizukuSession.run(cmd)
                Mode.NORMAL -> false
            }
        }
    }
}
