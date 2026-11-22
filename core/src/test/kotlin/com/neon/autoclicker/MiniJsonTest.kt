package com.neon.autoclicker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MiniJsonTest {

    @Test
    fun targetsRoundTrip() {
        val list = listOf(Pt(0.5f, 0.25f), Pt(1f, 0f))
        assertEquals(list, Targets.fromJson(Targets.toJson(list)))
        assertEquals("[[0.5,0.25],[1,0]]", Targets.toJson(list))
    }

    @Test
    fun readsAndroidSavedTargets() {
        assertEquals(listOf(Pt(0.4321f, 0.9f)), Targets.fromJson("[[0.4321,0.9]]"))
        assertNull(Targets.fromJson("[]"))
        assertNull(Targets.fromJson("not json"))
    }

    @Test
    fun profilesRoundTripWithUnicodeNames() {
        val p = Profile("Farm \"run\" 도장 印章", 8, 300, 40, Config.MODE_ALL, listOf(Pt(0.1f, 0.2f), Pt(0.3f, 0.4f)))
        val back = Profile.listFromJson(Profile.listToJson(listOf(p)))
        assertEquals(listOf(p), back)
    }

    @Test
    fun readsOldProfileFormats() {
        // version with a timer list index (3 = 10 minutes) and org.json escaping
        val p = Profile.listFromJson("""[{"name":"Old\/one","q":4,"timer":3,"size":32,"mode":0,"targets":[[0.5,0.5]]}]""").single()
        assertEquals("Old/one", p.name)
        assertEquals(600, p.timerSec)
        // "No timer" in old versions becomes the 1-minute default length
        assertEquals(60, Profile.listFromJson("""[{"name":"x","timer":0}]""").single().timerSec)
    }

    @Test
    fun badDataGivesEmptyList() {
        assertEquals(0, Profile.listFromJson("{oops").size)
        assertEquals(0, Profile.listFromJson(null).size)
    }
}
