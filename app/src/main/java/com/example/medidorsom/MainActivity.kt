package com.example.medidorsom

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

class MainActivity : Activity() {

    private val sampleRate = 44100
    private lateinit var txtRms: TextView
    private lateinit var txtPico: TextView
    private lateinit var txtStatus: TextView
    private lateinit var txtFonte: TextView
    private lateinit var barra: ProgressBar

    @Volatile private var rodando = false
    @Volatile private var picoMaxDb = -120.0
    private var thread: Thread? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val raiz = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(48, 96, 48, 48)
        }
        txtRms = TextView(this).apply { textSize = 40f; text = "-- dBFS"; gravity = Gravity.CENTER }
        txtPico = TextView(this).apply { textSize = 18f; text = "Pico: -- dBFS"; gravity = Gravity.CENTER }
        txtStatus = TextView(this).apply { textSize = 22f; text = "Aguardando..."; gravity = Gravity.CENTER }
        txtFonte = TextView(this).apply { textSize = 12f; gravity = Gravity.CENTER }
        barra = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            minimumHeight = 80
        }
        val btnZerar = Button(this).apply {
            text = "Zerar pico"
            setOnClickListener { picoMaxDb = -120.0 }
        }

        raiz.addView(txtRms)
        raiz.addView(barra, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 80).apply { topMargin = 48; bottomMargin = 48 })
        raiz.addView(txtPico)
        raiz.addView(txtStatus)
        raiz.addView(btnZerar)
        raiz.addView(txtFonte)
        setContentView(raiz)
    }

    override fun onResume() {
        super.onResume()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            iniciar()
        } else {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }
    }

    override fun onPause() {
        super.onPause()
        parar()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (results.isNotEmpty() && results[0] == PackageManager.PERMISSION_GRANTED) iniciar()
        else txtStatus.text = "Permissão de microfone negada"
    }

    private fun criarAudioRecord(fonte: Int, tamanho: Int): AudioRecord? {
        return try {
            val ar = AudioRecord(fonte, sampleRate, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, tamanho)
            if (ar.state == AudioRecord.STATE_INITIALIZED) ar else { ar.release(); null }
        } catch (e: Exception) { null }
    }

    private fun iniciar() {
        if (rodando) return
        val minBuf = AudioRecord.getMinBufferSize(sampleRate,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val amostras = sampleRate / 10               // janelas de 100 ms
        val tamanho = max(minBuf, amostras * 4)

        // UNPROCESSED evita AGC/redução de ruído, dando medição "crua".
        var nomeFonte = "UNPROCESSED (sem AGC)"
        var rec = criarAudioRecord(MediaRecorder.AudioSource.UNPROCESSED, tamanho)
        if (rec == null) {
            nomeFonte = "MIC (pode ter AGC do aparelho)"
            rec = criarAudioRecord(MediaRecorder.AudioSource.MIC, tamanho)
        }
        if (rec == null) { txtStatus.text = "Não foi possível abrir o microfone"; return }
        txtFonte.text = "Fonte: $nomeFonte"

        val gravador = rec
        rodando = true
        thread = Thread {
            val buf = ShortArray(amostras)
            gravador.startRecording()
            while (rodando) {
                val n = gravador.read(buf, 0, buf.size)
                if (n <= 0) continue
                var soma = 0.0
                var pico = 0
                for (i in 0 until n) {
                    val s = buf[i].toInt()
                    soma += s.toDouble() * s
                    pico = max(pico, abs(s))
                }
                val rms = sqrt(soma / n)
                val rmsDb = dbfs(rms)
                val picoDb = dbfs(pico.toDouble())
                if (picoDb > picoMaxDb) picoMaxDb = picoDb
                runOnUiThread { atualizar(rmsDb, picoDb) }
            }
            gravador.stop()
            gravador.release()
        }.also { it.start() }
    }

    private fun parar() {
        rodando = false
        thread?.join(500)
        thread = null
    }

    private fun dbfs(valor: Double): Double =
        if (valor < 1.0) -120.0 else 20.0 * log10(valor / 32768.0)

    private fun atualizar(rmsDb: Double, picoDb: Double) {
        txtRms.text = String.format("%.1f dBFS", rmsDb)
        txtPico.text = String.format("Pico máx.: %.1f dBFS", picoMaxDb)
        barra.progress = (((rmsDb + 90.0) / 90.0) * 100).toInt().coerceIn(0, 100)

        when {
            picoDb >= -1.0 -> { txtStatus.text = "CLIPPING! Reduza o ganho"; txtStatus.setTextColor(Color.RED) }
            picoDb >= -6.0 -> { txtStatus.text = "Muito alto: arrisca distorcer"; txtStatus.setTextColor(Color.rgb(255, 140, 0)) }
            picoDb >= -18.0 -> { txtStatus.text = "Nível bom"; txtStatus.setTextColor(Color.rgb(0, 150, 0)) }
            picoDb >= -40.0 -> { txtStatus.text = "Baixo: aumente o ganho"; txtStatus.setTextColor(Color.rgb(200, 160, 0)) }
            else -> { txtStatus.text = "Muito baixo / silêncio"; txtStatus.setTextColor(Color.GRAY) }
        }
    }
}
