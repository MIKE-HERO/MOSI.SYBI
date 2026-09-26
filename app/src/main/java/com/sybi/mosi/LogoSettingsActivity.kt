package com.sybi.mosi

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import coil.ImageLoader
import coil.decode.SvgDecoder
import coil.request.ImageRequest
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

class LogoSettingsActivity : BaseActivity() {

    private lateinit var imgPreview: ImageView
    private lateinit var viewColorPreview: View
    private lateinit var btnChangeLogo: Button
    private lateinit var etMainTitle: EditText
    private lateinit var btnSaveTitle: Button
    private lateinit var btnCustomColor: Button
    private lateinit var switchMulticolor: Switch
    private lateinit var btnRegenerateColors: Button
    private lateinit var multicolorDots: List<View>
    private val prefs by lazy { getSharedPreferences("AppPrefs", Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)
        setContentView(R.layout.activity_logo_settings)

        val rootView = findViewById<View>(android.R.id.content)
        val sideBar = findViewById<View>(R.id.sideBarLayout)
        btnChangeLogo = findViewById(R.id.btnChangeLogo)
        etMainTitle = findViewById(R.id.etMainTitle)
        btnSaveTitle = findViewById(R.id.btnSaveTitle)
        btnCustomColor = findViewById(R.id.btnCustomColor)

        val colorReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val newColor = intent.getStringExtra("new_color")
                if (newColor != null) {
                    rootView.setBackgroundColor(Color.parseColor(newColor))
                    if (sideBar != null) {
                        sideBar.setBackgroundColor(Color.parseColor(newColor))
                    }
                    val colorList = android.content.res.ColorStateList.valueOf(Color.parseColor(newColor))
                    btnChangeLogo.backgroundTintList = colorList
                    btnSaveTitle.backgroundTintList = colorList
                    btnCustomColor.backgroundTintList = colorList
                }
            }
        }
        LocalBroadcastManager.getInstance(this).registerReceiver(colorReceiver, IntentFilter("ACTION_UPDATE_THEME"))

        // Cargar el color guardado al inicio
        val savedColor = prefs.getString("BackgroundColor", "#0F3E82")
        rootView.setBackgroundColor(Color.parseColor(savedColor))
        if (sideBar != null) {
            sideBar.setBackgroundColor(Color.parseColor(savedColor))
        }
        val initialColorList = android.content.res.ColorStateList.valueOf(Color.parseColor(savedColor))
        btnChangeLogo.backgroundTintList = initialColorList
        btnSaveTitle.backgroundTintList = initialColorList
        btnCustomColor.backgroundTintList = initialColorList

        imgPreview = findViewById(R.id.imgLogoPreview)
        viewColorPreview = findViewById(R.id.viewColorPreview)

        // Cargar el título guardado
        loadSavedTitle()

        // Cargar el logo guardado
        loadSavedLogo()

        // Cargar el color guardado en la vista previa
        loadCurrentColor()

        // 1. Botón Regresar
        findViewById<LinearLayout>(R.id.btnBackLogo).setOnClickListener {
            finish()
        }

        // 2. Botón Guardar Título
        btnSaveTitle.setOnClickListener {
            saveTitle()
        }

        // 3. Botón Cambiar Logo
        btnChangeLogo.setOnClickListener {
            openGallery()
        }

        // 4. Configurar botones de colores predefinidos
        setupColorButtons()

        // 5. Botón Paleta de colores personalizada
        btnCustomColor.setOnClickListener {
            showColorPickerDialog()
        }

        // 6. Modo multicolor
        switchMulticolor = findViewById(R.id.switchMulticolor)
        btnRegenerateColors = findViewById(R.id.btnRegenerateColors)
        multicolorDots = listOf(
            findViewById(R.id.dotMulticolor1),
            findViewById(R.id.dotMulticolor2),
            findViewById(R.id.dotMulticolor3),
            findViewById(R.id.dotMulticolor4),
            findViewById(R.id.dotMulticolor5)
        )
        setupMulticolorSection()
    }

    private fun setupMulticolorSection() {
        switchMulticolor.isChecked = prefs.getBoolean("MulticolorEnabled", false)
        loadMulticolorPreview()

        switchMulticolor.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("MulticolorEnabled", isChecked).apply()
            if (isChecked && prefs.getString("MulticolorColors", null).isNullOrBlank()) {
                regenerateMulticolorColors()
            } else {
                notifyMulticolorChanged()
            }
        }

        btnRegenerateColors.setOnClickListener {
            regenerateMulticolorColors()
        }
    }

    private fun regenerateMulticolorColors() {
        val colors = LogoColorTheme.generateRandomPalette()
        val hexColors = colors.joinToString(",") { String.format("#%06X", 0xFFFFFF and it) }
        prefs.edit().putString("MulticolorColors", hexColors).apply()
        showMulticolorPreview(colors)
        notifyMulticolorChanged()
        Toast.makeText(this, "Colores regenerados", Toast.LENGTH_SHORT).show()
    }

    private fun loadMulticolorPreview() {
        val saved = prefs.getString("MulticolorColors", null)
        val colors = if (!saved.isNullOrBlank()) {
            saved.split(",").mapNotNull { hex ->
                try { Color.parseColor(hex) } catch (e: Exception) { null }
            }
        } else {
            emptyList()
        }
        if (colors.isNotEmpty()) {
            showMulticolorPreview(colors)
        }
    }

    private fun showMulticolorPreview(colors: List<Int>) {
        val fallback = Color.parseColor("#0F3E82")
        for (i in multicolorDots.indices) {
            val color = colors.getOrNull(i) ?: colors.lastOrNull() ?: fallback
            multicolorDots[i].setBackgroundColor(color)
        }
    }

    private fun notifyMulticolorChanged() {
        val intent = Intent("ACTION_UPDATE_MULTICOLOR")
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun loadSavedTitle() {
        val defaultTitle = "MÓDULO DE SALUD INTEGRAL"
        val savedTitle = prefs.getString("AppTitle", defaultTitle) ?: defaultTitle
        etMainTitle.setText(savedTitle)
    }

    private fun saveTitle() {
        val newTitle = etMainTitle.text.toString().trim()
        if (newTitle.isNotEmpty()) {
            val editor = prefs.edit()
            editor.putString("AppTitle", newTitle)
            editor.apply()

            // Notificar a MainActivity
            val intent = Intent("ACTION_UPDATE_TITLE")
            intent.putExtra("main_title", newTitle)
            LocalBroadcastManager.getInstance(this).sendBroadcast(intent)

            hideKeyboardAndClearFocus()
            Toast.makeText(this, "Título actualizado correctamente", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "El título no puede estar vacío", Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadCurrentColor() {
        val defaultColor = "#0F3E82"
        val savedColor = prefs.getString("BackgroundColor", defaultColor)
        viewColorPreview.setBackgroundColor(Color.parseColor(savedColor))
    }

    private fun loadSavedLogo() {
        val logoPath = prefs.getString("LogoPath", null)
        if (logoPath != null) {
            loadLogo(imgPreview, logoPath)
        } else {
            imgPreview.setImageResource(R.drawable.sybi_logo_blanco)
        }
    }

    private fun loadLogo(imageView: ImageView, path: String) {
        try {
            val imageLoader = ImageLoader.Builder(imageView.context)
                .components {
                    add(SvgDecoder.Factory())
                }
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

    private fun setupColorButtons() {
        val colorMap = mapOf(
            R.id.btnColorAzul to "#0F3E82",
            R.id.btnColorCian to "#00FFFF",
            R.id.btnColorRojo to "#D32F2F",
            R.id.btnColorVerde to "#388E3C",
            R.id.btnColorAmarillo to "#FBC02D",
            R.id.btnColorNaranja to "#F57C00",
            R.id.btnColorMorado to "#7B1FA2",
            R.id.btnColorRosa to "#E91E63",
            R.id.btnColorGris to "#757575"
        )

        for ((id, hex) in colorMap) {
            findViewById<View>(id)?.setOnClickListener {
                changeColor(hex)
            }
        }
    }

    private fun changeColor(hexColor: String) {
        viewColorPreview.setBackgroundColor(Color.parseColor(hexColor))

        val editor = prefs.edit()
        editor.putString("BackgroundColor", hexColor)
        editor.putBoolean("MulticolorEnabled", false)
        editor.apply()

        if (::switchMulticolor.isInitialized) {
            switchMulticolor.isChecked = false
        }

        val intent = Intent("ACTION_UPDATE_THEME")
        intent.putExtra("new_color", hexColor)
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)

        Toast.makeText(this, "¡Color actualizado en toda la app!", Toast.LENGTH_SHORT).show()
    }

    private fun showColorPickerDialog() {
        val savedColorHex = prefs.getString("BackgroundColor", "#0F3E82") ?: "#0F3E82"
        var currentColor = try {
            Color.parseColor(savedColorHex)
        } catch (e: Exception) {
            Color.parseColor("#0F3E82")
        }

        val hsv = FloatArray(3)
        Color.colorToHSV(currentColor, hsv)
        var currentHue = hsv[0]
        var currentSat = hsv[1]
        var currentVal = hsv[2]

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 20)
        }

        // 1. Panel 2D Saturación / Valor
        val satValCard = CardView(this).apply {
            radius = 16f
            cardElevation = 4f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                380
            ).apply {
                bottomMargin = 20
            }
        }
        val satValView = ColorPickerSatValView(this).apply {
            setSatVal(currentHue, currentSat, currentVal)
        }
        satValCard.addView(satValView)
        container.addView(satValCard)

        // 2. Fila con Vista Previa + Barra Rainbow Hue
        val middleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 20
            }
        }

        val previewCard = CardView(this).apply {
            radius = 30f
            cardElevation = 4f
            layoutParams = LinearLayout.LayoutParams(60, 60)
        }
        val colorPreview = View(this).apply {
            setBackgroundColor(currentColor)
        }
        previewCard.addView(colorPreview)
        middleRow.addView(previewCard)

        val hueView = ColorPickerHueView(this).apply {
            setHue(currentHue)
            layoutParams = LinearLayout.LayoutParams(
                0,
                45,
                1f
            ).apply {
                marginStart = 25
            }
        }
        middleRow.addView(hueView)
        container.addView(middleRow)

        // 3. Campo de entrada HEX
        val hexInput = EditText(this).apply {
            hint = "#RRGGBB"
            textSize = 16f
            gravity = Gravity.CENTER
            setText(String.format("#%06X", 0xFFFFFF and currentColor))
            filters = arrayOf(InputFilter.LengthFilter(7))
            background = ContextCompat.getDrawable(this@LogoSettingsActivity, android.R.drawable.editbox_background)
            setPadding(15, 15, 15, 15)
        }
        val hexLayoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomMargin = 10
        }
        container.addView(hexInput, hexLayoutParams)

        // Control de sincronización
        var isUpdatingUI = false

        fun updateAllControls(newColor: Int, source: String) {
            if (isUpdatingUI) return
            isUpdatingUI = true

            currentColor = newColor
            colorPreview.setBackgroundColor(newColor)

            Color.colorToHSV(newColor, hsv)
            currentHue = hsv[0]
            currentSat = hsv[1]
            currentVal = hsv[2]

            if (source != "SAT_VAL") {
                satValView.setSatVal(currentHue, currentSat, currentVal)
            }
            if (source != "HUE") {
                hueView.setHue(currentHue)
            }
            if (source != "HEX") {
                val r = Color.red(newColor)
                val g = Color.green(newColor)
                val b = Color.blue(newColor)
                hexInput.setText(String.format("#%02X%02X%02X", r, g, b))
            }

            isUpdatingUI = false
        }

        // Callbacks
        satValView.onSatValChangedListener = { s, v ->
            currentSat = s
            currentVal = v
            val newColor = Color.HSVToColor(floatArrayOf(currentHue, currentSat, currentVal))
            updateAllControls(newColor, source = "SAT_VAL")
        }

        hueView.onHueChangedListener = { h ->
            currentHue = h
            satValView.setHue(currentHue)
            val newColor = Color.HSVToColor(floatArrayOf(currentHue, currentSat, currentVal))
            updateAllControls(newColor, source = "HUE")
        }

        hexInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (!isUpdatingUI) {
                    val text = s?.toString()?.trim() ?: ""
                    val formatted = if (text.startsWith("#")) text else "#$text"
                    if (formatted.length == 7) {
                        try {
                            val parsed = Color.parseColor(formatted)
                            updateAllControls(parsed, source = "HEX")
                        } catch (_: Exception) {}
                    }
                }
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        val dialog = AlertDialog.Builder(this)
            .setTitle("Paleta de colores")
            .setView(container)
            .setPositiveButton("Aceptar") { _, _ ->
                val hexString = String.format("#%02X%02X%02X", Color.red(currentColor), Color.green(currentColor), Color.blue(currentColor))
                changeColor(hexString)
            }
            .setNegativeButton("Cancelar", null)
            .create()

        setupDialogKeyboardBehavior(dialog)
        dialog.show()
    }

    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            try {
                val imageUri: Uri = result.data!!.data!!

                // Copiar la imagen a almacenamiento interno para que persista
                val savedPath = copyImageToInternalStorage(imageUri)

                if (savedPath != null) {
                    // Mostrar en vista previa
                    loadLogo(imgPreview, savedPath)

                    // Guardar ruta en preferencias
                    val editor = prefs.edit()
                    editor.putString("LogoPath", savedPath)
                    editor.apply()

                    // Notificar a MainActivity
                    val intent = Intent("ACTION_UPDATE_LOGO")
                    intent.putExtra("logo_path", savedPath)
                    LocalBroadcastManager.getInstance(this).sendBroadcast(intent)

                    // Si el modo multicolor está activo, recalcular los colores con el nuevo logo
                    if (::switchMulticolor.isInitialized && switchMulticolor.isChecked) {
                        regenerateMulticolorColors()
                    }

                    Toast.makeText(this, "Logo actualizado correctamente", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Error al guardar la imagen", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this, "Error al cargar la imagen: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun copyImageToInternalStorage(uri: Uri): String? {
        return try {
            val inputStream: InputStream = contentResolver.openInputStream(uri) ?: return null
            val mimeType = contentResolver.getType(uri)
            val isSvg = mimeType == "image/svg+xml" || uri.toString().endsWith(".svg", ignoreCase = true)
            val extension = if (isSvg) ".svg" else ".png"

            val logoFile = File(filesDir, "logo$extension")
            val outputStream = FileOutputStream(logoFile)

            inputStream.use { input ->
                outputStream.use { output ->
                    input.copyTo(output)
                }
            }

            logoFile.absolutePath
        } catch (e: Exception) {
            null
        }
    }

    private fun openGallery() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "image/svg+xml"))
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        try {
            pickImageLauncher.launch(intent)
        } catch (e: Exception) {
            val fallbackIntent = Intent(Intent.ACTION_PICK).apply {
                type = "image/*"
            }
            pickImageLauncher.launch(fallbackIntent)
        }
    }
}

