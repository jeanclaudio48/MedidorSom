package com.example.medidorsom

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

// ---------------------------------------------------------------------------
// Cores (claro / escuro)
// ---------------------------------------------------------------------------
class Palette(night: Boolean) {
    val bg = Color.parseColor(if (night) "#0F1317" else "#EDF1F4")
    val card = Color.parseColor(if (night) "#182027" else "#FFFFFF")
    val tile = Color.parseColor(if (night) "#1F2A33" else "#E3E9EE")
    val ink = Color.parseColor(if (night) "#E8EEF2" else "#192127")
    val muted = Color.parseColor(if (night) "#8C9BA6" else "#5A6873")
    val line = Color.parseColor(if (night) "#2B3741" else "#D3DBE1")
    val ok = Color.parseColor(if (night) "#3FD18F" else "#168A58")
    val warn = Color.parseColor(if (night) "#F2AA38" else "#C27A00")
    val bad = Color.parseColor(if (night) "#FF5E53" else "#D3342B")
    val low = Color.parseColor(if (night) "#6FA8DC" else "#2F6FA8")
    val btn = Color.parseColor(if (night) "#E8EEF2" else "#192127")
    val btnInk = Color.parseColor(if (night) "#0F1317" else "#FFFFFF")
}

fun withAlpha(c: Int, a: Int): Int = (c and 0x00FFFFFF) or (a shl 24)

private fun fmt(v: Float): String =
    if (v <= -119f) "--" else String.format("%.1f", v).replace('-', '−')

// ---------------------------------------------------------------------------
// Logo do cabeçalho (mesmas barras do ícone do app)
// ---------------------------------------------------------------------------
class LogoView(c: Context, private val p: Palette) : View(c) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val heights = floatArrayOf(0.38f, 0.70f, 1.0f, 0.60f, 0.44f)

    override fun onDraw(cv: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val bw = w * 0.12f
        val gap = w * 0.075f
        val x0 = (w - (5 * bw + 4 * gap)) / 2f
        for (i in 0 until 5) {
            val hh = heights[i] * h * 0.86f
            val x = x0 + i * (bw + gap)
            paint.color = when (i) {
                2 -> p.ink
                1, 3 -> p.ok
                else -> withAlpha(p.ok, 150)
            }
            cv.drawRoundRect(x, (h - hh) / 2f, x + bw, (h + hh) / 2f, bw / 2f, bw / 2f, paint)
        }
    }
}

// ---------------------------------------------------------------------------
// Medidor segmentado (estilo LED) com zonas de cor e marcador de pico
// ---------------------------------------------------------------------------
class MeterView(c: Context, private val p: Palette) : View(c) {
    var rms = -120f
    var peak = -120f

    private val segs = 40
    private val step = 60f / segs // 1,5 dB por segmento
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = p.muted
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 11f, resources.displayMetrics)
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density

    private fun zone(d: Float): Int = when {
        d < -18f -> p.low
        d < -6f -> p.ok
        d < -2f -> p.warn
        else -> p.bad
    }

    override fun onDraw(cv: Canvas) {
        val w = width.toFloat()
        val top = dp(8f)
        val bh = dp(30f)
        val gap = dp(2f)
        val segW = (w - gap * (segs - 1)) / segs
        val r = dp(3f)

        paint.style = Paint.Style.FILL
        for (i in 0 until segs) {
            val mid = -60f + i * step + step / 2f
            val x0 = i * (segW + gap)
            paint.color = withAlpha(zone(mid), if (rms >= mid) 255 else 46)
            cv.drawRoundRect(x0, top, x0 + segW, top + bh, r, r, paint)
        }

        if (peak > -59f) {
            val i = ((peak + 60f) / step).toInt().coerceIn(0, segs - 1)
            val x0 = i * (segW + gap)
            paint.color = p.ink
            cv.drawRoundRect(x0, top - dp(4f), x0 + segW, top + bh + dp(4f), r, r, paint)
        }

        var d = -60
        while (d <= 0) {
            val x = ((d + 60f) / 60f) * w
            val label = if (d == 0) "0" else d.toString().replace('-', '−')
            txt.textAlign = when (d) {
                -60 -> Paint.Align.LEFT
                0 -> Paint.Align.RIGHT
                else -> Paint.Align.CENTER
            }
            cv.drawText(label, x, top + bh + dp(6f) + txt.textSize, txt)
            d += 10
        }
    }
}

