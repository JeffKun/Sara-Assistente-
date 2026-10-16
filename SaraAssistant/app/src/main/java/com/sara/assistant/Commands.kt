package com.sara.assistant

import android.Manifest
import android.app.Activity
import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.Settings
import java.net.URLEncoder
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Comandos que a Sara resolve sozinha, sem IA. [handle] devolve o que ela deve responder,
 * ou null quando a frase não é um comando (aí vai para a IA).
 */
class Commands(private val ctx: Activity) {
    companion object { const val REQ_CONTACTS = 42 }

    private val ptBR = Locale("pt", "BR")
    private var torchOn = false
    private var pendingCall: String? = null

    private fun norm(s: String): String =
        Normalizer.normalize(s.lowercase(ptBR), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").trim()

    fun handle(raw: String): String? {
        val low = raw.lowercase(ptBR).trim().trimEnd('.', '!', '?', ',').trim()
        val p = norm(low)

        if (Regex("\\b(desligar|desativar|esconder|fechar)\\b.*\\b(sara|bolha)\\b").containsMatchIn(p)) {
            ctx.stopService(Intent(ctx, FloatingSaraService::class.java))
            return "Tudo bem, meu bem. Desativei o botão flutuante."
        }
        if (Regex("\\bque horas\\b|\\bhoras sao\\b|\\bque hora e\\b|\\bhora atual\\b").containsMatchIn(p)) {
            return "Agora são " + SimpleDateFormat("HH:mm", ptBR).format(Date()) + "."
        }
        if (Regex("\\bque dia (e )?hoje\\b|\\bdata de hoje\\b|\\bqual (e )?a data\\b|\\bdia da semana\\b").containsMatchIn(p)) {
            return "Hoje é " + SimpleDateFormat("EEEE, d 'de' MMMM 'de' yyyy", ptBR).format(Date()) + "."
        }
        if ("bateria" in p) {
            val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            return "Sua bateria está em ${bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)} por cento."
        }
        if ("lanterna" in p) {
            val want = when {
                Regex("\\b(desligar|apagar|desativar|fechar)\\b").containsMatchIn(p) -> false
                Regex("\\b(ligar|liga|acender|acende|ativar|ativa)\\b").containsMatchIn(p) -> true
                else -> null
            }
            return torch(want)
        }
        if (Regex("\\bwi-?fi\\b|\\bwifi\\b").containsMatchIn(p) || "internet" in p && Regex("\\b(ligar|desligar|configurar)\\b").containsMatchIn(p)) {
            openWifiPanel()
            return "O Android não deixa aplicativos ligarem o Wi-Fi sozinhos. Abri o painel de internet para você."
        }
        if ("bluetooth" in p) {
            return safeStart(Intent(Settings.ACTION_BLUETOOTH_SETTINGS), "Abrindo o Bluetooth. Por segurança, o Android pede que você ligue por lá.")
        }
        if ("volume" in p) {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            return when {
                Regex("\\b(aumentar|aumenta|subir|sobe)\\b|\\b(mais alto|maior)\\b").containsMatchIn(p) -> {
                    am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                    "Aumentei o volume."
                }
                Regex("\\b(diminuir|diminui|abaixar|abaixa|baixar|baixa|reduzir)\\b|\\b(mais baixo|menor)\\b").containsMatchIn(p) -> {
                    am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                    "Abaixei o volume."
                }
                else -> null
            }
        }
        if (Regex("\\b(timer|temporizador|cronometro)\\b").containsMatchIn(p)) {
            val m = Regex("(\\d+)\\s*(segundo|minuto|hora)").find(p)
            if (m != null) {
                val n = m.groupValues[1].toInt()
                val seconds = n * when (m.groupValues[2]) { "segundo" -> 1; "minuto" -> 60; else -> 3600 }
                val i = Intent(AlarmClock.ACTION_SET_TIMER)
                    .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                return safeStart(i, "Pronto, timer de ${m.groupValues[1]} ${m.groupValues[2]}${if (n == 1) "" else "s"} marcado.")
            }
        }
        val call = Regex("^(?:ligar|ligue|liga|telefonar|telefone)\\s+(?:para|pra|pro)?\\s*(.+)$").find(low)
        if (call != null) return callContact(call.groupValues[1].trim())

        val yt = Regex("^(?:tocar|toca|colocar|coloca|pesquisar|pesquisa|buscar|busca|procurar|procura)\\s+(.+?)\\s+(?:no|do|pelo)\\s+youtube$").find(low)
        if (yt != null) {
            if (!Net.isOnline(ctx)) return "Estou sem internet agora, então não consigo procurar no YouTube."
            val q = yt.groupValues[1]
            val uri = Uri.parse("https://www.youtube.com/results?search_query=" + URLEncoder.encode(q, "UTF-8"))
            return safeStart(Intent(Intent.ACTION_VIEW, uri), "Procurando $q no YouTube.")
        }
        val web = Regex("^(?:pesquisar|pesquisa|buscar|busca|procurar|procura|googlar)\\s+(?:por\\s+|sobre\\s+)?(.+)$").find(low)
        if (web != null) {
            if (!Net.isOnline(ctx)) return "Estou sem internet agora, então não consigo pesquisar."
            val q = web.groupValues[1]
            return safeStart(Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, q), "Pesquisando $q.")
        }
        if (Regex("\\b(configuracoes|ajustes)\\b").containsMatchIn(p)) {
            return safeStart(Intent(Settings.ACTION_SETTINGS), "Abrindo as configurações.")
        }
        val open = Regex("^(?:abr(?:ir|e|a)|inici(?:ar|a)|execut(?:ar|a)|lan[cç](?:ar|a))\\s+(?:o\\s+|a\\s+|os\\s+|as\\s+)?(?:app\\s+|aplicativo\\s+)?(.+)$").find(low)
        if (open != null) return openApp(open.groupValues[1].trim())
        return null
    }

