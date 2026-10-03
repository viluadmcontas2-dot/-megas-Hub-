package com.omegas.hub

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity

/** Casca: uma WebView em tela cheia que carrega `ui/index.html`. A ponte `Omegas` entra no P2. */
class MainActivity : AppCompatActivity() {
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.allowFileAccess = true
        web.setBackgroundColor(0xFF080C12.toInt())
        setContentView(web)
        web.loadUrl("file:///android_asset/ui/index.html")
    }
}
