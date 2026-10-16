package com.sara.assistant

enum class SaraMode { IDLE, LISTENING, THINKING, SPEAKING }

/** Estado compartilhado entre o app e a bolha flutuante (mesmo processo). */
object SaraState {
    @Volatile var mode: SaraMode = SaraMode.IDLE
    /** Nível de voz do microfone, de 0 a 1 (usado na animação de "ouvindo"). */
    @Volatile var level: Float = 0f
    @Volatile var bubbleOn: Boolean = false
}