    // ---------- ações ----------

    private fun safeStart(i: Intent, okReply: String): String =
        try { ctx.startActivity(i); okReply } catch (e: Exception) { "Não encontrei um aplicativo para fazer isso neste celular." }

    private fun openWifiPanel() {
        val i = if (Build.VERSION.SDK_INT >= 29) Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY) else Intent(Settings.ACTION_WIFI_SETTINGS)
        try { ctx.startActivity(i) } catch (e: Exception) { ctx.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) }
    }

    private fun torch(want: Boolean?): String {
        return try {
            val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val id = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
                ?: return "Este celular não tem lanterna."
            val target = want ?: !torchOn
            cm.setTorchMode(id, target)
            torchOn = target
            if (target) "Lanterna ligada." else "Lanterna desligada."
        } catch (e: Exception) {
            "Não consegui acessar a lanterna agora."
        }
    }

    private fun openApp(label: String): String {
        val q0 = norm(label)
        val q = if (q0 == "navegador" || q0 == "internet") "chrome" else q0
        val pm = ctx.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        var bestScore = 0
        var bestName = ""
        var bestPkg: String? = null
        for (ri in pm.queryIntentActivities(launcher, 0)) {
            val shown = ri.loadLabel(pm).toString()
            val name = norm(shown)
            val score = when {
                name == q -> 3
                name.startsWith(q) -> 2
                name.contains(q) || (name.length >= 3 && q.contains(name)) -> 1
                else -> 0
            }
            if (score > bestScore || (score == bestScore && score > 0 && name.length < bestName.length)) {
                bestScore = score
                bestName = name
                bestPkg = ri.activityInfo.packageName
            }
        }
        val pkg = bestPkg ?: return "Não encontrei o aplicativo $label neste celular."
        val intent = pm.getLaunchIntentForPackage(pkg) ?: return "Não consegui abrir o $label."
        return try { ctx.startActivity(intent); "Claro, meu bem. Abrindo o $label." } catch (e: Exception) { "Não consegui abrir o $label." }
    }

    private fun callContact(name: String): String {
        val digits = name.filter { it.isDigit() }
        if (digits.length >= 3 && name.all { it.isDigit() || it == ' ' || it == '-' || it == '+' || it == '(' || it == ')' }) {
            return safeStart(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$digits")), "Abrindo o telefone com o número $digits.")
        }
        if (ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            pendingCall = name
            ctx.requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS), REQ_CONTACTS)
            return "Preciso da sua permissão para ler os contatos. Depois de permitir, eu ligo para $name."
        }
        return dial(name)
    }

    fun onContactsResult(granted: Boolean): String? {
        val name = pendingCall ?: return null
        pendingCall = null
        return if (granted) dial(name) else "Sem a permissão de contatos não consigo procurar $name."
    }

    private fun dial(name: String): String {
        var number: String? = null
        var found: String? = null
        try {
            val cur = ctx.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
                arrayOf("%$name%"),
                null
            )
            cur?.use { if (it.moveToFirst()) { found = it.getString(0); number = it.getString(1) } }
        } catch (e: Exception) {
            return "Não consegui ler os contatos agora."
        }
        val n = number ?: return "Não encontrei $name nos seus contatos."
        return safeStart(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(n))), "Abrindo o telefone para ligar para ${found ?: name}.")
    }
}
