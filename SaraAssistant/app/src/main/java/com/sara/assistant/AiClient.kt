package com.sara.assistant

import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Conversa livre com a API de mensagens da Anthropic.
 * A chave é digitada pelo próprio usuário e guardada só neste aparelho (SharedPreferences privado).
 * Para distribuir o app a outras pessoas, o ideal é trocar isto por um backend seu que guarde a chave.
 */
class AiClient(private val prefs: SharedPreferences) {
    companion object {
        const val DEFAULT_MODEL = "claude-haiku-5-5"
        private const val ENDPOINT = "https://api.anthropic.com/v1/messages"
        private const val SYSTEM =
            "Você é a Sara, uma assistente pessoal carinhosa e simpática que conversa em português do Brasil. " +
            "Suas respostas são lidas em voz alta: use no máximo 3 frases curtas, tom natural de conversa, " +
            "sem markdown, sem listas e sem emojis. De vez em quando chame a pessoa de \"meu bem\", sem exagero. " +
            "Se não souber algo, diga com honestidade."
    }

    private val main = Handler(Looper.getMainLooper())
    private val history = ArrayList<Pair<String, String>>()

    fun apiKey(): String = prefs.getString("api_key", "").orEmpty().trim()
    fun model(): String = prefs.getString("model", DEFAULT_MODEL).orEmpty().trim().ifEmpty { DEFAULT_MODEL }
    fun hasKey(): Boolean = apiKey().isNotEmpty()
    fun clear() = history.clear()

    /**
     * Chame na thread principal. [done] também é chamado na thread principal e recebe
     * a resposta e um indicador de falha de rede (true quando faltou conexão).
     */
    fun ask(text: String, done: (String, Boolean) -> Unit) {
        history.add("user" to text)
        while (history.size > 12) history.removeAt(0)
        while (history.isNotEmpty() && history[0].first != "user") history.removeAt(0)
        val snapshot = ArrayList(history)
        val key = apiKey()
        val model = model()

        Thread {
            var ok = false
            var networkFailure = false
            var answer = ""
            try {
                val messages = JSONArray()
                for (m in snapshot) messages.put(JSONObject().put("role", m.first).put("content", m.second))
                val body = JSONObject()
                    .put("model", model)
                    .put("max_tokens", 500)
                    .put("system", SYSTEM)
                    .put("messages", messages)

                val conn = URL(ENDPOINT).openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 15000
                conn.readTimeout = 60000
                conn.doOutput = true
                conn.setRequestProperty("content-type", "application/json")
                conn.setRequestProperty("x-api-key", key)
                conn.setRequestProperty("anthropic-version", "2023-06-01")
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val raw = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                conn.disconnect()

                if (code in 200..299) {
                    val content = JSONObject(raw).getJSONArray("content")
                    val sb = StringBuilder()
                    for (i in 0 until content.length()) {
                        val block = content.getJSONObject(i)
                        if (block.optString("type") == "text") sb.append(block.optString("text"))
                    }
                    answer = sb.toString().trim()
                    ok = answer.isNotEmpty()
                    if (!ok) answer = "Não consegui pensar numa resposta agora. Pode repetir?"
                } else {
                    answer = when (code) {
                        401, 403 -> "Sua chave de IA não foi aceita. Confira em Configurações."
                        404 -> "Não encontrei o modelo $model. Confira o nome em Configurações."
                        429 -> "Muitas mensagens em pouco tempo. Tente de novo em instantes."
                        else -> "Tive um problema ao falar com a IA (erro $code)."
                    }
                }
            } catch (e: IOException) {
                networkFailure = true
                answer = OfflineBrain.NO_INTERNET_REPLY
            } catch (e: Exception) {
                answer = "Algo deu errado ao falar com a IA."
            }
            val finalOk = ok
            val finalAnswer = answer
            val finalNet = networkFailure
            main.post {
                if (finalOk) history.add("assistant" to finalAnswer)
                else if (history.isNotEmpty()) history.removeAt(history.size - 1)
                done(finalAnswer, finalNet)
            }
        }.start()
    }
}
