package io.github.whateverayn.kkmap.rail

import android.content.Context
import io.github.whateverayn.kkmap.core.rail.RailGraph
import io.github.whateverayn.kkmap.core.rail.RouteFinder
import io.github.whateverayn.kkmap.core.rail.StationGroup
import io.github.whateverayn.kkmap.core.rail.StationSearch
import io.github.whateverayn.kkmap.core.rail.TransferFinder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** 線路グラフと, それを使う検索・経路計算をまとめたもの */
class RailData(val graph: RailGraph) {
    val search = StationSearch(graph)
    val finder = RouteFinder(graph)
    val transfers = TransferFinder(graph)
    private val byCode = graph.groups.associateBy { it.code }

    fun groupByCode(code: Int): StationGroup? = byCode[code]
}

/** 線路グラフ (assets/rail/graph.bin, 約2MB) はプロセスで1回だけ読む */
object RailRepository {
    private val mutex = Mutex()
    private var data: RailData? = null

    suspend fun load(context: Context): RailData = mutex.withLock {
        data ?: withContext(Dispatchers.IO) {
            context.assets.open("rail/graph.bin").use { RailData(RailGraph.read(it)) }
        }.also { data = it }
    }
}
