package io.github.whateverayn.kkmap.core.rail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/**
 * 実データ (core-map の assets に同梱している graph.bin) で経路を確かめる.
 * 区間は JR の幹線・地方線・環状線や, 会社境界 (亀山, 塩尻) をまたぐものなどから選んだ.
 */
class RouteFinderTest {

    companion object {
        private lateinit var graph: RailGraph
        private lateinit var finder: RouteFinder
        private lateinit var search: StationSearch

        @BeforeClass
        @JvmStatic
        fun load() {
            graph = File("../core-map/src/main/assets/rail/graph.bin").inputStream().use { RailGraph.read(it) }
            finder = RouteFinder(graph)
            search = StationSearch(graph)
        }
    }

    /** 同名駅が複数あるときは, 指定した路線が乗り入れている駅を選ぶ */
    private fun station(name: String, line: String): StationGroup =
        search.search(name).first { it.name == name && line in it.lines }

    /** [expectedKm] は営業キロ */
    private fun assertBest(from: String, line: String, to: String, expectedKm: Double) {
        val candidates = finder.find(station(from, line), station(to, line))
        assertTrue("$from→$to: 候補なし", candidates.isNotEmpty())
        val best = candidates.first()
        assertEquals("$from→$to の路線", listOf(line), best.lines)
        // 距離は線路の形状から測るので営業キロとは一致しない. 桁違いの遠回りでないことだけ確かめる
        assertEquals("$from→$to の距離", expectedKm, best.lengthMeters / 1000, expectedKm * 0.25)
    }

    @Test fun osakaToTennoji() = assertBest("大阪", "大阪環状線", "天王寺", 10.7)
    @Test fun tennojiToNagoya() = assertBest("天王寺", "関西線", "名古屋", 174.7)
    @Test fun nagoyaToChigasaki() = assertBest("名古屋", "東海道線", "茅ヶ崎", 307.7)
    @Test fun chigasakiToHashimoto() = assertBest("茅ヶ崎", "相模線", "橋本", 33.3)
    @Test fun hashimotoToHigashiKanagawa() = assertBest("橋本", "横浜線", "東神奈川", 33.2)
    @Test fun higashiKanagawaToKawasaki() = assertBest("東神奈川", "東海道線", "川崎", 8.4)
    @Test fun kawasakiToFuchuhommachi() = assertBest("川崎", "南武線", "府中本町", 27.9)
    @Test fun fuchuhommachiToNishikokubunji() = assertBest("府中本町", "武蔵野線", "西国分寺", 3.6)
    @Test fun nishikokubunjiToShiojiri() = assertBest("西国分寺", "中央線", "塩尻", 181.4)
    @Test fun shiojiriToTajimi() = assertBest("塩尻", "中央線", "多治見", 137.6)
    @Test fun tajimiToMinoota() = assertBest("多治見", "太多線", "美濃太田", 17.8)
    @Test fun minootaToGifu() = assertBest("美濃太田", "高山線", "岐阜", 27.3)
    @Test fun gifuToShinOsaka() = assertBest("岐阜", "東海道線", "新大阪", 152.2)

    @Test
    fun osakaToShinOsakaHasTwoRoutes() {
        // 東海道線の本線と, うめきた (大阪駅地下ホーム) からの支線
        val candidates = finder.find(station("大阪", "東海道線"), station("新大阪", "東海道線"))
        assertTrue("候補が2つ以上: $candidates", candidates.size >= 2)
    }

    @Test
    fun loopLineHasBothDirections() {
        // 大阪環状線の京橋回りと西九条回り
        val candidates = finder.find(station("大阪", "大阪環状線"), station("天王寺", "大阪環状線"))
        val via = candidates.map { c -> c.stations.map { it.name } }
        assertTrue("京橋回り: $via", via.any { "京橋" in it })
        assertTrue("西九条回り: $via", via.any { "西九条" in it })
    }

    @Test
    fun candidatesNeverVisitSameStationTwice() {
        for (c in finder.find(station("川崎", "南武線"), station("府中本町", "南武線"))) {
            assertEquals(c.stations.size, c.stations.toSet().size)
        }
    }

    @Test
    fun akashiToAshiyaCrossesLineName() {
        // 山陽線から自然に東海道線へ続く (神戸で路線名が変わる)
        val candidates = finder.find(station("明石", "山陽線"), station("芦屋", "東海道線"))
        assertEquals(listOf("山陽線", "東海道線"), candidates.first().lines)
    }

    @Test
    fun ashiyaToOsakaIgnoresSandwichedStationOnParallelLine() {
        // 尼崎のホームが福知山線の駅として載っているが, 経路は東海道線だけ
        val candidates = finder.find(station("芦屋", "東海道線"), station("大阪", "東海道線"))
        assertEquals(listOf("東海道線"), candidates.first().lines)
    }

    @Test
    fun searchNormalizesSmallKe() {
        assertTrue(search.search("茅ケ崎").any { it.name == "茅ヶ崎" })
    }

    @Test
    fun searchRanksExactMatchFirst() {
        assertEquals("大阪", search.search("大阪").first().name)
    }
}
