// Moto Mesh Shell v1.6 · 2026-07-19
// Self-updater: reads https://app.moto-mesh.com/shell.json {"v":"1.2","url":"https://moto-mesh.com/app"},
// downloads the APK (following redirects) and hands it to Android's installer · one tap for the rider.
package dk.n2it.motomesh

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

object Updater {
    private const val META = "https://app.moto-mesh.com/shell.json"
    private var ran = false

    fun check(act: Activity) {
        if (ran) return; ran = true
        thread {
            try {
                val cur = act.packageManager.getPackageInfo(act.packageName, 0).versionName ?: "0"
                val meta = URL(META).openConnection() as HttpURLConnection
                meta.connectTimeout = 6000; meta.readTimeout = 6000
                val j = JSONObject(meta.inputStream.bufferedReader().readText())
                val latest = j.optString("v", "0")
                val url = j.optString("url", "")
                if (url.isBlank() || !newer(cur, latest)) return@thread
                val cred = act.getSharedPreferences("mm", Activity.MODE_PRIVATE).getString("dlc", "") ?: ""
                val dlUrl = if (cred.isBlank()) url else url + (if (url.contains("?")) "&" else "?") + "c=" + java.net.URLEncoder.encode(cred, "UTF-8")

                val out = File(act.getExternalFilesDir(null), "motomesh-shell-" + latest + ".apk")
                if (!out.exists() || out.length() < 100_000) {
                    var c = URL(dlUrl).openConnection() as HttpURLConnection
                    c.instanceFollowRedirects = true
                    c.connectTimeout = 8000; c.readTimeout = 30000
                    // follow one cross-scheme hop manually if needed
                    if (c.responseCode in 301..308) {
                        val loc = c.getHeaderField("Location") ?: return@thread
                        c = URL(loc).openConnection() as HttpURLConnection
                        c.connectTimeout = 8000; c.readTimeout = 30000
                    }
                    c.inputStream.use { i -> out.outputStream().use { o -> i.copyTo(o) } }
                }
                val head = ByteArray(2); out.inputStream().use { it.read(head) }
                if (out.length() < 200_000 || head[0] != 0x50.toByte() || head[1] != 0x4B.toByte()) {
                    out.delete()
                    act.runOnUiThread { Toast.makeText(act, "Update download failed \u00b7 try again later", Toast.LENGTH_LONG).show() }
                    return@thread
                }

                act.runOnUiThread {
                    AlertDialog.Builder(act)
                        .setTitle("Moto Mesh update")
                        .setMessage("Version " + latest + " is ready (you have " + cur + ").")
                        .setPositiveButton("INSTALL") { _, _ ->
                            try {
                                if (!act.packageManager.canRequestPackageInstalls()) {
                                    Toast.makeText(act, "Allow installs for Moto Mesh, then tap INSTALL again", Toast.LENGTH_LONG).show()
                                    act.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                        android.net.Uri.parse("package:" + act.packageName)))
                                    ran = false
                                    return@setPositiveButton
                                }
                                val uri = FileProvider.getUriForFile(act, "dk.n2it.motomesh.files", out)
                                act.startActivity(Intent(Intent.ACTION_VIEW)
                                    .setDataAndType(uri, "application/vnd.android.package-archive")
                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
                            } catch (e: Exception) {
                                Toast.makeText(act, "Installer error: " + (e.message ?: "unknown"), Toast.LENGTH_LONG).show()
                            }
                        }
                        .setNegativeButton("LATER", null)
                        .show()
                }
            } catch (_: Exception) {}
        }
    }

    private fun newer(cur: String, latest: String): Boolean {
        val a = cur.split("."); val b = latest.split(".")
        for (i in 0 until maxOf(a.size, b.size)) {
            val d = (b.getOrNull(i)?.toIntOrNull() ?: 0) - (a.getOrNull(i)?.toIntOrNull() ?: 0)
            if (d != 0) return d > 0
        }
        return false
    }
}
