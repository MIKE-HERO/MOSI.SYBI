package com.sybi.mosi

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PorterDuff
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.Switch
import android.widget.TextView
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import coil.ImageLoader
import coil.decode.SvgDecoder
import coil.request.ImageRequest
import java.io.File

class ReportSettingsActivity : BaseActivity() {

    companion object {
        const val PREF_ALLOW_PRINT = "allow_print_results"
        const val PREF_ALLOW_EMAIL = "allow_email_results"
    }

    private val allSwitches = mutableListOf<Switch>()
    private lateinit var etPdfTitle: EditText
    private lateinit var etPdfSubtitle: EditText
    private lateinit var ivPreviewLogo: ImageView
    private lateinit var tvPreviewTitle: TextView
    private lateinit var tvPreviewSubtitle: TextView
    private lateinit var tvPreviewMeta: TextView

    private val appPrefs by lazy { getSharedPreferences("AppPrefs", Context.MODE_PRIVATE) }

    private val logoReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val logoPath = intent.getStringExtra("logo_path") ?: appPrefs.getString("LogoPath", null)
            loadLogo(ivPreviewLogo, logoPath)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_report_settings)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)

        val sideBar = findViewById<View>(R.id.sideBarLayout)

        val colorReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val newColor = intent.getStringExtra("new_color") ?: return
                sideBar.setBackgroundColor(Color.parseColor(newColor))
                applySwitchTint(Color.parseColor(newColor))
            }
        }
        LocalBroadcastManager.getInstance(this)
            .registerReceiver(colorReceiver, IntentFilter("ACTION_UPDATE_THEME"))
        LocalBroadcastManager.getInstance(this)
            .registerReceiver(logoReceiver, IntentFilter("ACTION_UPDATE_LOGO"))

        val savedColor = appPrefs.getString("BackgroundColor", "#0F3E82") ?: "#0F3E82"
        sideBar.setBackgroundColor(Color.parseColor(savedColor))

        findViewById<View>(R.id.btnBackReportSettings).setOnClickListener { finish() }

        // Switches
        val prefs = getSharedPreferences("DevicePrefs", Context.MODE_PRIVATE)
        setupSwitch(R.id.switchEnablePrint, PREF_ALLOW_PRINT, prefs)
        setupSwitch(R.id.switchEnableEmail, PREF_ALLOW_EMAIL, prefs)

        applySwitchTint(Color.parseColor(savedColor))

        // Personalización de PDF
        etPdfTitle = findViewById(R.id.etPdfTitle)
        etPdfSubtitle = findViewById(R.id.etPdfSubtitle)
        ivPreviewLogo = findViewById(R.id.ivPreviewLogo)
        tvPreviewTitle = findViewById(R.id.tvPreviewTitle)
        tvPreviewSubtitle = findViewById(R.id.tvPreviewSubtitle)
        tvPreviewMeta = findViewById(R.id.tvPreviewMeta)

        val defaultTitle = appPrefs.getString("AppTitle", "MÓDULO DE SALUD INTEGRAL") ?: "MÓDULO DE SALUD INTEGRAL"
        val savedPdfTitle = appPrefs.getString("PdfTitle", defaultTitle) ?: defaultTitle
        val savedPdfSubtitle = appPrefs.getString("PdfSubtitle", "") ?: ""

        etPdfTitle.setText(savedPdfTitle)
        etPdfSubtitle.setText(savedPdfSubtitle)

        tvPreviewTitle.text = savedPdfTitle
        tvPreviewSubtitle.text = savedPdfSubtitle
        tvPreviewSubtitle.visibility = if (savedPdfSubtitle.isBlank()) View.GONE else View.VISIBLE

        val logoPath = appPrefs.getString("LogoPath", null)
        loadLogo(ivPreviewLogo, logoPath)

        etPdfTitle.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val title = s.toString()
                appPrefs.edit().putString("PdfTitle", title).apply()
                tvPreviewTitle.text = if (title.isBlank()) defaultTitle else title
            }
        })

        etPdfSubtitle.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val subtitle = s.toString()
                appPrefs.edit().putString("PdfSubtitle", subtitle).apply()
                tvPreviewSubtitle.text = subtitle
                tvPreviewSubtitle.visibility = if (subtitle.isBlank()) View.GONE else View.VISIBLE
            }
        })
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(logoReceiver)
        } catch (_: Exception) {}
    }

    private fun loadLogo(imageView: ImageView, path: String?) {
        try {
            if (path.isNullOrBlank()) {
                imageView.setImageResource(R.drawable.sybi_logo_blanco)
                return
            }
            val imageLoader = ImageLoader.Builder(imageView.context)
                .components { add(SvgDecoder.Factory()) }
                .build()

            val file = File(path)
            val data: Any = if (file.exists()) file else Uri.parse(path)

            val request = ImageRequest.Builder(imageView.context)
                .data(data)
                .target(imageView)
                .error(R.drawable.sybi_logo_blanco)
                .placeholder(R.drawable.sybi_logo_blanco)
                .build()

            imageLoader.enqueue(request)
        } catch (e: Exception) {
            imageView.setImageResource(R.drawable.sybi_logo_blanco)
        }
    }

    private fun setupSwitch(switchId: Int, prefKey: String, prefs: android.content.SharedPreferences) {
        val sw = findViewById<Switch>(switchId)
        allSwitches.add(sw)
        sw.isChecked = prefs.getBoolean(prefKey, true)
        sw.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(prefKey, isChecked).apply()
        }
    }

    private fun applySwitchTint(originalColor: Int) {
        for (sw in allSwitches) {
            sw.thumbDrawable?.setColorFilter(originalColor, PorterDuff.Mode.SRC_ATOP)
            sw.trackDrawable?.setColorFilter(originalColor, PorterDuff.Mode.SRC_ATOP)
        }
    }
}