// =========================================================================
// Vista personalizada 2D para Saturación y Valor (Brillo)
// =========================================================================
class ColorPickerSatValView @JvmOverloads constructor(
    context: Context,
    attrs: android.util.AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var hue: Float = 0f
    private var saturation: Float = 1f
    private var value: Float = 1f

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = Color.WHITE
    }
    private val innerStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.BLACK
    }

    private var satShader: Shader? = null
    private var valShader: Shader? = null

    var onSatValChangedListener: ((saturation: Float, value: Float) -> Unit)? = null

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    fun setHue(h: Float) {
        this.hue = h
        satShader = null
        invalidate()
    }

    fun setSatVal(h: Float, s: Float, v: Float) {
        this.hue = h
        this.saturation = s.coerceIn(0f, 1f)
        this.value = v.coerceIn(0f, 1f)
        satShader = null
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        valShader = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            Color.TRANSPARENT, Color.BLACK,
            Shader.TileMode.CLAMP
        )
        satShader = null
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        if (satShader == null) {
            val hueColor = Color.HSVToColor(floatArrayOf(hue, 1f, 1f))
            satShader = LinearGradient(
                0f, 0f, w, 0f,
                Color.WHITE, hueColor,
                Shader.TileMode.CLAMP
            )
        }

        // Pasada 1: Gradiente horizontal (Blanco -> Color de Tono puro)
        paint.shader = satShader
        canvas.drawRect(0f, 0f, w, h, paint)

        // Pasada 2: Gradiente vertical (Transparente -> Negro)
        if (valShader == null) {
            valShader = LinearGradient(
                0f, 0f, 0f, h,
                Color.TRANSPARENT, Color.BLACK,
                Shader.TileMode.CLAMP
            )
        }
        paint.shader = valShader
        canvas.drawRect(0f, 0f, w, h, paint)

        // Dibujar el círculo selector
        val cx = saturation * w
        val cy = (1f - value) * h
        val radius = 18f

        canvas.drawCircle(cx, cy, radius, strokePaint)
        canvas.drawCircle(cx, cy, radius - 2f, innerStrokePaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                val w = width.toFloat()
                val h = height.toFloat()
                if (w > 0 && h > 0) {
                    saturation = (event.x / w).coerceIn(0f, 1f)
                    value = (1f - (event.y / h)).coerceIn(0f, 1f)
                    invalidate()
                    onSatValChangedListener?.invoke(saturation, value)
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}

// =========================================================================
// Vista personalizada para la Barra Rainbow de Tono (Hue)
// =========================================================================
class ColorPickerHueView @JvmOverloads constructor(
    context: Context,
    attrs: android.util.AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var hue: Float = 0f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = Color.WHITE
    }
    private val innerThumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.BLACK
    }

    private var hueShader: Shader? = null

    var onHueChangedListener: ((hue: Float) -> Unit)? = null

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    fun setHue(h: Float) {
        this.hue = h.coerceIn(0f, 360f)
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val rainbowColors = intArrayOf(
            Color.RED, Color.YELLOW, Color.GREEN,
            Color.CYAN, Color.BLUE, Color.MAGENTA, Color.RED
        )
        hueShader = LinearGradient(
            0f, 0f, w.toFloat(), 0f,
            rainbowColors, null,
            Shader.TileMode.CLAMP
        )
        paint.shader = hueShader
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        val rx = h / 2f
        canvas.drawRoundRect(0f, 0f, w, h, rx, rx, paint)

        val cx = (hue / 360f) * w
        val cy = h / 2f
        val radius = h / 2f - 2f

        canvas.drawCircle(cx.coerceIn(radius, w - radius), cy, radius, thumbPaint)
        canvas.drawCircle(cx.coerceIn(radius, w - radius), cy, radius - 2f, innerThumbPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                val w = width.toFloat()
                if (w > 0) {
                    hue = ((event.x / w) * 360f).coerceIn(0f, 360f)
                    invalidate()
                    onHueChangedListener?.invoke(hue)
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}