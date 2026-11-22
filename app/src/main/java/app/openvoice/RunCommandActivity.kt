package app.openvoice

import android.app.Activity
import android.os.Bundle
import android.widget.Toast

/**
 * Invisible activity that runs one custom command. Used by pinned home-screen shortcuts, and by
 * other apps (Tasker, adb...) when "Allow other apps to trigger commands" is on:
 *   adb shell am start -a app.openvoice.action.RUN_COMMAND --es name "My command" --es text "optional"
 */
class RunCommandActivity : Activity() {

    companion object {
        const val ACTION_SHORTCUT = "app.openvoice.action.RUN_SHORTCUT"
        const val ACTION_RUN = "app.openvoice.action.RUN_COMMAND"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val svc = OpenVoiceService.instance
        val external = intent.action == ACTION_RUN
        val cmd = if (external) {
            if (!Prefs.allowExternal(this)) null
            else intent.getStringExtra("name")?.let { Store.findCommandByName(this, it) }
        } else {
            intent.getStringExtra("id")?.let { Store.findCommand(this, it) }
        }
        when {
            svc == null -> Toast.makeText(this, "Open Voice accessibility service is off", Toast.LENGTH_LONG).show()
            cmd == null -> Toast.makeText(
                this,
                if (external && !Prefs.allowExternal(this)) "External triggers are turned off in Open Voice" else "Command not found",
                Toast.LENGTH_LONG
            ).show()
            else -> svc.runCustom(cmd, intent.getStringExtra("text"), 400)
        }
        finish()
    }
}
