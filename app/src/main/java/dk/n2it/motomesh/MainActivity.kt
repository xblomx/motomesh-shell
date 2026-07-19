// Moto Mesh Shell v1.0 · 2026-07-19
// Thin native host: System WebView loads the unchanged PWA at https://app.moto-mesh.com.
// Because THIS app process holds RECORD_AUDIO + a microphone|location foreground service,
// getUserMedia and geolocation inside the WebView keep running when the app is backgrounded
// (e.g. behind Google Maps). That is the whole trick — and it is the platform-blessed one.
package dk.n2it.motomesh

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private val APP_URL = "https://app.moto-mesh.com"
    private val PERM_REQ = 4711

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        web = WebView(this)
        setContentView(web)

        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            setGeolocationEnabled(true)
            // Mark the shell so the PWA can detect it and enable shell-only UX later.
            userAgentString = userAgentString + " MotoMeshShell/1.0"
        }

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                val u = req.url.toString()
                // Keep the PWA inside the shell; hand everything else (Google Maps links!) to the OS.
                return if (u.startsWith(APP_URL)) false else {
                    try { startActivity(Intent(Intent.ACTION_VIEW, req.url)) } catch (_: Exception) {}
                    true
                }
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                // The user already granted mic to the APP; forward that grant to the page.
                runOnUiThread {
                    val wants = request.resources.filter {
                        it == PermissionRequest.RESOURCE_AUDIO_CAPTURE
                    }.toTypedArray()
                    if (wants.isNotEmpty()) request.grant(wants) else request.deny()
                }
            }
            override fun onGeolocationPermissionsShowPrompt(
                origin: String, callback: GeolocationPermissions.Callback
            ) { callback.invoke(origin, true, false) }
        }

        requestNeededPermissions()
        web.loadUrl(APP_URL)
    }

    private fun requestNeededPermissions() {
        val need = mutableListOf<String>()
        for (p in arrayOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION
        )) if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) need += p
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) need += Manifest.permission.POST_NOTIFICATIONS

        if (need.isNotEmpty()) ActivityCompat.requestPermissions(this, need.toTypedArray(), PERM_REQ)
        else startMeshService()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        if (code == PERM_REQ) startMeshService() // start regardless; service degrades gracefully
    }

    private fun startMeshService() {
        val i = Intent(this, MeshService::class.java)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
    }

    // Back = background the shell (ride goes on), never kill the page.
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (web.canGoBack()) web.goBack() else moveTaskToBack(true)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        if (isFinishing) stopService(Intent(this, MeshService::class.java))
        super.onDestroy()
    }
}
