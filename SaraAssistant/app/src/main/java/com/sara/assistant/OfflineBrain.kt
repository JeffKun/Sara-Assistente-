package com.sara.assistant

import java.text.Normalizer
import java.util.Calendar
import java.util.Locale

/** Conversa simples que funciona sem internet e sem IA. Devolve null quando não entende. */
object OfflineBrain {
    const val NO_INTERNET_REPLY =
        "Estou sem internet agora, então só consigo conversas simples e comandos do celular, como abrir apps, " +
        "lanterna, horas e ligações. Quando a conexão voltar, eu converso de verdade."

    private val jokes = listOf(
        "Por que o livro de matemática ficou triste? Porque tinha muitos problemas.",
        "O que o zero disse para o oito? Que cinto bonito!",
        "Por que o computador foi ao médico? Porque estava com um vírus.",
        "O que a impressora falou para a outra? Essa folha é sua ou é impressão minha?"
    )

    private fun norm(s: String): String =
        Normalizer.normalize(s.lowercase(Locale("pt", "BR")), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").trim()

    private fun has(p: String, pattern: String) = Regex(pattern).containsMatchIn(p)

    fun smallTalk(raw: String): String? {
        val p = norm(raw).trimEnd('.', '!', '?', ',')
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return when {
            has(p, "\\bbom dia\\b") -> "Bom dia, meu bem! Que seu dia seja leve. Do que você precisa?"
            has(p, "\\bboa tarde\\b") -> "Boa tarde, meu bem! Como posso ajudar?"
            has(p, "\\bboa noite\\b") -> "Boa noite, meu bem. Estou aqui se precisar de algo."
            has(p, "^(oi|ola|eae|e ai|opa|hey|oie)\\b") -> when {
                hour < 12 -> "Oi, meu bem! Bom dia. Como posso ajudar?"
                hour < 18 -> "Oi, meu bem! Boa tarde. Como posso ajudar?"
                else -> "Oi, meu bem! Boa noite. Como posso ajudar?"
            }
            has(p, "\\b(tudo bem|como voce esta|como vai voce|como voce vai|tudo certo)\\b") ->
                "Estou ótima, meu bem, ainda mais com você por aqui. E você, como está?"
            has(p, "\\b(qual e o seu nome|qual seu nome|quem e voce|quem voce e|como voce se chama)\\b") ->
                "Eu sou a Sara, sua assistente pessoal. Estou sempre por aqui com você."
            has(p, "\\bobrigad[oa]\\b|\\bvaleu\\b") -> "Imagina, meu bem. É um prazer ajudar."
            has(p, "\\b(te amo|gosto de voce|voce e demais)\\b") -> "Ai, que fofo. Eu também gosto muito de você."
            has(p, "\\bpiada\\b") -> jokes.random()
            has(p, "\\b(tchau|ate logo|ate mais|falou|flw)\\b") -> "Até logo, meu bem. Quando precisar, é só chamar."
            has(p, "\\b(estou|to|me sinto)\\s+(triste|cansad[oa]|mal|desanimad[oa]|ansios[oa])\\b") ->
                "Sinto muito, meu bem. Respira fundo comigo. Se quiser, me conta o que aconteceu, e se for algo pesado, vale conversar com alguém de confiança."
            has(p, "\\b(o que voce (sabe|pode|consegue) fazer|ajuda|comandos)\\b") ->
                "Sem internet eu abro aplicativos, ligo para contatos, digo as horas e a data, ligo a lanterna, mexo no volume e marco timers. " +
                "Com internet, eu também converso e pesquiso."
            else -> null
        }
    }
}
