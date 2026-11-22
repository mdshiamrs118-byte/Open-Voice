package app.openvoice

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.content.res.XmlResourceParser
import android.net.Uri
import org.xmlpull.v1.XmlPullParser

/** A fully described intent that can be started directly (Normal mode) or through `am` (Root / Shizuku). */
class IntentSpec(
    val kind: String,
    val action: String,
    val data: String,
    val type: String,
    val categories: List<String>,
    val pkg: String,
    val cls: String,
    val extras: List<Triple<String, String, String>> // (type, key, value)
) {
    private fun act() = action.ifBlank { if (data.isNotBlank()) Intent.ACTION_VIEW else "" }

    fun toIntent(): Intent {
        val i = Intent()
        val a = act()
        if (a.isNotBlank()) i.action = a
        if (pkg.isNotBlank() && cls.isNotBlank()) {
            i.setClassName(pkg, if (cls.startsWith(".")) pkg + cls else cls)
        } else if (pkg.isNotBlank()) {
            i.setPackage(pkg)
        }
        if (data.isNotBlank() && type.isNotBlank()) i.setDataAndType(Uri.parse(data), type)
        else if (data.isNotBlank()) i.data = Uri.parse(data)
        else if (type.isNotBlank()) i.type = type
        categories.forEach { i.addCategory(it) }
        for ((t, k, v) in extras) {
            when (t) {
                "int" -> i.putExtra(k, v.trim().toIntOrNull() ?: 0)
                "long" -> i.putExtra(k, v.trim().toLongOrNull() ?: 0L)
                "bool" -> i.putExtra(k, v.trim().equals("true", ignoreCase = true))
                "float" -> i.putExtra(k, v.trim().toFloatOrNull() ?: 0f)
                else -> i.putExtra(k, v)
            }
        }
        return i
    }

    private fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"

    fun toAm(): String {
        val sb = StringBuilder(
            when (kind) {
                "broadcast" -> "am broadcast"
                "service" -> "am startservice"
                else -> "am start"
            }
        )
        val a = act()
        if (a.isNotBlank()) sb.append(" -a ").append(q(a))
        if (data.isNotBlank()) sb.append(" -d ").append(q(data))
        if (type.isNotBlank()) sb.append(" -t ").append(q(type))
        categories.forEach { sb.append(" -c ").append(q(it)) }
        if (pkg.isNotBlank() && cls.isNotBlank()) sb.append(" -n ").append(q("$pkg/$cls"))
        else if (pkg.isNotBlank()) sb.append(" -p ").append(q(pkg))
        for ((t, k, v) in extras) {
            val flag = when (t) {
                "int" -> "--ei"
                "long" -> "--el"
                "bool" -> "--ez"
                "float" -> "--ef"
                else -> "--es"
            }
            sb.append(" $flag ${q(k)} ${q(v)}")
        }
        return sb.toString()
    }
}

object IntentRunner {

    /** Lines like `key=value`, `int:count=5`, `bool:flag=true`. */
    fun parseExtras(text: String): List<Triple<String, String, String>> =
        text.lines().map { it.trim() }.filter { it.contains("=") }.map { line ->
            val eq = line.indexOf('=')
            var key = line.substring(0, eq).trim()
            val value = line.substring(eq + 1).trim()
            var type = "string"
            val colon = key.indexOf(':')
            if (colon > 0 && key.substring(0, colon) in listOf("int", "long", "bool", "float", "string")) {
                type = key.substring(0, colon)
                key = key.substring(colon + 1)
            }
            Triple(type, key, value)
        }

    fun specFrom(a: Action) = IntentSpec(
        kind = a.p("kind", "activity"),
        action = a.p("action").trim(),
        data = a.p("data").trim(),
        type = a.p("type").trim(),
        categories = a.p("category").split(",").map { it.trim() }.filter { it.isNotEmpty() },
        pkg = a.p("package").trim(),
        cls = a.p("class").trim(),
        extras = parseExtras(a.p("extras"))
    )

    /** Returns an error message, or null on success. */
    fun launch(svc: OpenVoiceService, mode: Mode, spec: IntentSpec): String? {
        if (mode != Mode.NORMAL) {
            Shell.exec(mode, spec.toAm())
            return null
        }
        val i = spec.toIntent()
        return try {
            when (spec.kind) {
                "broadcast" -> svc.sendBroadcast(i)
                "service" -> svc.startService(i)
                else -> {
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    svc.startActivity(i)
                }
            }
            null
        } catch (e: ActivityNotFoundException) {
            "no app can handle this intent"
        } catch (e: SecurityException) {
            "not allowed: target isn't exported. Use Root or Shizuku mode"
        } catch (e: Exception) {
            e.message ?: "intent failed"
        }
    }
}

/**
 * Reads the static shortcuts other apps declare (the ones you see when long-pressing their icon)
 * and launches them.
 */
object AppShortcuts {

    class SIntent(
        val action: String?, val data: String?, val pkg: String?, val cls: String?,
        val extras: List<Pair<String, String>>, val categories: List<String>
    ) {
        fun toSpec(defaultPkg: String) = IntentSpec(
            kind = "activity", action = action.orEmpty(), data = data.orEmpty(), type = "",
            categories = categories, pkg = pkg ?: defaultPkg, cls = cls.orEmpty(),
            extras = extras.map { Triple("string", it.first, it.second) }
        )
    }

