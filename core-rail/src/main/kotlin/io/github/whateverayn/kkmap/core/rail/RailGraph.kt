package io.github.whateverayn.kkmap.core.rail

import java.io.DataInputStream
import java.io.InputStream

/** 経度・緯度 (度) */
data class LonLat(val lon: Double, val lat: Double)

/** 駅 (N02 のグループコード単位. 乗換駅は1つになる) */
data class StationGroup(
    val index: Int,
    val code: Int,
    val name: String,
    val position: LonLat,
    /** 事業者名 (JR は "JR西日本" などの短縮形) */
    val operators: List<String>,
    val lines: List<String>,
)

/**
 * 線路グラフ. tools/rail-data/build_graph.py が出力する graph.bin を読む.
 *
 * 節点は駅 (グループ + 事業者 + 路線ごとのホームをまとめたもの) と分岐点.
 * 辺は節点の間の線路で, 距離・路線名別の距離・形状を持つ.
 */
class RailGraph private constructor(
    val groups: List<StationGroup>,
    private val strings: Array<String>,
    // 節点
    val nodeCount: Int,
    internal val nodeGroup: IntArray,
    private val nodeLine: IntArray,
    // 辺
    internal val edgeFrom: IntArray,
    internal val edgeTo: IntArray,
    internal val edgeLength: FloatArray,
    private val edgeLineStart: IntArray,
    private val edgeLineName: IntArray,
    private val edgeLineLength: FloatArray,
    private val edgePointStart: IntArray,
    private val points: IntArray,
) {
    val edgeCount: Int get() = edgeFrom.size

    // 隣接リスト (CSR 形式). adjacency[adjacencyStart[n] until adjacencyStart[n + 1]] が節点 n に接する辺
    internal val adjacencyStart: IntArray
    internal val adjacency: IntArray

    /** グループごとの節点 */
    internal val groupNodes: Array<IntArray>

    init {
        val degree = IntArray(nodeCount + 1)
        for (e in 0 until edgeCount) {
            degree[edgeFrom[e]]++
            degree[edgeTo[e]]++
        }
        adjacencyStart = IntArray(nodeCount + 1)
        for (n in 0 until nodeCount) adjacencyStart[n + 1] = adjacencyStart[n] + degree[n]
        adjacency = IntArray(adjacencyStart[nodeCount])
        val cursor = adjacencyStart.copyOf()
        for (e in 0 until edgeCount) {
            adjacency[cursor[edgeFrom[e]]++] = e
            adjacency[cursor[edgeTo[e]]++] = e
        }
        val byGroup = Array(groups.size) { mutableListOf<Int>() }
        for (n in 0 until nodeCount) if (nodeGroup[n] >= 0) byGroup[nodeGroup[n]].add(n)
        groupNodes = Array(groups.size) { byGroup[it].toIntArray() }
    }

    /** 辺 e を節点 from の側から見た反対側の節点 */
    internal fun otherEnd(e: Int, from: Int): Int = if (edgeFrom[e] == from) edgeTo[e] else edgeFrom[e]

    /** 駅節点の路線名 (駅でなければ null) */
    internal fun nodeLineName(n: Int): String? = nodeLine[n].takeIf { it >= 0 }?.let { strings[it] }

    /** 辺 e を通る路線名と, その距離 (m) */
    internal fun edgeLines(e: Int): List<Pair<String, Float>> =
        (edgeLineStart[e] until edgeLineStart[e + 1]).map { strings[edgeLineName[it]] to edgeLineLength[it] }

    /** 辺 e の形状. forward が false なら to → from の向き */
    internal fun edgePoints(e: Int, forward: Boolean): List<LonLat> {
        val range = edgePointStart[e] until edgePointStart[e + 1]
        val list = range.map { LonLat(points[it * 2] / COORD_SCALE, points[it * 2 + 1] / COORD_SCALE) }
        return if (forward) list else list.asReversed()
    }

    companion object {
        private const val MAGIC = "KKRG"
        private const val SCHEMA_VERSION = 1
        private const val NO_STRING = -1 // 0xFFFFFFFF
        internal const val COORD_SCALE = 1_000_000.0

        fun read(input: InputStream): RailGraph {
            val din = DataInputStream(input.buffered())
            val magic = ByteArray(4).also { din.readFully(it) }.toString(Charsets.US_ASCII)
            require(magic == MAGIC) { "graph.bin ではない: $magic" }
            val schemaVersion = din.readUnsignedShort()
            require(schemaVersion == SCHEMA_VERSION) { "未対応の schema version: $schemaVersion" }

            val strings = Array(din.readInt()) {
                ByteArray(din.readUnsignedShort()).also { din.readFully(it) }.toString(Charsets.UTF_8)
            }

            val groups = List(din.readInt()) { index ->
                val code = din.readInt()
                val name = strings[din.readInt()]
                val position = LonLat(din.readInt() / COORD_SCALE, din.readInt() / COORD_SCALE)
                val operators = List(din.readUnsignedByte()) { strings[din.readInt()] }
                val lines = List(din.readUnsignedByte()) { strings[din.readInt()] }
                StationGroup(index, code, name, position, operators, lines)
            }

            val nodeCount = din.readInt()
            val nodeGroup = IntArray(nodeCount)
            val nodeLine = IntArray(nodeCount)
            for (n in 0 until nodeCount) {
                din.readInt() // 経度 (今は使わない)
                din.readInt() // 緯度
                nodeGroup[n] = din.readInt()
                nodeLine[n] = din.readInt().let { if (it == NO_STRING) -1 else it }
            }

            val edgeCount = din.readInt()
            val edgeFrom = IntArray(edgeCount)
            val edgeTo = IntArray(edgeCount)
            val edgeLength = FloatArray(edgeCount)
            val edgeLineStart = IntArray(edgeCount + 1)
            val lineNames = ArrayList<Int>()
            val lineLengths = ArrayList<Float>()
            val edgePointStart = IntArray(edgeCount + 1)
            val coords = IntArrayBuilder()
            for (e in 0 until edgeCount) {
                edgeFrom[e] = din.readInt()
                edgeTo[e] = din.readInt()
                edgeLength[e] = din.readFloat()
                repeat(din.readUnsignedByte()) {
                    lineNames.add(din.readInt())
                    lineLengths.add(din.readFloat())
                }
                edgeLineStart[e + 1] = lineNames.size
                repeat(din.readInt()) {
                    coords.add(din.readInt())
                    coords.add(din.readInt())
                }
                edgePointStart[e + 1] = coords.size / 2
            }

            return RailGraph(
                groups, strings,
                nodeCount, nodeGroup, nodeLine,
                edgeFrom, edgeTo, edgeLength,
                edgeLineStart, lineNames.toIntArray(), lineLengths.toFloatArray(),
                edgePointStart, coords.toIntArray(),
            )
        }
    }
}

private class IntArrayBuilder {
    private var data = IntArray(1 shl 16)
    var size = 0
        private set

    fun add(value: Int) {
        if (size == data.size) data = data.copyOf(size * 2)
        data[size++] = value
    }

    fun toIntArray(): IntArray = data.copyOf(size)
}
