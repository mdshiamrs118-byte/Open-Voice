package app.openvoice

/** Names, quick-settings tile labels and (Root/Shizuku) shell commands for system toggles. */
object SystemToggles {

    class Spec(val key: String, val title: String, val labels: List<String>, val on: String?, val off: String?)

    val specs = listOf(
        Spec("wifi", "Wi‑Fi", listOf("Wi-Fi", "Wi‑Fi", "WiFi", "Internet"), "svc wifi enable", "svc wifi disable"),
        Spec("bluetooth", "Bluetooth", listOf("Bluetooth"), "svc bluetooth enable", "svc bluetooth disable"),
        Spec("hotspot", "Hotspot", listOf("Hotspot", "Mobile Hotspot", "Tethering", "Portable hotspot"), null, null),
        Spec("airplane", "Airplane mode", listOf("Airplane mode", "Flight mode", "Aeroplane mode"),
            "cmd connectivity airplane-mode enable", "cmd connectivity airplane-mode disable"),
        Spec("location", "Location", listOf("Location"), "cmd location set-location-enabled true", "cmd location set-location-enabled false"),
        Spec("data", "Mobile data", listOf("Mobile data", "Cellular data", "Data"), "svc data enable", "svc data disable"),
        Spec("nfc", "NFC", listOf("NFC"), "svc nfc enable", "svc nfc disable"),
        Spec("dnd", "Do Not Disturb", listOf("Do Not Disturb", "Don't disturb", "DND", "Priority only"),
            "cmd notification set_dnd on", "cmd notification set_dnd off"),
        Spec("flashlight", "Flashlight", listOf("Flashlight", "Torch"), null, null),
        Spec("rotate", "Auto-rotate", listOf("Auto-rotate", "Auto rotate", "Rotation"),
            "settings put system accelerometer_rotation 1", "settings put system accelerometer_rotation 0"),
        Spec("battery_saver", "Battery Saver", listOf("Battery Saver", "Power saving", "Power saver"),
            "settings put global low_power 1", "settings put global low_power 0"),
        Spec("dark_mode", "Dark mode", listOf("Dark theme", "Dark mode"), "cmd uimode night yes", "cmd uimode night no"),
        Spec("night_light", "Night Light", listOf("Night Light", "Night Shift", "Eye comfort", "Reading mode"),
            "settings put secure night_display_activated 1", "settings put secure night_display_activated 0"),
        Spec("screen_record", "Screen record", listOf("Screen record", "Screen recorder"), null, null),
        Spec("cast", "Cast", listOf("Cast", "Screen cast", "Smart View"), null, null)
    )

    private val aliases: Map<String, String> = buildMap<String, String> {
        fun a(key: String, vararg names: String) = names.forEach { put(it, key) }
        a("wifi", "wifi", "wi fi", "wireless", "wlan")
        a("bluetooth", "bluetooth", "blue tooth")
        a("hotspot", "hotspot", "hot spot", "tethering", "mobile hotspot", "wifi hotspot", "wi fi hotspot", "portable hotspot")
        a("airplane", "airplane mode", "airplane", "flight mode", "aeroplane mode", "aeroplane", "flight")
        a("location", "location", "gps", "location services")
        a("data", "mobile data", "cellular data", "data", "cellular")
        a("nfc", "nfc")
        a("dnd", "do not disturb", "dnd", "don't disturb", "dont disturb")
        a("flashlight", "flashlight", "flash light", "torch", "flash")
        a("rotate", "auto rotate", "autorotate", "rotation", "screen rotation", "rotate", "auto rotation")
        a("battery_saver", "battery saver", "power saving", "power saver", "battery saving", "power saving mode", "low power mode")
        a("dark_mode", "dark mode", "dark theme")
        a("night_light", "night light", "night shift", "blue light filter", "eye comfort", "reading mode")
        a("screen_record", "screen record", "screen recorder", "screen recording")
        a("cast", "cast", "screen cast", "smart view", "screen mirroring")
    }

    fun norm(s: String): String =
        s.lowercase().replace("-", " ").replace("‑", " ")
            .replace(Regex("[^a-z0-9' ]"), "").replace(Regex("\\s+"), " ").trim()

    fun canon(raw: String): String = aliases[norm(raw)] ?: norm(raw)
    fun known(raw: String): Boolean = aliases.containsKey(norm(raw))
    fun spec(key: String): Spec? = specs.firstOrNull { it.key == key }
}
