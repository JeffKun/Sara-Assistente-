package com.sara.assistant

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

class MainActivity : Activity(), TextToSpeech.OnInitListener {
    private val REQ_INIT = 40
    private val REQ_AUDIO = 41

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var prefs: SharedPreferences
    private lateinit var ai: AiClient
    private lateinit var commands: Commands

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var recognizer: SpeechRecognizer? = null
    private var listenAfterPermission = false
    private var awaitingOverlay = false

    private lateinit var avatar: SaraAvatarView
    private lateinit var status: TextView
    private lateinit var chat: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var micButton: Button
    private lateinit var bubbleChip: TextView
    private lateinit var netBadge: TextView

    private val saved = ArrayList<Pair<Boolean, String>>()
    private val welcome = "Oi, meu bem! 💜 Sou a Sara. Toque no microfone para falar comigo ou escreva aqui embaixo. Funciono com e sem internet."

    private val netCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { ui.post { refreshNet() }; ui.postDelayed({ refreshNet() }, 1500) }
        override fun onLost(network: Network) { ui.post { refreshNet() } }
    }

    private val purple = Color.rgb(167, 139, 250)
    private val bg = Color.rgb(9, 9, 27)
    private val card = Color.rgb(22, 22, 48)
    private val deep = Color.rgb(83, 48, 176)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("sara", Context.MODE_PRIVATE)
        ai = AiClient(prefs)
        commands = Commands(this)
        tts = TextToSpeech(this, this)
        buildUi()
        restoreChat()
        refreshNet()
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.registerNetworkCallback(NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), netCallback)
        } catch (e: Exception) {}

        val needed = ArrayList<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) needed.add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) needed.add(Manifest.permission.POST_NOTIFICATIONS)
        if (needed.isNotEmpty()) requestPermissions(needed.toTypedArray(), REQ_INIT)

        handleLaunchIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLaunchIntent(intent)
    }

    private fun handleLaunchIntent(i: Intent?) {
        if (i?.getBooleanExtra("listen", false) == true) {
            i.removeExtra("listen")
            ui.postDelayed({ startListening() }, 350)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshNet()
        if (awaitingOverlay && Settings.canDrawOverlays(this)) {
            awaitingOverlay = false
            enableOverlay()
        }
        refreshBubbleChip()
    }

    // ---------- voz de saída ----------

    override fun onInit(code: Int) {
        if (code != TextToSpeech.SUCCESS) return
        val langResult = tts?.setLanguage(Locale("pt", "BR"))
        if (langResult == TextToSpeech.LANG_MISSING_DATA || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
            ui.post {
                addMessage("Dica: não achei a voz em português neste celular. Instale-a em Configurações > Idioma > Saída de texto para voz, para eu falar também sem internet.", false, false)
            }
        }
        tts?.setSpeechRate(0.96f)
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) { ui.post { setMode(SaraMode.SPEAKING) } }
            override fun onDone(utteranceId: String?) { ui.post { if (SaraState.mode == SaraMode.SPEAKING) setMode(SaraMode.IDLE) } }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { ui.post { setMode(SaraMode.IDLE) } }
            override fun onError(utteranceId: String?, errorCode: Int) { ui.post { setMode(SaraMode.IDLE) } }
        })
        ttsReady = true
    }

    private fun speak(text: String) {
        val clean = text.replace(Regex("[\\p{So}\\p{Sk}\\p{Cs}*#`_]"), "").trim()
        if (!ttsReady || clean.isEmpty()) { setMode(SaraMode.IDLE); return }
        tts?.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "sara-${System.currentTimeMillis()}")
    }

    private fun stopSpeaking() {
        tts?.stop()
        if (SaraState.mode == SaraMode.SPEAKING) setMode(SaraMode.IDLE)
    }

    // ---------- voz de entrada ----------

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) { SaraState.level = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f) }
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() { SaraState.level = 0f }
        override fun onEvent(eventType: Int, params: Bundle?) {}
        override fun onError(error: Int) {
            SaraState.level = 0f
            setMode(SaraMode.IDLE)
            status.text = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Não ouvi nada. Toque no microfone e tente de novo."
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Sem internet e sem voz offline. Baixe o português offline nas configurações de voz do Google, ou escreva para mim."
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Preciso da permissão do microfone."
                else -> "Não consegui ouvir agora. Tente de novo."
            }
        }
        override fun onResults(results: Bundle?) {
            SaraState.level = 0f
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (text.isNullOrBlank()) setMode(SaraMode.IDLE) else submit(text)
        }
        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (!text.isNullOrBlank() && SaraState.mode == SaraMode.LISTENING) status.text = "“$text”"
        }
    }

    private fun startListening() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            listenAfterPermission = true
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_AUDIO)
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            reply("Não encontrei um serviço de reconhecimento de voz neste celular.")
            return
        }
        stopSpeaking()
        if (recognizer == null) recognizer = SpeechRecognizer.createSpeechRecognizer(this).also { it.setRecognitionListener(listener) }
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, !Net.isOnline(this@MainActivity))
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        }
        setMode(SaraMode.LISTENING)
        try { recognizer?.startListening(i) } catch (e: Exception) { setMode(SaraMode.IDLE); reply("Não consegui iniciar o microfone.") }
    }

    private fun stopAll() {
        recognizer?.cancel()
        SaraState.level = 0f
        stopSpeaking()
        setMode(SaraMode.IDLE)
    }

    // ---------- conversa ----------

    private fun submit(raw: String) {
        val text = raw.trim()
        if (text.isEmpty()) return
        stopSpeaking()
        addMessage(text, true)
        val local = commands.handle(text)
        val online = Net.isOnline(this)
        when {
            local != null -> reply(local)
            ai.hasKey() && online -> {
                setMode(SaraMode.THINKING)
                ai.ask(text) { answer, networkFailed ->
                    reply(if (networkFailed) OfflineBrain.smallTalk(text) ?: answer else answer)
                }
            }
            else -> reply(
                OfflineBrain.smallTalk(text) ?: if (!online) OfflineBrain.NO_INTERNET_REPLY
                else "Ouvi você dizer: $text. Para eu conversar de verdade, coloque sua chave de IA em Configurações. Por enquanto consigo abrir apps, ligar, dizer as horas, ligar a lanterna e mais."
            )
        }
        refreshBubbleChip()
    }

    private fun reply(text: String) {
        addMessage(text, false)
        speak(text)
    }

    private fun setMode(m: SaraMode) {
        SaraState.mode = m
        status.text = when (m) {
            SaraMode.IDLE -> "Pronta para conversar"
            SaraMode.LISTENING -> "Ouvindo…"
            SaraMode.THINKING -> "Pensando…"
            SaraMode.SPEAKING -> "Falando…"
        }
        micButton.text = if (m == SaraMode.IDLE) "🎙" else "■"
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        when (requestCode) {
            REQ_AUDIO -> if (listenAfterPermission) { listenAfterPermission = false; if (granted) startListening() }
            Commands.REQ_CONTACTS -> commands.onContactsResult(granted)?.let { reply(it) }
        }
    }

    // ---------- interface ----------

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(dp(20), dp(12), dp(20), dp(12))
            setOnApplyWindowInsetsListener { v, insets -> applyInsets(v, insets); insets }
        }

        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(TextView(this).apply { text = "Sara ♡"; textSize = 26f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD })
        val sub = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        sub.addView(TextView(this).apply { text = "Sua assistente pessoal"; textSize = 13f; setTextColor(purple) })
        netBadge = TextView(this).apply { textSize = 12f; setPadding(dp(10), 0, 0, 0) }
        sub.addView(netBadge)
        titles.addView(sub)
        header.addView(titles, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(TextView(this).apply {
            text = "⚙"; textSize = 22f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            background = rounded(card, 22)
            contentDescription = "Configurações"
            setOnClickListener { showSettings() }
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
        root.addView(header)

        avatar = SaraAvatarView(this, false)
        root.addView(avatar, LinearLayout.LayoutParams(-1, dp(220)).apply { topMargin = dp(6) })

        status = TextView(this).apply { textSize = 14f; setTextColor(purple); gravity = Gravity.CENTER; setPadding(0, dp(2), 0, dp(6)) }
        root.addView(status, LinearLayout.LayoutParams(-1, -2))

        scroll = ScrollView(this)
        chat = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(chat)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        chips.addView(chip("▶ YouTube") { submit("abrir youtube") })
        chips.addView(chip("💬 WhatsApp") { submit("abrir whatsapp") })
        chips.addView(chip("⚙ Ajustes do Android") { startActivity(Intent(Settings.ACTION_SETTINGS)) })
        bubbleChip = chip("🫧 Bolha") { toggleBubble() }
        chips.addView(bubbleChip)
        val chipScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        chipScroll.addView(chips)
        root.addView(chipScroll, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        input = EditText(this).apply {
            hint = "Escreva para a Sara…"
            setHintTextColor(Color.GRAY); setTextColor(Color.WHITE); textSize = 15f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_SEND
            maxLines = 3
            background = rounded(card, 24)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND) { submit(text.toString()); setText(""); true } else false
            }
        }
        bar.addView(input, LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(roundButton("➤", card) { submit(input.text.toString()); input.setText("") }, LinearLayout.LayoutParams(dp(48), dp(48)).apply { leftMargin = dp(8) })
        micButton = roundButton("🎙", deep) {
            if (SaraState.mode == SaraMode.IDLE) startListening() else stopAll()
        }
        bar.addView(micButton, LinearLayout.LayoutParams(dp(52), dp(52)).apply { leftMargin = dp(8) })
        root.addView(bar, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })

        setContentView(root)
        setMode(SaraMode.IDLE)
    }

    @Suppress("DEPRECATION")
    private fun applyInsets(v: View, insets: WindowInsets) {
        val top: Int
        val bottom: Int
        if (Build.VERSION.SDK_INT >= 30) {
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            val ime = insets.getInsets(WindowInsets.Type.ime())
            top = bars.top
            bottom = maxOf(bars.bottom, ime.bottom)
        } else {
            top = insets.systemWindowInsetTop
            bottom = insets.systemWindowInsetBottom
        }
        v.setPadding(dp(20), top + dp(12), dp(20), bottom + dp(12))
    }

    private fun refreshNet() {
        if (!::netBadge.isInitialized) return
        val on = Net.isOnline(this)
        netBadge.text = if (on) "● Online" else "● Offline"
        netBadge.setTextColor(if (on) Color.rgb(110, 231, 183) else Color.rgb(251, 191, 36))
    }

    private fun restoreChat() {
        try {
            val arr = JSONArray(prefs.getString("chat", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                saved.add(o.getBoolean("u") to o.getString("t"))
                addMessage(o.getString("t"), o.getBoolean("u"), false)
            }
        } catch (e: Exception) {}
        if (saved.isEmpty()) addMessage(welcome, false)
    }

    private fun persistChat() {
        val arr = JSONArray()
        for (m in saved.takeLast(60)) arr.put(JSONObject().put("u", m.first).put("t", m.second))
        prefs.edit().putString("chat", arr.toString()).apply()
    }

    private fun addMessage(text: String, fromUser: Boolean, save: Boolean = true) {
        if (save) {
            saved.add(fromUser to text)
            if (saved.size > 80) saved.removeAt(0)
            persistChat()
        }
        val tv = TextView(this).apply {
            this.text = text
            textSize = 15f
            setTextColor(Color.WHITE)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = if (fromUser) rounded(deep, 18, Color.rgb(124, 92, 220)) else rounded(card, 18)
            maxWidth = (resources.displayMetrics.widthPixels * 0.8f).toInt()
            setTextIsSelectable(true)
        }
        chat.addView(tv, LinearLayout.LayoutParams(-2, -2).apply {
            topMargin = dp(8)
            gravity = if (fromUser) Gravity.END else Gravity.START
        })
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun chip(label: String, action: () -> Unit) = TextView(this).apply {
        text = label; textSize = 14f; setTextColor(Color.WHITE)
        setPadding(dp(14), dp(9), dp(14), dp(9))
        background = rounded(card, 20)
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(8) }
    }

    private fun roundButton(label: String, color: Int, action: () -> Unit) = Button(this).apply {
        text = label; textSize = 20f; setTextColor(Color.WHITE); isAllCaps = false
        setPadding(0, 0, 0, 0)
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color); setStroke(dp(1), Color.rgb(67, 54, 118)) }
        setOnClickListener { action() }
    }

    private fun rounded(color: Int, radius: Int, stroke: Int = Color.rgb(67, 54, 118)) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat(); setStroke(dp(1), stroke)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // ---------- bolha e configurações ----------

    private fun refreshBubbleChip() {
        if (::bubbleChip.isInitialized) bubbleChip.text = if (SaraState.bubbleOn) "🫧 Bolha: ligada" else "🫧 Bolha: desligada"
    }

    private fun toggleBubble() {
        if (SaraState.bubbleOn) {
            stopService(Intent(this, FloatingSaraService::class.java))
            SaraState.bubbleOn = false
            refreshBubbleChip()
        } else enableOverlay()
    }

    private fun enableOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            awaitingOverlay = true
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            Toast.makeText(this, "Permita “Exibir sobre outros apps” e volte para cá.", Toast.LENGTH_LONG).show()
            return
        }
        val i = Intent(this, FloatingSaraService::class.java)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
        SaraState.bubbleOn = true
        refreshBubbleChip()
    }

    private fun showSettings() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(22), dp(8), dp(22), 0) }
        box.addView(TextView(this).apply {
            text = "Chave de IA (Anthropic)"; textSize = 13f; setTextColor(purple); setPadding(0, dp(8), 0, 0)
        })
        val key = EditText(this).apply {
            hint = "sk-ant-…"
            setText(prefs.getString("api_key", ""))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine()
        }
        box.addView(key)
        box.addView(TextView(this).apply { text = "Modelo"; textSize = 13f; setTextColor(purple); setPadding(0, dp(12), 0, 0) })
        val model = EditText(this).apply { setText(ai.model()); setSingleLine() }
        box.addView(model)
        box.addView(TextView(this).apply {
            text = "A chave fica guardada somente neste celular. Sem chave, a Sara só executa comandos (abrir apps, ligar, horas, lanterna…)."
            textSize = 12f; setTextColor(Color.LTGRAY); setPadding(0, dp(12), 0, dp(4))
        })
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Configurações da Sara")
            .setView(box)
            .setPositiveButton("Salvar") { _, _ ->
                prefs.edit().putString("api_key", key.text.toString().trim()).putString("model", model.text.toString().trim()).apply()
                Toast.makeText(this, "Salvo 💜", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("Limpar conversa") { _, _ -> ai.clear(); saved.clear(); chat.removeAllViews(); addMessage("Conversa limpa. Do que você precisa, meu bem?", false) }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    override fun onDestroy() {
        try { (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(netCallback) } catch (e: Exception) {}
        recognizer?.destroy()
        recognizer = null
        tts?.stop(); tts?.shutdown()
        SaraState.mode = SaraMode.IDLE
        SaraState.level = 0f
        super.onDestroy()
    }
}
