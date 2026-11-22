package app.openvoice

import java.util.Calendar
import kotlin.random.Random

/** Evaluates the condition fields of if / else if / while / wait until actions. */
object Conditions {

    /** [a] must already have its {placeholders} resolved. */
    fun eval(svc: OpenVoiceService, a: Action): Boolean {
        val first = one(svc, a, "")
        val c2 = a.p("check2", "none")
        if (c2.isBlank() || c2 == "none") return first
        val second = one(svc, a, "2")
        return if (a.p("join", "and") == "or") first || second else first && second
    }

    private fun one(svc: OpenVoiceService, a: Action, s: String): Boolean {
        val check = a.p("check$s", "value")
        val left = a.p("left$s")
        val op = a.p("op$s", "equals")
        val right = a.p("right$s")

        val r = when (check) {
            "value" -> compare(left, op, right)
            "foreground_app" -> {
                val pkg = svc.foregroundPackage().orEmpty()
                val label = svc.appLabel(pkg)
                if (op.startsWith("not")) compare(pkg, op, right) && compare(label, op, right)
                else compare(pkg, op, right) || compare(label, op, right)
            }
            "text_on_screen" -> svc.screenHasText(right)
            "app_installed" -> svc.findPackage(right) != null || svc.packageInstalled(right)
            "time_between" -> timeBetween(left, right)
            "day_of_week" -> dayMatches(right)
            "battery_level" -> numCompare(svc.batteryLevel().toDouble(), op, num(right))
            "charging" -> svc.isCharging()
            "internet_connected" -> svc.internetConnected()
            "wifi_connected" -> svc.wifiConnected()
            "device_locked" -> svc.deviceLocked()
            "orientation" -> svc.isLandscape() == right.trim().lowercase().startsWith("land")
            "random_chance" -> Random.nextInt(100) < (num(right)?.toInt() ?: 50)
            else -> false
        }
        return if (a.p("negate$s", "no") == "yes") !r else r
    }

    private fun num(s: String): Double? =
        s.trim().toDoubleOrNull() ?: CommandParser.parseNumber(s.trim().lowercase())?.toDouble()

    private fun numCompare(x: Double?, op: String, y: Double?): Boolean {
        if (x == null || y == null) return false
        return when (op) {
            "equals" -> x == y
            "not_equals" -> x != y
            "gt" -> x > y
            "lt" -> x < y
            "gte" -> x >= y
            "lte" -> x <= y
            else -> false
        }
    }

    private fun compare(l: String, op: String, r: String): Boolean {
        val a = l.trim()
        val b = r.trim()
        return when (op) {
            "equals" -> {
                val na = num(a)
                a.equals(b, ignoreCase = true) || (na != null && na == num(b))
            }
            "not_equals" -> !compare(l, "equals", r)
            "contains" -> a.contains(b, ignoreCase = true)
            "not_contains" -> !a.contains(b, ignoreCase = true)
            "starts_with" -> a.startsWith(b, ignoreCase = true)
            "ends_with" -> a.endsWith(b, ignoreCase = true)
            "matches" -> try {
                Regex(b, RegexOption.IGNORE_CASE).containsMatchIn(a)
            } catch (e: Exception) {
                false
            }
            "is_empty" -> a.isEmpty()
            "not_empty" -> a.isNotEmpty()
            "gt", "lt", "gte", "lte" -> numCompare(num(a), op, num(b))
            else -> false
        }
    }

    private fun minutes(s: String): Int? {
        val p = s.trim().split(":")
        if (p.size != 2) return null
        val h = p[0].trim().toIntOrNull() ?: return null
        val m = p[1].trim().toIntOrNull() ?: return null
        return h * 60 + m
    }

    private fun timeBetween(start: String, end: String): Boolean {
        val s = minutes(start) ?: return false
        val e = minutes(end) ?: return false
        val c = Calendar.getInstance()
        val now = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
        return if (s <= e) now in s..e else now >= s || now <= e
    }

    private fun dayMatches(list: String): Boolean {
        val names = listOf("sun", "mon", "tue", "wed", "thu", "fri", "sat")
        val today = names[Calendar.getInstance().get(Calendar.DAY_OF_WEEK) - 1]
        val tokens = list.lowercase().split(",", " ").map { it.trim() }.filter { it.isNotEmpty() }
        return tokens.any {
            when (it) {
                "weekday", "weekdays" -> today in listOf("mon", "tue", "wed", "thu", "fri")
                "weekend", "weekends" -> today in listOf("sat", "sun")
                else -> it.take(3) == today
            }
        }
    }

    // ---- human-readable summary for the editor ----
    fun describe(a: Action): String {
        val first = describeOne(a, "")
        val c2 = a.p("check2", "none")
        if (c2.isBlank() || c2 == "none") return first
        return first + " " + a.p("join", "and").uppercase() + " " + describeOne(a, "2")
    }

    private fun describeOne(a: Action, s: String): String {
        val check = a.p("check$s", "value")
        val left = a.p("left$s")
        val op = a.p("op$s", "equals").replace('_', ' ')
        val right = a.p("right$s")
        val core = when (check) {
            "value" -> "$left $op $right".trim()
            "foreground_app" -> "app $op “$right”"
            "text_on_screen" -> "screen shows “$right”"
            "app_installed" -> "“$right” installed"
            "time_between" -> "time $left–$right"
            "day_of_week" -> "day is $right"
            "battery_level" -> "battery $op $right"
            "orientation" -> "orientation $right"
            "random_chance" -> "$right% chance"
            else -> check.replace('_', ' ')
        }
        return if (a.p("negate$s", "no") == "yes") "NOT ($core)" else core
    }
}
