package com.github.tgwsproxy

import android.os.Bundle
import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity

/**
 * Simple help screen that loads localized HTML from assets.
 */
class HelpActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Apply locale before setContentView
        LocaleUtils.applyLocale(this)
        val webView = WebView(this)
        setContentView(webView)

        val lang = LocaleUtils.getAppLanguage(this)
        val htmlFile = when (lang) {
            "ru" -> "help_ru.html"
            "en" -> "help_en.html"
            else -> {
                // auto-detect
                val sys = resources.configuration.locales.get(0)
                if (sys.language == "ru") "help_ru.html" else "help_en.html"
            }
        }
        webView.loadUrl("file:///android_asset/$htmlFile")
    }
}