// ---------------------------------------------------------------------------
// Gráfico dos últimos 30 segundos (20 pontos por segundo) com degradê
// ---------------------------------------------------------------------------
class HistoryView(c: Context, private val p: Palette) : View(c) {
    private val n = 600
    private val rmsA = FloatArray(n)
    private val peakA = FloatArray(n)
    private var head = 0
    private var size = 0
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val area = Path()
    private var shader: LinearGradient? = null
    private var shaderH = 0f
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = p.muted
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 10f, resources.displayMetrics)
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density

    fun push(r: Float, pk: Float) {
        rmsA[head] = r
        peakA[head] = pk
        head = (head + 1) % n
        if (size < n) size++
        invalidate()
    }

    fun clear() {
        head = 0
        size = 0
        invalidate()
    }

    fun recentPeak(count: Int): Float {
        var m = -120f
        val k = min(count, size)
        for (i in 1..k) {
            val v = peakA[(head - i + n) % n]
            if (v > m) m = v
        }
        return m
    }

    override fun onDraw(cv: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        fun y(d: Float): Float = h - ((d.coerceIn(-60f, 0f) + 60f) / 60f) * h

        // faixa ideal (-18 a -6 dBFS)
        paint.style = Paint.Style.FILL
        paint.color = withAlpha(p.ok, 36)
        cv.drawRect(0f, y(-6f), w, y(-18f), paint)

        // grade
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(1f)
        paint.color = p.line
        for (d in intArrayOf(-60, -40, -20, 0)) {
            val yy = y(d.toFloat()).coerceIn(1f, h - 1f)
            cv.drawLine(0f, yy, w, yy, paint)
            val t = if (d == 0) "0 dB" else d.toString().replace('-', '−') + " dB"
            val ty = if (d == 0) yy + label.textSize + dp(2f) else yy - dp(3f)
            cv.drawText(t, dp(4f), ty, label)
        }
        if (size < 2) return

        fun trace(arr: FloatArray, out: Path) {
            out.reset()
            for (i in 0 until size) {
                val idx = (head - size + i + n) % n
                val x = w * (n - size + i) / (n - 1f)
                val yy = y(arr[idx])
                if (i == 0) out.moveTo(x, yy) else out.lineTo(x, yy)
            }
        }

        // pico (linha fina)
        trace(peakA, path)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(1f)
        paint.strokeJoin = Paint.Join.ROUND
        paint.color = withAlpha(p.ink, 110)
        cv.drawPath(path, paint)

        // nível médio: área com degradê + linha
        trace(rmsA, path)
        area.set(path)
        area.lineTo(w, h)
        area.lineTo(w * (n - size) / (n - 1f), h)
        area.close()
        if (shader == null || shaderH != h) {
            shader = LinearGradient(0f, 0f, 0f, h, withAlpha(p.ok, 120), withAlpha(p.ok, 0), Shader.TileMode.CLAMP)
            shaderH = h
        }
        paint.style = Paint.Style.FILL
        paint.shader = shader
        cv.drawPath(area, paint)
        paint.shader = null

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(2.5f)
        paint.color = p.ok
        cv.drawPath(path, paint)
    }
}

// ---------------------------------------------------------------------------
// Botão com ícone + texto
// ---------------------------------------------------------------------------
class IconButton(c: Context, p: Palette, filled: Boolean) : LinearLayout(c) {
    private val icon = ImageView(c)
    private val caption = TextView(c)

    init {
        val d = resources.displayMetrics.density
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true
        val shape = GradientDrawable().apply {
            setColor(if (filled) p.btn else p.card)
            cornerRadius = 16f * d
            if (!filled) setStroke((1f * d).roundToInt(), p.line)
        }
        background = RippleDrawable(ColorStateList.valueOf(Color.argb(70, 128, 128, 128)), shape, null)
        val fg = if (filled) p.btnInk else p.ink
        icon.setColorFilter(fg)
        caption.setTextColor(fg)
        caption.textSize = 16f
        caption.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        val side = (22f * d).roundToInt()
        addView(icon, LayoutParams(side, side))
        addView(caption, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { leftMargin = (8f * d).roundToInt() })
    }

    fun set(iconRes: Int, text: String) {
        icon.setImageResource(iconRes)
        caption.text = text
        contentDescription = text
    }
}

// ---------------------------------------------------------------------------
// Tela principal
// ---------------------------------------------------------------------------
class MainActivity : Activity() {

