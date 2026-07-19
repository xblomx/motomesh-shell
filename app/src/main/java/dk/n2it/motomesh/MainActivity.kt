// Moto Mesh Shell v1.8 · 2026-07-19
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
import android.app.DownloadManager
import android.content.ContentValues
import android.net.Uri
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import android.os.Handler
import android.os.Looper
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
    private var mmFocusReq: AudioFocusRequest? = null

    private lateinit var web: WebView
    private var fileCb: ValueCallback<Array<Uri>>? = null
    private lateinit var fileLauncher: ActivityResultLauncher<Intent>
    private var nfcUrl: String? = null
    private var nfcTimeout: Runnable? = null
    private val APP_URL = "https://app.moto-mesh.com"
    private val PERM_REQ = 4711

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        web = WebView(this)
        setContentView(web)

        fileLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { res ->
            fileCb?.onReceiveValue(
                WebChromeClient.FileChooserParams.parseResult(res.resultCode, res.data)
            )
            fileCb = null
        }

        // Blob-export bridge: the PWA hands base64 here; we land it in Downloads/MotoMesh.
        web.addJavascriptInterface(object {
            @JavascriptInterface
            fun saveFile(name: String, b64: String) {
                try {
                    val bytes = Base64.decode(b64, Base64.DEFAULT)
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, name.ifBlank { "motomesh.bin" })
                        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/MotoMesh")
                        put(MediaStore.Downloads.IS_PENDING, 1)
                    }
                    val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    uri?.let {
                        contentResolver.openOutputStream(it)?.use { os -> os.write(bytes) }
                        values.clear(); values.put(MediaStore.Downloads.IS_PENDING, 0)
                        contentResolver.update(it, values, null, null)
                    }
                    runOnUiThread { Toast.makeText(this@MainActivity, "Saved to Downloads/MotoMesh/" + name, Toast.LENGTH_SHORT).show() }
                } catch (e: Exception) {
                    runOnUiThread { Toast.makeText(this@MainActivity, "Save failed", Toast.LENGTH_SHORT).show() }
                }
            }
            @JavascriptInterface
            fun writeNfc(u: String) { runOnUiThread { startNfcWrite(u) } }
            @JavascriptInterface
            fun shareText(t: String) {
                try {
                    val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, t)
                    startActivity(Intent.createChooser(i, "Share"))
                } catch (_: Exception) {}
            }
            @JavascriptInterface
            fun audioFocus(mode: String) {
                runOnUiThread {
                    var res = -1
                    try {
                        val am = getSystemService(AUDIO_SERVICE) as AudioManager
                        if (mode == "duck") {
                            if (mmFocusReq == null) {
                                val attrs = AudioAttributes.Builder()
                                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                    .build()
                                mmFocusReq = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                                    .setAudioAttributes(attrs)
                                    .setWillPauseWhenDucked(false)
                                    .build()
                            }
                            res = am.requestAudioFocus(mmFocusReq!!)
                        } else {
                            res = mmFocusReq?.let { val r = am.abandonAudioFocusRequest(it); mmFocusReq = null; r } ?: 1
                        }
                    } catch (_: Exception) {}
                    try { web.evaluateJavascript("try{window.mmFocusResult&&mmFocusResult(\"" + mode + "\"," + res + ")}catch(e){}", null) } catch (_: Exception) {}
                }
            }
            @JavascriptInterface
            fun getUpdate() { runOnUiThread { Updater.force(this@MainActivity) } }
            @JavascriptInterface
            fun setUpdateToken(t: String) {
                try { getSharedPreferences("mm", MODE_PRIVATE).edit().putString("dlc", t.trim()).apply() } catch (_: Exception) {}
            }
        }, "MMShell")

        // Plain downloads (http/https) go to the system DownloadManager.
        web.setDownloadListener { url, _, _, _, _ ->
            try {
                if (url.startsWith("http")) {
                    val dm = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
                    dm.enqueue(DownloadManager.Request(Uri.parse(url))
                        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED))
                }
            } catch (_: Exception) { runOnUiThread { Toast.makeText(this, "Download could not start \u00b7 use the in-app update dialog instead", Toast.LENGTH_LONG).show() } }
        }

        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            setGeolocationEnabled(true)
            // Mark the shell so the PWA can detect it and enable shell-only UX later.
            userAgentString = userAgentString + " MotoMeshShell/1.12"
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
                        it == PermissionRequest.RESOURCE_AUDIO_CAPTURE ||
                        it == PermissionRequest.RESOURCE_VIDEO_CAPTURE
                    }.toTypedArray()
                    if (wants.isNotEmpty()) request.grant(wants) else request.deny()
                }
            }
            override fun onShowFileChooser(
                view: WebView, cb: ValueCallback<Array<Uri>>,
                params: FileChooserParams
            ): Boolean {
                fileCb?.onReceiveValue(null)
                fileCb = cb
                return try { fileLauncher.launch(params.createIntent()); true }
                catch (e: Exception) { fileCb = null; false }
            }
            override fun onGeolocationPermissionsShowPrompt(
                origin: String, callback: GeolocationPermissions.Callback
            ) { callback.invoke(origin, true, false) }
        }

        requestNeededPermissions()
        val deep = intent?.data?.takeIf { it.host == "app.moto-mesh.com" }?.toString()
        web.loadUrl(deep ?: APP_URL)
        web.postDelayed({ try { Updater.check(this) } catch (_: Exception) {} }, 8000)
    }

    private fun requestNeededPermissions() {
        val need = mutableListOf<String>()
        for (p in arrayOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA,
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

    private fun nfcResult(ok: Boolean, msg: String) {
        runOnUiThread {
            val safe = msg.replace("\\", "").replace("\"", "'")
            web.evaluateJavascript("window.mmNfcResult&&mmNfcResult(" + ok + ",\"" + safe + "\")", null)
            Toast.makeText(this, if (ok) "Tag written" else msg, Toast.LENGTH_SHORT).show()
        }
        stopNfc()
    }

    private fun stopNfc() {
        try { NfcAdapter.getDefaultAdapter(this)?.disableReaderMode(this) } catch (_: Exception) {}
        nfcTimeout?.let { Handler(Looper.getMainLooper()).removeCallbacks(it) }; nfcTimeout = null
    }

    private fun startNfcWrite(u: String) {
        val ad = NfcAdapter.getDefaultAdapter(this)
        if (ad == null || !ad.isEnabled) { nfcResult(false, "NFC is off or not available on this phone"); return }
        nfcUrl = u
        Toast.makeText(this, "Hold a tag flat against the top-back of the phone", Toast.LENGTH_LONG).show()
        val to = Runnable { nfcResult(false, "No tag detected in 30s") }
        nfcTimeout = to; Handler(Looper.getMainLooper()).postDelayed(to, 30000)
        ad.enableReaderMode(this, { tag -> handleTag(tag) },
            NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or
            NfcAdapter.FLAG_READER_NFC_F or NfcAdapter.FLAG_READER_NFC_V, null)
    }

    private fun handleTag(tag: Tag) {
        try {
            val u = nfcUrl ?: ""
            val msg = if (u.isBlank())
                NdefMessage(arrayOf(NdefRecord(NdefRecord.TNF_EMPTY, ByteArray(0), ByteArray(0), ByteArray(0))))
            else NdefMessage(arrayOf(NdefRecord.createUri(u)))
            val ndef = Ndef.get(tag)
            if (ndef != null) {
                ndef.connect()
                if (!ndef.isWritable) { nfcResult(false, "Tag is locked (read-only)"); return }
                if (ndef.maxSize < msg.toByteArray().size) { nfcResult(false, "Tag is too small for this link"); return }
                ndef.writeNdefMessage(msg); ndef.close()
                nfcResult(true, "")
            } else {
                val f = NdefFormatable.get(tag)
                if (f != null) { f.connect(); f.format(msg); f.close(); nfcResult(true, "") }
                else nfcResult(false, "Unsupported tag type")
            }
        } catch (e: Exception) { nfcResult(false, "Write failed: " + (e.message ?: "unknown")) }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.data?.takeIf { it.host == "app.moto-mesh.com" }?.let { web.loadUrl(it.toString()) }
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
        stopNfc()
        try { mmFocusReq?.let { (getSystemService(AUDIO_SERVICE) as AudioManager).abandonAudioFocusRequest(it) } } catch (_: Exception) {}
        if (isFinishing) stopService(Intent(this, MeshService::class.java))
        super.onDestroy()
    }
}
