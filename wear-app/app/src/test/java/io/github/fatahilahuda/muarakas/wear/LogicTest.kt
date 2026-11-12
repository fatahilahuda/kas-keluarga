package io.github.fatahilahuda.muarakas.wear

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LogicTest {
    private val seed = Seed(JSONObject(File("src/main/assets/seed.json").readText()))

    private fun item(kind: String, id: String, data: String) = Item(kind, id, 1, JSONObject(data))
    private fun tx(id: String, extra: String) = item("tx", id, """{"id":"$id",$extra}""")

    private fun sample(me: String = "m1"): Kas = Kas(
        seed,
        listOf(
            item("settings", "main", """{"budget":4000000,"cbud":{"jajan":300000},"cats":[
                {"t":"out","c":"hiburan","del":true,"to":"jajan","u":5},
                {"t":"out","c":"kopi","custom":true,"n":"Kopi Kantor","w":"americano latte","at":1,"u":6}]}"""),
            item("member", "m1", """{"id":"m1","n":"Huda","hideCats":["bonus"]}"""),
            item("member", "m2", """{"id":"m2","n":"Istri"}"""),
            item("pocket", "dompet", """{"id":"dompet","n":"Dompet","k":0,"start":100000}"""),
            item("pocket", "bank", """{"id":"bank","n":"Bank","k":1,"start":1000000}"""),
            item("pocket", "haji", """{"id":"haji","n":"Haji","k":4,"start":0,"save":true}"""),
            item("biz", "toko", """{"id":"toko","n":"Toko","k":2,"pocket":"bank"}"""),
            item("biz", "tHaji", """{"id":"tHaji","n":"Target Haji","kind":"tabungan","pocket":"haji","target":1000000}"""),
            tx("a", """"type":"in","amount":5000000,"cat":"gaji","pocket":"bank","date":"2026-11-01","ts":1,"by":"m1""""),
            tx("b", """"type":"out","amount":25000,"cat":"makan","pocket":"dompet","date":"2026-11-02","ts":2,"by":"m2""""),
            tx("c", """"type":"out","amount":50000,"cat":"hiburan","pocket":"dompet","date":"2026-11-03","ts":3,"by":"m1""""),
            tx("d", """"type":"tf","amount":250000,"cat":"tf","pocket":"bank","to":"haji","date":"2026-11-03","ts":4,"by":"m1""""),
            tx("e", """"type":"in","amount":700000,"cat":"jual","pocket":"bank","biz":"toko","date":"2026-11-04","ts":5,"by":"m1""""),
            tx("f", """"type":"in","amount":900000,"cat":"bonus","pocket":"bank","hidden":true,"date":"2026-11-05","ts":6,"by":"m2""""),
            tx("g", """"type":"out","amount":0,"sealed":true,"cat":"lain","pocket":"bank","date":"2026-11-05","ts":7,"by":"m2""""),
            tx("h", """"type":"out","amount":99000,"cat":"makan","pocket":"dompet","date":"2026-11-06","ts":8,"by":"m1""""),
            item("del", "h", """{"ts":9}"""),
            tx("i", """"type":"out","amount":40000,"cat":"makan","pocket":"dompet","date":"2026-10-30","ts":10,"by":"m1""""),
        ),
        me, 0,
    )

    @Test fun amounts() {
        assertEquals(15000L, Amount.parse("bakso 15rb")?.value)
        assertEquals(6_200_000L, Amount.parse("gaji 6,2jt")?.value)
        assertEquals(30000L, Amount.parse("bensin 30")?.value)
        assertEquals(25000L, Amount.parse("kopi 25.000")?.value)
        assertEquals(1_500_000L, Amount.parse("1,5 juta cicilan")?.value)
    }

    @Test fun speechNumbers() {
        fun v(s: String) = Amount.parse(Amount.normalizeSpeech(s))?.value
        assertEquals(15000L, v("bakso lima belas ribu"))
        assertEquals(150000L, v("seratus lima puluh ribu belanja"))
        assertEquals(2_500_000L, v("dua juta lima ratus ribu"))
        assertEquals(25000L, v("dua puluh lima ribu bensin"))
        assertEquals(1000L, v("parkir seribu"))
        assertEquals(15000L, v("Rp15.000 nasi padang"))
        assertTrue(Amount.normalizeSpeech("sirup 20 ribu").startsWith("sirup"))
    }

    @Test fun categoriesFollowFamilySettings() {
        val k = sample()
        val out = k.cats["out"]!!.map { it.id }
        assertFalse("deleted category hidden", "hiburan" in out)
        assertTrue("custom category added", "kopi" in out)
        assertEquals("lain", out.last())
        // catatan in a deleted category move to its replacement
        assertEquals("jajan", k.visible.first { it.id == "c" }.cat)
    }

    @Test fun guessesCategories() {
        val k = sample()
        val ai = Ai(k, JSONObject())
        assertEquals("makan", ai.rank("out", "bakso")[0].first)
        assertEquals("transport", ai.rank("out", "bensin pertalite")[0].first)
        assertEquals("kopi", ai.rank("out", "americano")[0].first)
        assertEquals("gaji", ai.rank("in", "gajian")[0].first)
        assertTrue(ai.rank("out", "").isEmpty())
    }

    @Test fun monthTotalsMatchPhone() {
        val t = sample().totals("2026-11")
        assertEquals(5_000_000L, t.hin)               // business income and the partner's secret bonus stay out
        assertEquals(75_000L, t.hout)                 // deleted catatan h gone, October excluded
        assertEquals(50_000L, t.cats["jajan"])
    }

    @Test fun maskingAndBalances() {
        val mine = sample("m1")
        assertFalse(mine.visible.any { it.id == "f" || it.id == "g" })
        val b = mine.balances()
        assertEquals(100_000L - 25_000 - 50_000 - 40_000, b["dompet"]) // saldo mencakup semua bulan
        assertEquals(1_000_000L + 5_000_000 - 250_000 + 700_000, b["bank"])
        assertEquals(250_000L, b["haji"])
        assertEquals(25.0, mine.targetFor("haji")!!.pct, 0.01)
        // the owner of the secret bonus sees it
        assertTrue(sample("m2").visible.any { it.id == "f" })
        assertEquals(setOf("bonus"), mine.hideCats)
    }

    @Test fun usahaAndDefaults() {
        val k = sample()
        val u = k.usaha("2026-11").single()
        assertEquals(700_000L, u.omzet)
        assertEquals("bin", k.ctype(k.visible.first { it.id == "e" }))
        assertEquals("dompet", k.defaultPocket("out", "makan", null))
        assertEquals("bank", k.defaultPocket("out", "makan", "bank"))
    }

    @Test fun shortMoney() {
        assertEquals("Rp 1,3 jt", Fmt.short(1_250_000))
        assertEquals("Rp 12 jt", Fmt.short(12_400_000))
        assertEquals("Rp 350 rb", Fmt.short(350_000))
        assertEquals("−Rp 2 jt", Fmt.short(-2_000_000))
        assertEquals("15 rb", Fmt.short(15_000, rp = false))
    }
}
