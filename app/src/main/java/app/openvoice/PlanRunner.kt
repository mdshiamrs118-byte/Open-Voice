package app.openvoice

import android.os.Handler
import android.os.Looper

/** Runs an AI plan one step at a time, waiting for each screen change before the next step. */
class PlanRunner(private val svc: OpenVoiceService) {

    private val h = Handler(Looper.getMainLooper())
    private var token = 0

    fun cancel() {
        token++
        h.removeCallbacksAndMessages(null)
    }

    private fun isFail(r: String) =
        r == "failed" || r.contains("not found") || r.startsWith("no ") ||
            r.startsWith("needs") || r.startsWith("say ") || r.startsWith("didn't") || r.startsWith("can't")

    private fun defaultWait(c: Command): Long = when (c) {
        is Command.OpenApp -> 1500
        is Command.OpenUri -> 1500
        is Command.Toggle -> 2800
        Command.Screenshot -> 1200
        Command.ScrollTop, Command.ScrollBottom -> 4000
        is Command.ClickText, is Command.Tap -> 800
        Command.Back, Command.Home, Command.Recents -> 700
        else -> 450
    }

    fun run(steps: List<PlanStep>, showProgress: Boolean, onDone: (String) -> Unit) {
        cancel()
        val my = token
        val real = steps.count { it !is PlanStep.Pause }
        var shown = 0

        fun go(i: Int) {
            if (my != token) return
            if (i >= steps.size) {
                onDone("done ✓")
                return
            }
            val s = steps[i]
            if (s !is PlanStep.Pause) {
                shown++
                if (showProgress && real > 1) svc.setStatus("$shown/$real · ${s.label}", 4500)
            }
            when (s) {
                is PlanStep.Pause -> h.postDelayed({ go(i + 1) }, s.ms)
                is PlanStep.Builtin -> {
                    val r = svc.exec(s.cmd)
                    if (isFail(r)) {
                        onDone("⚠ ${s.label}: $r")
                        return
                    }
                    h.postDelayed({ go(i + 1) }, if (s.waitAfter > 0) s.waitAfter else defaultWait(s.cmd))
                }
                is PlanStep.Custom -> svc.runCustomThen(s.cmd, s.captured) { res ->
                    if (my == token) {
                        if (res.startsWith("⚠")) onDone("${s.label}: $res") else h.postDelayed({ go(i + 1) }, 300)
                    }
                }
            }
        }
        go(0)
    }
}
