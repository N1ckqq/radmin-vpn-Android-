package com.radminvpn.android.signaling

import android.util.Base64

/**
 * Ручной signaling (Вариант 3) — без Firebase.
 *
 * Пользователи обмениваются ключами вручную (через мессенджер, QR-код и т.д.)
 * Формат: Base64-закодированный SDP
 *
 * Процесс:
 * 1. Хост создаёт offer → кодирует в Base64 → показывает пользователю
 * 2. Пользователь копирует этот текст и отправляет другу
 * 3. Друг вставляет offer → генерирует answer → кодирует в Base64
 * 4. Друг отправляет answer обратно хосту
 * 5. Хост вставляет answer → соединение установлено
 */
object ManualSignaling {

    /**
     * Кодировать SDP в компактный формат для передачи
     */
    fun encodeSdp(sdp: String): String {
        // Сжимаем SDP убирая ненужное и кодируем в Base64
        val compressed = sdp
            .lines()
            .filter { line ->
                // Оставляем только важные строки SDP
                line.startsWith("v=") ||
                line.startsWith("o=") ||
                line.startsWith("s=") ||
                line.startsWith("t=") ||
                line.startsWith("a=group") ||
                line.startsWith("a=msid") ||
                line.startsWith("m=") ||
                line.startsWith("c=") ||
                line.startsWith("a=ice-ufrag") ||
                line.startsWith("a=ice-pwd") ||
                line.startsWith("a=fingerprint") ||
                line.startsWith("a=setup") ||
                line.startsWith("a=mid") ||
                line.startsWith("a=sctp") ||
                line.startsWith("a=max-message-size") ||
                line.startsWith("a=candidate")
            }
            .joinToString("\n")

        return Base64.encodeToString(compressed.toByteArray(), Base64.NO_WRAP)
    }

    /**
     * Декодировать SDP из компактного формата
     */
    fun decodeSdp(encoded: String): String {
        val decoded = Base64.decode(encoded, Base64.NO_WRAP)
        return String(decoded)
    }

    /**
     * Кодировать ICE candidates для передачи
     */
    fun encodeIceCandidates(candidates: List<String>): String {
        val joined = candidates.joinToString("|||")
        return Base64.encodeToString(joined.toByteArray(), Base64.NO_WRAP)
    }

    /**
     * Декодировать ICE candidates
     */
    fun decodeIceCandidates(encoded: String): List<String> {
        val decoded = Base64.decode(encoded, Base64.NO_WRAP)
        return String(decoded).split("|||")
    }

    /**
     * Создать полный "ключ подключения" (SDP + ICE candidates в одном)
     */
    fun createConnectionKey(sdp: String, iceCandidates: List<String>): String {
        val data = "SDP:$sdp\nICE:${iceCandidates.joinToString("|||")}"
        return Base64.encodeToString(data.toByteArray(), Base64.NO_WRAP)
    }

    /**
     * Распарсить "ключ подключения"
     */
    fun parseConnectionKey(key: String): Pair<String, List<String>>? {
        return try {
            val decoded = String(Base64.decode(key, Base64.NO_WRAP))
            val sdpPart = decoded.substringAfter("SDP:").substringBefore("\nICE:")
            val icePart = decoded.substringAfter("ICE:")
            val candidates = if (icePart.isNotEmpty()) icePart.split("|||") else emptyList()
            Pair(sdpPart, candidates)
        } catch (e: Exception) {
            null
        }
    }
}
