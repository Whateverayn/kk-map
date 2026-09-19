package io.github.whateverayn.kkmap.core.rail

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TransferFinderTest {
    private val graph = File("../core-map/src/main/assets/rail/graph.bin").inputStream().use { RailGraph.read(it) }
    private val search = StationSearch(graph)
    private val finder = TransferFinder(graph)
    private val routes = RouteFinder(graph)

    private fun station(name: String, line: String): StationGroup =
        search.search(name).first { it.name == name && line in it.lines }

    @Test
    fun suggestsTransferBetweenUnconnectedNetworks() {
        // JR 東海道線の芦屋から阪急京都線の南方へは線路でつながっていない
        val from = station("芦屋", "東海道線")
        val to = station("南方", "京都線")
        assertTrue(routes.find(from, to).isEmpty())

        val suggestions = finder.suggest(from, to)
        assertTrue("候補あり", suggestions.isNotEmpty())
        // 乗換先は必ず降車駅へ線路でつながっている
        for (s in suggestions.take(3)) {
            assertTrue("$s", s.next.index == to.index || routes.find(s.next, to).isNotEmpty())
        }
    }
}
