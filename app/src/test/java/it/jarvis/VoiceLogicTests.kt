package it.jarvis

import it.jarvis.assistant.LocalIntent
import it.jarvis.assistant.LocalIntentParser
import it.jarvis.assistant.SentenceChunker
import it.jarvis.assistant.WakeWord
import it.jarvis.assistant.YesNo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceLogicTests {
    @Test fun timer() {
        assertEquals(LocalIntent.Timer(300), LocalIntentParser.parse("imposta un timer di 5 minuti"))
        assertEquals(LocalIntent.Timer(7200), LocalIntentParser.parse("timer 2 ore"))        assertEquals(LocalIntent.Timer(30), LocalIntentParser.parse("timer di 30 secondi"))
    }

    @Test fun alarm() {
        assertEquals(LocalIntent.Alarm(7, 30), LocalIntentParser.parse("imposta sveglia alle 7:30"))
        assertEquals(LocalIntent.Alarm(7, 30), LocalIntentParser.parse("sveglia alle 7 e 30"))
        assertEquals(LocalIntent.Alarm(7, 30), LocalIntentParser.parse("sveglia alle 7 e mezza"))
        assertEquals(LocalIntent.Alarm(8, 0), LocalIntentParser.parse("sveglia alle 8"))
        assertNull(LocalIntentParser.parse("sveglia alle 27:90"))
    }

    @Test fun basics() {
        assertEquals(LocalIntent.Time, LocalIntentParser.parse("che ora è"))
        assertEquals(LocalIntent.Today, LocalIntentParser.parse("che giorno è oggi"))
        assertEquals(LocalIntent.Battery, LocalIntentParser.parse("quanta batteria ho"))
        assertEquals(LocalIntent.Stop, LocalIntentParser.parse("Basta!"))
        assertEquals(LocalIntent.OpenApp("youtube"), LocalIntentParser.parse("apri YouTube"))
    }

    @Test fun longQuestionsGoToTheModel() {
        assertNull(LocalIntentParser.parse("spiegami come funziona la batteria di un'auto elettrica in inverno"))
        assertNull(LocalIntentParser.parse("che ora è a Tokyo adesso quando a Roma sono le tre del mattino"))
    }

    @Test fun wakeWord() {
        assertEquals("che tempo fa", WakeWord.strip("Ehi Jarvis, che tempo fa"))
        assertEquals("", WakeWord.strip("jarvis"))
        assertNull(WakeWord.strip("sto parlando con un amico"))
        assertNull(WakeWord.strip("il jarvis di ieri"))
    }

    @Test fun yesNo() {
        assertEquals(true, YesNo.parse("Sì, certo"))
        assertEquals(false, YesNo.parse("no grazie"))
        assertNull(YesNo.parse("boh forse"))
    }

    @Test fun chunker() {
        val out = ArrayList<String>()
        val c = SentenceChunker { out += it }
        "La capitale d'Italia è Roma. Ha circa 2,8 milioni di abitanti".chunked(5).forEach(c::feed)
        c.flush()
        assertEquals(listOf("La capitale d'Italia è Roma.", "Ha circa 2,8 milioni di abitanti"), out)
    }
}
