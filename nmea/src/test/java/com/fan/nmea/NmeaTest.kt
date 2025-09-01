package com.fan.nmea

import com.fan.nmea.model.NmeaMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NmeaTest {
    val nmeaParserManager = NmeaParserManager()
    val nmeaLineAggregator = NmeaLineAggregator()
    val gga = "\$GNGGA,010822.00,3157.01094528,N,11853.42141470,E,1,21,1.0,21.2697,M,2.6087,M,,*77"

    @Test
    fun test() {
        val sentence =
            "\$GNGGA,010822.00,3157.01094528,N,11853.42141470,E,1,21,1.0,21.2697,M,2.6087,M,,*77"
        val message = nmeaParserManager.parse(sentence)

        assertTrue(message is NmeaMessage.GGA)

        val msg = message as NmeaMessage.GGA

        assertEquals(msg.raw, sentence)
        assertEquals(msg.fixQuality, 1)
    }
}