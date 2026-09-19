package io.github.whateverayn.kkmap.core.rail

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** graph.bin の線路のつながり方 (tools/rail-data/build_graph.py の前処理) を確かめる */
class GraphTopologyTest {
    private val graph = File("../core-map/src/main/assets/rail/graph.bin").inputStream().use { RailGraph.read(it) }
    private val search = StationSearch(graph)
    private val finder = RouteFinder(graph)

    private fun station(name: String, line: String): StationGroup =
        search.search(name).first { it.name == name && line in it.lines }

    @Test
    fun crossingLinesAreNotConnected() {
        // JR東西線と地下鉄谷町線は北新地/東梅田付近で交差するだけ. N02 では交差点で端点を共有している
        val routes = finder.find(station("北新地", "JR東西線"), station("中崎町", "2号線(谷町線)"))
        assertTrue("線路でつながっていないはず: ${routes.map { it.lines }}", routes.isEmpty())
    }

    @Test
    fun operatorBoundaryIsConnected() {
        // 関西線は亀山で JR西日本から JR東海に変わるが, 線路はつながっている
        assertTrue(finder.find(station("加茂", "関西線"), station("四日市", "関西線")).isNotEmpty())
    }
}
