package com.laoke.weshirt.ui

import com.laoke.weshirt.ui.assistant.speechWav
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AssistantVoiceInputTest {
    @Test fun wavHeaderAndLengthMatchMono16BitSpeechEndpoint() {
        val pcm = byteArrayOf(0, 64, 127, -7, 31, 15)
        val wav = speechWav(pcm, 4)
        val header = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(48, wav.size)
        assertEquals("RIFF", wav.copyOfRange(0, 4).toString(Charsets.US_ASCII))
        assertEquals(40, header.getInt(4))
        assertEquals("WAVEfmt ", wav.copyOfRange(8, 16).toString(Charsets.US_ASCII))
        assertEquals(1, header.getShort(20).toInt())
        assertEquals(1, header.getShort(22).toInt())
        assertEquals(16000, header.getInt(24))
        assertEquals(16, header.getShort(34).toInt())
        assertEquals(4, header.getInt(40))
        assertArrayEquals(pcm.copyOf(4), wav.copyOfRange(44, 48))
        assertThrows(IllegalArgumentException::class.java) { speechWav(pcm, 5) }
    }
}