    companion object {
        const val SAMPLE_RATE = 44100
        const val TARGET_DB = -12f
        const val REQ_MIC = 1
        const val BANNER_NONE = 0
        const val BANNER_PERMISSION = 1
        const val BANNER_SILENT = 2
        const val BANNER_ERROR = 3
    }

    private lateinit var pal: Palette
    private lateinit var audioManager: AudioManager
    private val main = Handler(Looper.getMainLooper())

    private lateinit var tvRms: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvTip: TextView
    private lateinit var tvSource: TextView
    private lateinit var tvChip: TextView
    private lateinit var tvPeakHold: TextView
    private lateinit var tvPeakMax: TextView
    private lateinit var tvClips: TextView
    private lateinit var tvMicName: TextView
    private lateinit var meter: MeterView
    private lateinit var history: HistoryView
    private lateinit var btnToggle: IconButton
    private lateinit var banner: LinearLayout
    private lateinit var tvBanner: TextView
    private lateinit var btnBanner: Button

    @Volatile private var currentSession = 0
    private var worker: Thread? = null
    private var userPaused = false
    private var askedPermission = false
    private var waitingPermission = false
    private var deadRetries = 0
    private var bannerMode = BANNER_NONE
    private var pillColor = 0

    private var devices: List<AudioDeviceInfo> = emptyList()
    private var deviceNames: List<String> = emptyList()
    private var selectedDeviceId = -1 // -1 = automático