    class Shortcut(val pkg: String, val id: String, val label: String, val intents: List<SIntent>)

    private class IntentB(var action: String?, var data: String?, var pkg: String?, var cls: String?) {
        val extras = ArrayList<Pair<String, String>>()
        val categories = ArrayList<String>()
        fun build() = SIntent(action, data, pkg, cls, extras, categories)
    }

    private const val NS = "http://schemas.android.com/apk/res/android"

    private fun str(res: Resources, p: XmlResourceParser, name: String): String {
        val rid = p.getAttributeResourceValue(NS, name, 0)
        if (rid != 0) {
            return try {
                res.getString(rid)
            } catch (e: Exception) {
                ""
            }
        }
        return p.getAttributeValue(NS, name) ?: ""
    }

    @Suppress("DEPRECATION")
    fun forPackage(ctx: Context, pkg: String): List<Shortcut> {
        val out = ArrayList<Shortcut>()
        val pm = ctx.packageManager
        val res = try {
            pm.getResourcesForApplication(pkg)
        } catch (e: Exception) {
            return out
        }
        val q = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(pkg)
        val seen = HashSet<Int>()
        for (ri in pm.queryIntentActivities(q, PackageManager.GET_META_DATA)) {
            val md = ri.activityInfo.metaData ?: continue
            val rid = md.getInt("android.app.shortcuts", 0)
            if (rid == 0 || !seen.add(rid)) continue
            try {
                parse(res, rid, pkg, out)
            } catch (_: Exception) {
            }
        }
        return out
    }

    private fun parse(res: Resources, rid: Int, pkg: String, out: MutableList<Shortcut>) {
        val p = res.getXml(rid)
        var inShortcut = false
        var id = ""
        var label = ""
        var enabled = true
        var intents = ArrayList<SIntent>()
        var cur: IntentB? = null
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) {
                when (p.name) {
                    "shortcut" -> {
                        inShortcut = true
                        id = p.getAttributeValue(NS, "shortcutId") ?: ""
                        label = str(res, p, "shortcutShortLabel").ifBlank { str(res, p, "shortcutLongLabel") }
                        enabled = p.getAttributeBooleanValue(NS, "enabled", true)
                        intents = ArrayList()
                    }
                    "intent" -> if (inShortcut) {
                        cur = IntentB(
                            str(res, p, "action").ifBlank { null },
                            str(res, p, "data").ifBlank { null },
                            str(res, p, "targetPackage").ifBlank { null },
                            str(res, p, "targetClass").ifBlank { null }
                        )
                    }
                    "extra" -> cur?.extras?.add(str(res, p, "name") to str(res, p, "value"))
                    "categories" -> cur?.categories?.add(str(res, p, "name"))
                }
            } else if (ev == XmlPullParser.END_TAG) {
                when (p.name) {
                    "intent" -> {
                        cur?.let { intents.add(it.build()) }
                        cur = null
                    }
                    "shortcut" -> {
                        if (enabled && id.isNotBlank()) out.add(Shortcut(pkg, id, label.ifBlank { id }, intents))
                        inShortcut = false
                    }
                }
            }
            ev = p.next()
        }
        p.close()
    }

    /** (package, app label, shortcuts) for every launchable app that declares static shortcuts. */
    @Suppress("DEPRECATION")
    fun appsWithShortcuts(ctx: Context): List<Triple<String, String, List<Shortcut>>> {
        val pm = ctx.packageManager
        val probe = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val pkgs = pm.queryIntentActivities(probe, 0).map { it.activityInfo.packageName }.distinct()
        return pkgs.mapNotNull { p ->
            val l = forPackage(ctx, p)
            if (l.isEmpty()) null
            else {
                val label = try {
                    pm.getApplicationLabel(pm.getApplicationInfo(p, 0)).toString()
                } catch (e: Exception) {
                    p
                }
                Triple(p, label, l)
            }
        }.sortedBy { it.second.lowercase() }
    }

    private fun find(list: List<Shortcut>, query: String): Shortcut? {
        val s = query.trim().lowercase()
        return list.firstOrNull { it.id.lowercase() == s || it.label.lowercase() == s }
            ?: list.firstOrNull { it.label.lowercase().contains(s) || it.id.lowercase().contains(s) }
    }

    /** Returns an error message, or null on success. */
    fun run(svc: OpenVoiceService, mode: Mode, app: String, shortcut: String): String? {
        val a = app.trim()
        val pkg = (if (a.contains('.') && svc.packageInstalled(a)) a else svc.findPackage(a))
            ?: return "app “$app” not found"
        val list = forPackage(svc, pkg)
        if (list.isEmpty()) return "“$app” doesn't publish app shortcuts"
        val sc = find(list, shortcut) ?: return "shortcut “$shortcut” not found in $app"
        val specs = sc.intents.map { it.toSpec(pkg) }
        if (specs.isEmpty()) return "that shortcut has no intent"
        if (mode != Mode.NORMAL) {
            Shell.exec(mode, specs.last().toAm())
            return null
        }
        return try {
            val arr = specs.map { it.toIntent() }.toTypedArray()
            arr[0].addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            svc.startActivities(arr)
            null
        } catch (e: ActivityNotFoundException) {
            "shortcut target not found"
        } catch (e: SecurityException) {
            "not allowed: this shortcut isn't exported. Use Root or Shizuku mode"
        } catch (e: Exception) {
            e.message ?: "shortcut failed"
        }
    }
}