    private var rmsDisp = -120f
    private var peakHold = -120f
    private var peakHoldT = 0L
    private var peakMax = -120f
    private var clips = 0
    private var lastClipT = -100000L
    private var silent = false

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            onDevicesChanged()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            onDevicesChanged()
        }
    }

    // ----------------------------------------------------------------- ciclo
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        pal = Palette(night)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = pal.bg
        window.navigationBarColor = pal.card
        if (!night) {
            var flags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            if (Build.VERSION.SDK_INT >= 26) flags = flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            window.decorView.systemUiVisibility = flags
        }

        buildUi()
        audioManager.registerAudioDeviceCallback(deviceCallback, main)
        refreshDevices()
        updateUi()
    }

    override fun onResume() {
        super.onResume()
        refreshDevices()
        deadRetries = 0
        if (waitingPermission) return
        if (!userPaused) tryStart() else updateUi()
    }

    override fun onPause() {
        super.onPause()
        stopCapture()
        updateUi()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            audioManager.unregisterAudioDeviceCallback(deviceCallback)
        } catch (e: Exception) {
        }
        stopCapture()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        waitingPermission = false
        if (results.isNotEmpty() && results[0] == PackageManager.PERMISSION_GRANTED) {
            hideBanner()
            if (!userPaused) tryStart() else updateUi()
        } else {
            showBanner(BANNER_PERMISSION)
            updateUi()
        }
    }

    // ------------------------------------------------------------ permissão
    private fun hasPermission() =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun requestMic() {
        askedPermission = true
        waitingPermission = true
        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
    }

    private fun openAppSettings() {
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null)))
        } catch (e: Exception) {
        }
    }

    private fun tryStart() {
        if (worker != null) return
        if (!hasPermission()) {
            if (!askedPermission) requestMic() else showBanner(BANNER_PERMISSION)
            updateUi()
            return
        }
        startCapture()
    }

    // ---------------------------------------------------------------- captura
    private fun openRecord(source: Int, size: Int): AudioRecord? {
        return try {
            val r = AudioRecord(source, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, size)
            if (r.state == AudioRecord.STATE_INITIALIZED) r else {
                r.release()
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun openBest(size: Int): Pair<AudioRecord, String>? {
        val unprocessed =
            audioManager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
        if (unprocessed) {
            openRecord(MediaRecorder.AudioSource.UNPROCESSED, size)
                ?.let { return Pair(it, "Sem processamento do sistema") }
        }
        openRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, size)
            ?.let { return Pair(it, "Processamento mínimo (voz)") }
        openRecord(MediaRecorder.AudioSource.MIC, size)
            ?.let { return Pair(it, "Padrão do sistema (pode ter ganho automático)") }
        return null
    }

    private fun startCapture() {
        if (worker != null) return
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) {
            showBanner(BANNER_ERROR, "Este aparelho não suporta a captura de áudio necessária.")
            updateUi()
            return
        }
        val chunk = SAMPLE_RATE / 20 // blocos de 50 ms
        val opened = openBest(max(minBuf, chunk * 2 * 4))
        if (opened == null) {
            showBanner(BANNER_ERROR,
                "Não foi possível abrir o microfone. Feche apps que possam estar usando e toque em Iniciar.")
            updateUi()
            return
        }
        val recorder = opened.first

        if (selectedDeviceId != -1) {
            devices.firstOrNull { it.id == selectedDeviceId }?.let { recorder.setPreferredDevice(it) }
        }

        hideBanner()
        silent = false
        rmsDisp = -120f
        peakHold = -120f
        tvSource.text = "Fonte: " + opened.second

        val session = ++currentSession
        val t = Thread { capture(recorder, session, chunk) }
        worker = t
        t.start()
        updateUi()
    }

    private fun stopCapture() {
        currentSession++
        val w = worker
        worker = null
        try {
            w?.join(500)
        } catch (e: InterruptedException) {
        }
    }

    private fun restartCapture() {
        stopCapture()
        tryStart()
    }

    private fun failed(msg: String) {
        stopCapture()
        showBanner(BANNER_ERROR, msg)
        updateUi()
    }

    private fun dbfs(v: Double): Float =
        if (v < 1.0) -120f else (20.0 * log10(v / 32768.0)).toFloat()

    // roda na thread de captura
    private fun capture(rec: AudioRecord, session: Int, chunk: Int) {
        val buf = ShortArray(chunk)
        var silentChunks = 0
        try {
            rec.startRecording()
            if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                main.post {
                    if (session == currentSession) {
                        failed("O microfone não iniciou. Ele pode estar em uso por outro app.")
                    }
                }
            } else {
                while (session == currentSession) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n < 0) {
                        main.post {
                            if (session == currentSession) {
                                if (n == AudioRecord.ERROR_DEAD_OBJECT && deadRetries < 3) {
                                    deadRetries++
                                    restartCapture()
                                } else {
                                    failed("A captura de áudio foi interrompida. Toque em Iniciar para tentar de novo.")
                                }
                            }
                        }
                        break
                    }
                    if (n == 0) continue

                    var sum = 0.0
                    var pk = 0
                    for (i in 0 until n) {
                        val s = buf[i].toInt()
                        sum += s.toDouble() * s
                        val a = if (s < 0) -s else s
                        if (a > pk) pk = a
                    }
                    val rmsDb = dbfs(sqrt(sum / n))
                    val peakDb = dbfs(pk.toDouble())
                    val clipped = pk >= 32600
                    if (pk == 0) silentChunks++ else silentChunks = 0
                    val isSilent = silentChunks >= 60 // 3 s de silêncio digital absoluto

                    main.post {
                        if (session == currentSession) onSample(rmsDb, peakDb, clipped, isSilent)
                    }
                }
            }
        } catch (e: Exception) {
            main.post {
                if (session == currentSession) {
                    failed("Erro ao ler o microfone: " + (e.message ?: "desconhecido"))
                }
            }
        } finally {
            try {
                rec.stop()
            } catch (e: Exception) {
            }
            rec.release()
        }
    }

    // ------------------------------------------------------------ dispositivos
    private fun devName(d: AudioDeviceInfo): String {
        val base = when (d.type) {
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Microfone do aparelho"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Fone com fio"
            AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET -> "Microfone USB"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth"
            else -> "Entrada de áudio"
        }
        val product = d.productName?.toString().orEmpty()
        return if (product.isNotBlank() && d.type != AudioDeviceInfo.TYPE_BUILTIN_MIC) "$base ($product)" else base
    }

    private fun refreshDevices() {
        devices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).filter { it.isSource }
        val base = devices.map { devName(it) }
        val counts = base.groupingBy { it }.eachCount()
        val seen = HashMap<String, Int>()
        deviceNames = base.map { name ->
            if ((counts[name] ?: 1) > 1) {
                val k = (seen[name] ?: 0) + 1
                seen[name] = k
                "$name $k"
            } else name
        }
        updateMicName()
    }

    private fun onDevicesChanged() {
        refreshDevices()
        if (selectedDeviceId != -1 && devices.none { it.id == selectedDeviceId }) {
            selectedDeviceId = -1
            updateMicName()
            if (worker != null) restartCapture()
        }
    }

    private fun updateMicName() {
        val idx = devices.indexOfFirst { it.id == selectedDeviceId }
        tvMicName.text = if (selectedDeviceId == -1 || idx < 0) "Automático" else deviceNames[idx]
    }

    private fun chooseMic() {
        val items = arrayOf("Automático (padrão do aparelho)") + deviceNames.toTypedArray()
        val checked = devices.indexOfFirst { it.id == selectedDeviceId } + 1
        AlertDialog.Builder(this)
            .setTitle("Microfone de entrada")
            .setSingleChoiceItems(items, checked) { dialog, which ->
                selectedDeviceId = if (which == 0) -1 else devices[which - 1].id
                updateMicName()
                dialog.dismiss()
                if (worker != null) restartCapture()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ---------------------------------------------------------------- medições
    private fun onSample(rmsDb: Float, peakDb: Float, clipped: Boolean, isSilent: Boolean) {
        val now = SystemClock.uptimeMillis()
        rmsDisp = if (rmsDisp <= -119f) rmsDb
        else rmsDisp + (rmsDb - rmsDisp) * (if (rmsDb > rmsDisp) 0.6f else 0.15f)

        if (peakDb >= peakHold) {
            peakHold = peakDb
            peakHoldT = now
        } else if (now - peakHoldT > 1500) {
            peakHold = max(peakDb, peakHold - 0.8f)
        }
        if (peakDb > peakMax) peakMax = peakDb
        if (clipped) {
            if (now - lastClipT > 300) clips++
            lastClipT = now
        }
        history.push(rmsDb, peakDb)

        if (isSilent != silent) {
            silent = isSilent
            if (silent) showBanner(BANNER_SILENT) else if (bannerMode == BANNER_SILENT) hideBanner()
        }
        render(now)
    }

    private fun computeStatus(now: Long): Triple<String, String, Int> {
        val recent = history.recentPeak(60) // últimos 3 s
        if (now - lastClipT < 3000) {
            return Triple("Clipping: o sinal estourou",
                "Reduza o ganho ou o volume de entrada em pelo menos 6 dB.", pal.bad)
        }
        if (recent < -55f) {
            return Triple("Sem sinal", "Fale ou toque o som que você quer medir.", pal.muted)
        }
        val diff = (TARGET_DB - recent).roundToInt()
        return when {
            recent > -6f -> Triple("Alto demais",
                "Reduza cerca de ${abs(diff)} dB para ter margem contra distorção.", pal.warn)
            recent >= -18f -> Triple("Nível bom", "Mantenha o ajuste atual.", pal.ok)
            else -> Triple("Baixo",
                "Aumente cerca de $diff dB no ganho ou no volume de entrada.", pal.low)
        }
    }

    private fun setPill(color: Int) {
        if (color != pillColor) {
            pillColor = color
            tvStatus.background = rounded(withAlpha(color, 40), 20)
        }
    }

    private fun render(now: Long) {
        val (status, tip, color) = computeStatus(now)
        tvRms.text = fmt(rmsDisp)
        tvPeakHold.text = fmt(peakHold)
        tvPeakMax.text = fmt(peakMax)
        tvClips.text = clips.toString()
        tvStatus.text = status
        tvStatus.setTextColor(color)
        setPill(color)
        tvTip.text = tip
        meter.rms = rmsDisp
        meter.peak = peakHold
        meter.invalidate()
    }

    private fun resetPeaks() {
        peakMax = -120f
        peakHold = -120f
        clips = 0
        lastClipT = -100000L
        history.clear()
        tvPeakMax.text = "--"
        tvPeakHold.text = "--"
        tvClips.text = "0"
    }

    private fun updateChip() {
        val (t, c) = when {
            bannerMode != BANNER_NONE -> Pair("Atenção", pal.bad)
            worker != null -> Pair("Medindo", pal.ok)
            userPaused -> Pair("Pausado", pal.muted)
            else -> Pair("Parado", pal.muted)
        }
        tvChip.text = "●  $t"
        tvChip.setTextColor(c)
        tvChip.background = rounded(withAlpha(c, 38), 20)
    }

    private fun updateUi() {
        val running = worker != null
        btnToggle.set(if (running) R.drawable.ic_pause else R.drawable.ic_play,
            if (running) "Pausar" else "Iniciar")
        if (!running) {
            rmsDisp = -120f
            peakHold = -120f
            tvRms.text = "--"
            tvPeakHold.text = "--"
            tvStatus.setTextColor(pal.muted)
            setPill(pal.muted)
            when {
                bannerMode == BANNER_PERMISSION -> {
                    tvStatus.text = "Sem permissão"
                    tvTip.text = "Permita o uso do microfone para começar."
                }
                userPaused -> {
                    tvStatus.text = "Pausado"
                    tvTip.text = "Toque em Iniciar para voltar a medir."
                }
                else -> {
                    tvStatus.text = "Parado"
                    tvTip.text = "Toque em Iniciar para medir o microfone."
                }
            }
            meter.rms = -120f
            meter.peak = -120f
            meter.invalidate()
        }
        updateChip()
    }

    // ------------------------------------------------------------------ avisos
    private fun showBanner(mode: Int, msg: String? = null) {
        bannerMode = mode
        tvBanner.text = msg ?: when (mode) {
            BANNER_PERMISSION -> "O app precisa da permissão do microfone para medir o som."
            BANNER_SILENT ->
                "O microfone não está enviando sinal. Ele pode estar mudo, em uso por outro app (chamada, gravador) ou bloqueado nas configurações de privacidade."
            else -> "Ocorreu um erro no microfone."
        }
        if (mode == BANNER_PERMISSION) {
            btnBanner.text = if (!askedPermission || shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO))
                "Permitir microfone" else "Abrir configurações"
            btnBanner.visibility = View.VISIBLE
        } else {
            btnBanner.visibility = View.GONE
        }
        banner.visibility = View.VISIBLE
        updateChip()
    }

    private fun hideBanner() {
        bannerMode = BANNER_NONE
        banner.visibility = View.GONE
        updateChip()
    }

    // ---------------------------------------------------------------------- UI
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()

    private fun rounded(color: Int, radius: Int, stroke: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
            if (stroke != 0) setStroke(dp(1), stroke)
        }

    private fun label(t: String, sp: Float, color: Int, style: String = "sans-serif"): TextView =
        TextView(this).apply {
            text = t
            textSize = sp
            setTextColor(color)
            typeface = Typeface.create(style, Typeface.NORMAL)
        }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(18), dp(20), dp(18))
        background = rounded(pal.card, 24, pal.line)
    }

    private fun outlinedButton(t: String): Button = Button(this).apply {
        text = t
        isAllCaps = false
        textSize = 15f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        stateListAnimator = null
        setTextColor(pal.ink)
        val shape = rounded(pal.card, 14, pal.line)
        background = RippleDrawable(ColorStateList.valueOf(Color.argb(70, 128, 128, 128)), shape, null)
    }

    private fun lp(w: Int, h: Int, top: Int = 0, weight: Float = 0f): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(w, h, weight).apply { topMargin = dp(top) }

    private fun statTile(caption: String): Pair<LinearLayout, TextView> {
        val tile = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = rounded(pal.tile, 16)
        }
        val value = label("--", 20f, pal.ink, "sans-serif-medium")
        tile.addView(value)
        tile.addView(label(caption, 11f, pal.muted))
        return Pair(tile, value)
    }

    private fun buildUi() {
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT

        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(pal.bg)
        }
        val scroll = ScrollView(this)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(20))
        }
        scroll.addView(col, ViewGroup.LayoutParams(match, wrap))

        // cabeçalho: logo + título + chip de estado
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(LogoView(this, pal), LinearLayout.LayoutParams(dp(44), dp(44)))
        val titles = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(8), 0)
        }
        titles.addView(label("Medidor de som", 22f, pal.ink, "sans-serif-medium"))
        tvSource = label("Fonte: aguardando", 12f, pal.muted)
        titles.addView(tvSource)
        header.addView(titles, LinearLayout.LayoutParams(0, wrap, 1f))
        tvChip = label("", 12f, pal.muted, "sans-serif-medium").apply {
            setPadding(dp(12), dp(6), dp(12), dp(6))
        }
        header.addView(tvChip)
        col.addView(header, lp(match, wrap))

        // aviso (permissão, silêncio, erro)
        banner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = rounded(withAlpha(pal.bad, 28), 18, pal.bad)
            visibility = View.GONE
        }
        tvBanner = label("", 14f, pal.ink)
        btnBanner = outlinedButton("Permitir microfone").apply {
            setOnClickListener {
                if (!hasPermission()) {
                    if (!askedPermission || shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
                        requestMic()
                    } else {
                        openAppSettings()
                    }
                }
            }
        }
        banner.addView(tvBanner)
        banner.addView(btnBanner, lp(match, dp(46), 10))
        col.addView(banner, lp(match, wrap, 14))

        // cartão principal
        val cardMain = card()
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
        }
        tvRms = label("--", 72f, pal.ink, "sans-serif-light")
        val unit = label("dBFS", 16f, pal.muted).apply { setPadding(dp(8), 0, 0, dp(14)) }
        row.addView(tvRms)
        row.addView(unit)
        cardMain.addView(row)
        cardMain.addView(label("Nível médio (RMS)", 13f, pal.muted))
        tvStatus = label("", 17f, pal.ink, "sans-serif-medium").apply {
            setPadding(dp(14), dp(7), dp(14), dp(7))
        }
        cardMain.addView(tvStatus, lp(wrap, wrap, 14))
        tvTip = label("", 15f, pal.muted).apply { minLines = 2 }
        cardMain.addView(tvTip, lp(match, wrap, 8))
        meter = MeterView(this, pal)
        cardMain.addView(meter, lp(match, dp(64), 12))
        col.addView(cardMain, lp(match, wrap, 16))

        // estatísticas em três blocos
        val statsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val s1 = statTile("Pico recente (dBFS)")
        val s2 = statTile("Pico máximo (dBFS)")
        val s3 = statTile("Estouros")
        tvPeakHold = s1.second
        tvPeakMax = s2.second
        tvClips = s3.second
        tvClips.text = "0"
        statsRow.addView(s1.first, lp(0, wrap, 0, 1f))
        statsRow.addView(s2.first, lp(0, wrap, 0, 1f).apply { leftMargin = dp(8); rightMargin = dp(8) })
        statsRow.addView(s3.first, lp(0, wrap, 0, 1f))
        col.addView(statsRow, lp(match, wrap, 12))

        // gráfico
        val cardHist = card()
        cardHist.addView(label("Últimos 30 segundos", 15f, pal.ink, "sans-serif-medium"))
        history = HistoryView(this, pal)
        cardHist.addView(history, lp(match, dp(150), 10))
        cardHist.addView(label("Faixa verde: pico ideal da voz (−18 a −6 dBFS).", 12f, pal.muted), lp(match, wrap, 8))
        col.addView(cardHist, lp(match, wrap, 12))

        // seleção de microfone
        val micTile = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            isClickable = true
            isFocusable = true
            background = RippleDrawable(ColorStateList.valueOf(Color.argb(70, 128, 128, 128)),
                rounded(pal.card, 20, pal.line), null)
            setOnClickListener { chooseMic() }
        }
        val micIcon = ImageView(this).apply {
            setImageResource(R.drawable.ic_mic)
            setColorFilter(pal.ink)
        }
        micTile.addView(micIcon, LinearLayout.LayoutParams(dp(24), dp(24)))
        val micTexts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, dp(8), 0)
        }
        micTexts.addView(label("Entrada de áudio", 11f, pal.muted))
        tvMicName = label("Automático", 15f, pal.ink, "sans-serif-medium").apply {
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
        }
        micTexts.addView(tvMicName)
        micTile.addView(micTexts, LinearLayout.LayoutParams(0, wrap, 1f))
        micTile.addView(label("›", 26f, pal.muted))
        col.addView(micTile, lp(match, wrap, 12))

        col.addView(label(
            "dBFS mostra quanto do limite digital está em uso (0 é o máximo). Não é dB SPL. Nada é gravado nem enviado.",
            12f, pal.muted), lp(match, wrap, 14))

        // barra inferior com os controles principais
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(pal.card)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        btnToggle = IconButton(this, pal, true).apply {
            set(R.drawable.ic_play, "Iniciar")
            setOnClickListener {
                if (worker != null) {
                    userPaused = true
                    stopCapture()
                    updateUi()
                } else {
                    userPaused = false
                    deadRetries = 0
                    if (bannerMode != BANNER_PERMISSION) hideBanner()
                    tryStart()
                }
            }
        }
        val btnReset = IconButton(this, pal, false).apply {
            set(R.drawable.ic_reset, "Zerar")
            setOnClickListener { resetPeaks() }
        }
        bar.addView(btnToggle, lp(0, dp(56), 0, 2f))
        bar.addView(btnReset, lp(0, dp(56), 0, 1f).apply { leftMargin = dp(10) })

        outer.addView(scroll, LinearLayout.LayoutParams(match, 0, 1f))
        outer.addView(bar, LinearLayout.LayoutParams(match, wrap))
        setContentView(outer)
    }
}
