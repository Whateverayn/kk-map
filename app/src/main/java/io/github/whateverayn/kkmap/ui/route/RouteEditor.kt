package io.github.whateverayn.kkmap.ui.route

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.whateverayn.kkmap.core.rail.RouteCandidate
import io.github.whateverayn.kkmap.core.rail.StationGroup
import io.github.whateverayn.kkmap.core.rail.StationSearch
import io.github.whateverayn.kkmap.core.rail.TransferSuggestion
import io.github.whateverayn.kkmap.rail.RailData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 候補1枠の高さ. タップ領域の下限 (48dp) を満たす */
private val SLOT_HEIGHT = 56.dp

/** 入力の段階 */
private sealed interface Step {
    /** 乗車駅を選ぶ. [nearbyOf] があればその近くの駅を最初の候補にする */
    data class From(val nearbyOf: StationGroup?) : Step

    /** 降車駅を選ぶ */
    data class To(val from: StationGroup) : Step

    /** 経路の候補から選ぶ. [after] があれば, 選んだ後に続けてその区間 (乗換後の区間) も登録する */
    data class PickRoute(
        val from: StationGroup,
        val to: StationGroup,
        val routes: List<RouteCandidate>,
        val after: List<RouteCandidate>? = null,
    ) : Step

    /** 線路でつながっていないので, 乗換の候補から選ぶ */
    data class PickTransfer(val from: StationGroup, val to: StationGroup, val suggestions: List<TransferSuggestion>) : Step
}

private fun defaultStep(sections: List<RouteCandidate>): Step =
    sections.lastOrNull()?.to?.let { Step.To(it) } ?: Step.From(null)

/**
 * 区間の一覧と, 区間を追加する入力.
 *
 * 入力 UI の原則 (アニメーションなし, 候補の位置は入力内容だけで決まる, スクロールしない固定枠):
 * - 画面の下から: キーボード → 入力欄 → 候補 (最有力をキーボードに一番近い位置に置く)
 * - Enter は最有力 (一番下) の候補を確定. 他の候補は枠を直接タップ
 * - 駅の候補が1つに絞れたら (変換の確定時点で) 即座に確定する
 * - 2区間目以降は, 前の区間の降車駅を乗車駅の初期値にする (タップで変更でき, 近くの駅が候補に出る)
 * - 線路でつながっていなければ, 乗換の候補 (どこまで行ってどこへ乗り換えるか) を出す. 選ぶと2区間まとめて登録する
 */
@Composable
fun RouteEditor(
    data: RailData,
    sections: List<RouteCandidate>,
    onSectionsChange: (List<RouteCandidate>) -> Unit,
    onClose: () -> Unit,
) {
    BackHandler(onBack = onClose)
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf(defaultStep(sections)) }
    var query by remember(step) { mutableStateOf(TextFieldValue("")) }
    var message by remember(step) { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    /** 区間を登録する. [after] (乗換後の区間の候補) があれば続けて登録するか, 複数なら選ばせる */
    fun commit(next: List<RouteCandidate>, after: List<RouteCandidate>? = null) {
        onSectionsChange(next)
        step = when {
            after.isNullOrEmpty() -> defaultStep(next)
            after.size == 1 -> defaultStep(next + after.first()).also { onSectionsChange(next + after.first()) }
            else -> Step.PickRoute(after.first().from, after.first().to, after)
        }
    }

    fun chooseStation(station: StationGroup) {
        when (val s = step) {
            is Step.From -> step = Step.To(station)
            is Step.To -> {
                busy = true
                scope.launch {
                    val routes = withContext(Dispatchers.Default) { data.finder.find(s.from, station) }
                    if (routes.isEmpty()) {
                        val suggestions = withContext(Dispatchers.Default) { data.transfers.suggest(s.from, station) }
                        busy = false
                        if (suggestions.isEmpty()) {
                            message = "${s.from.name} → ${station.name}: 線路でつながっておらず, 近くで乗り換えられる駅も見つかりません"
                        } else {
                            step = Step.PickTransfer(s.from, station, suggestions)
                        }
                        return@launch
                    }
                    busy = false
                    when (routes.size) {
                        1 -> commit(sections + routes.first())
                        else -> step = Step.PickRoute(s.from, station, routes)
                    }
                }
            }
            is Step.PickRoute, is Step.PickTransfer -> Unit
        }
    }

    fun chooseTransfer(from: StationGroup, to: StationGroup, suggestion: TransferSuggestion) {
        busy = true
        scope.launch {
            val (first, second) = withContext(Dispatchers.Default) {
                data.finder.find(from, suggestion.via) to data.finder.find(suggestion.next, to)
            }
            busy = false
            when {
                // 乗車駅そのものが乗換駅なら, 乗換後の区間だけ
                suggestion.via.index == from.index -> commit(sections, second)
                first.isEmpty() -> message = "${from.name} → ${suggestion.via.name} の経路が見つかりません"
                first.size == 1 -> commit(sections + first.first(), second)
                else -> step = Step.PickRoute(from, suggestion.via, first, after = second)
            }
        }
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .safeDrawingPadding()
                .imePadding()
                .padding(horizontal = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("経路", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = onClose, modifier = Modifier.heightIn(min = 48.dp)) { Text("閉じる") }
            }
            SectionList(
                sections = sections,
                onRemove = { i -> commit(sections.filterIndexed { j, _ -> j != i }) },
                onClear = { commit(emptyList()) },
                modifier = Modifier.heightIn(max = 200.dp),
            )

            // 段階の表示 (乗車駅はタップで変更できる)
            val header = when (val s = step) {
                is Step.From -> "乗車駅を入力"
                is Step.To -> "${s.from.name} から: 降車駅を入力"
                is Step.PickRoute -> "${s.from.name} → ${s.to.name}: 経路を選択"
                is Step.PickTransfer -> "${s.from.name} → ${s.to.name}: 線路でつながっていません. 乗換を選択"
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                Text(header, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                val s = step
                val from = when (s) {
                    is Step.To -> s.from
                    is Step.PickRoute -> s.from
                    is Step.PickTransfer -> s.from
                    is Step.From -> null
                }
                if (from != null) {
                    TextButton(onClick = { step = Step.From(from) }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text("乗車駅を変更")
                    }
                }
            }
            message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            if (busy) Text("経路を計算中…", style = MaterialTheme.typography.bodySmall)

            // 候補 (残りの高さいっぱいの固定枠. 下ほど有力)
            BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
                val slotCount = (maxHeight / SLOT_HEIGHT).toInt().coerceAtLeast(1)
                when (val s = step) {
                    is Step.PickRoute -> CandidateSlots(
                        slotCount = slotCount,
                        items = routeLabels(s.routes),
                        onPick = { i -> commit(sections + s.routes[i], s.after) },
                    )
                    is Step.PickTransfer -> CandidateSlots(
                        slotCount = slotCount,
                        items = s.suggestions.map { transferLabel(it) },
                        onPick = { i -> chooseTransfer(s.from, s.to, s.suggestions[i]) },
                    )
                    else -> {
                        val stations = stationCandidates(data, s, query.text)
                        val q = StationSearch.normalize(query.text)
                        CandidateSlots(
                            slotCount = slotCount,
                            items = stations.map { stationLabel(it, stations) },
                            onPick = { i -> chooseStation(stations[i]) },
                            // 完全一致 (同名の駅) だけで枠が埋まるときは, それらをスクロールで選べるようにする
                            scrollableCount = if (q.isEmpty()) 0 else stations.count { StationSearch.normalize(it.name) == q },
                        )
                    }
                }
            }

            // 入力欄 (経路・乗換の選択中は出さない)
            if (step is Step.From || step is Step.To) {
                val focus = remember { FocusRequester() }
                LaunchedEffect(step) { focus.requestFocus() }
                OutlinedTextField(
                    value = query,
                    onValueChange = { value ->
                        query = value
                        message = null
                        // 変換が確定していて, 候補が1つに絞れたら即確定
                        if (value.composition == null && value.text.isNotBlank()) {
                            val hits = data.search.search(value.text)
                            if (hits.size == 1) {
                                query = TextFieldValue("", TextRange.Zero)
                                chooseStation(hits.first())
                            }
                        }
                    },
                    singleLine = true,
                    placeholder = { Text("駅名 (漢字)") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = {
                        val s = step
                        stationCandidates(data, s, query.text).firstOrNull()?.let { chooseStation(it) }
                    }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                        .focusRequester(focus),
                )
            }
        }
    }
}

/** 駅の候補 (有力な順). 入力が空で乗車駅の変更中なら, 元の乗車駅と近くの駅 */
private fun stationCandidates(data: RailData, step: Step, text: String): List<StationGroup> {
    if (text.isBlank()) {
        val near = (step as? Step.From)?.nearbyOf ?: return emptyList()
        return listOf(near) + data.search.nearby(near)
    }
    return data.search.search(text)
}

/**
 * 駅の表示. 事業者名を添え, 同名の駅があるときは路線名も添える
 * (事業者だけでは区別できない同名駅がある. 例: 大久保 は JR東日本 の中央線と奥羽線の両方にある)
 */
private fun stationLabel(station: StationGroup, all: List<StationGroup>): String {
    val sameName = all.count { it.name == station.name } > 1
    val operators = station.operators.joinToString(" ")
    return if (sameName) {
        "${station.name}  ($operators / ${station.lines.joinToString("・")})"
    } else {
        "${station.name}  $operators"
    }
}

private fun transferLabel(s: TransferSuggestion): String {
    val km = "%.1fkm".format(s.totalMeters / 1000)
    return if (s.via.index == s.next.index) {
        "${s.via.name} で乗換  計 $km"
    } else {
        "${s.via.name} → 徒歩 ${s.walkMeters.toInt()}m → ${s.next.name}  計 $km"
    }
}

/**
 * 経路の候補の表示. 見分けがつくように経由駅を添える.
 *
 * 経由駅は, ほかの候補が通らない途中駅が一番長く続く区間の, 真ん中の駅 (大阪 → 奈良 なら環状線の回り方が分かる駅).
 * 1駅だけ離れて出てくる "ほかの候補が通らない駅" は選ばない. 線路データの都合で, 実際にはどちらの候補でも停まる駅
 * (大阪 → 奈良 の天王寺: 片方の経路だけホームを通らない線路を通る) がそうなることがあるため.
 * そういう駅が無ければ, 途中駅の真ん中あたりの駅
 */
private fun routeLabels(routes: List<RouteCandidate>): List<String> = routes.map { route ->
    val km = "%.1fkm".format(route.lengthMeters / 1000)
    val middle = route.stations.drop(1).dropLast(1)
    val others = routes.filter { it !== route }.flatMap { it.stations }.map { it.index }.toSet()
    // ほかの候補が通らない駅が連続する区間
    val runs = mutableListOf<MutableList<StationGroup>>()
    var previousUnique = false
    for (s in middle) {
        val unique = s.index !in others
        if (unique) {
            if (!previousUnique) runs.add(mutableListOf())
            runs.last().add(s)
        }
        previousUnique = unique
    }
    val longest = runs.maxByOrNull { it.size }
    val via = longest?.get((longest.size - 1) / 2) ?: middle.getOrNull(middle.size / 2)
    listOfNotNull(route.lines.joinToString(" → "), km, via?.let { "${it.name} 経由" }).joinToString("  ")
}

/**
 * 固定数の枠. 最有力 (items[0]) を一番下に置き, 上に向かって順に並べる.
 * 候補が枠より少なければ空の枠を残す (枠の位置は変わらない). 多ければ入り切らない分は出さない.
 *
 * ただし先頭 [scrollableCount] 個 (同名の駅など, 入力では絞り込めない候補) が枠に入り切らないときだけ,
 * それらをスクロールで選べるようにする. 初期表示は一番下 (最有力) で, 上へスクロールすると残りが見える.
 */
@Composable
private fun CandidateSlots(slotCount: Int, items: List<String>, onPick: (Int) -> Unit, scrollableCount: Int = 0) {
    if (scrollableCount > slotCount) {
        LazyColumn(reverseLayout = true, modifier = Modifier.fillMaxSize()) {
            items(scrollableCount) { i -> Slot(items[i], highlighted = i == 0) { onPick(i) } }
        }
        return
    }
    Column(
        verticalArrangement = Arrangement.Bottom,
        modifier = Modifier.fillMaxSize(),
    ) {
        for (slot in slotCount - 1 downTo 0) {
            val text = items.getOrNull(slot)
            Slot(text, highlighted = slot == 0 && text != null, onClick = if (text != null) ({ onPick(slot) }) else null)
        }
    }
}

@Composable
private fun Slot(text: String?, highlighted: Boolean, onClick: (() -> Unit)?) {
    Box(
        contentAlignment = Alignment.CenterStart,
        modifier = Modifier
            .fillMaxWidth()
            .height(SLOT_HEIGHT)
            .padding(vertical = 2.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        Surface(
            shape = MaterialTheme.shapes.small,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            color = if (highlighted) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(contentAlignment = Alignment.CenterStart, modifier = Modifier.padding(horizontal = 12.dp)) {
                Text(text.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun SectionList(
    sections: List<RouteCandidate>,
    onRemove: (Int) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 区間が増えたら, 直近に追加した区間が見えるように一番下までスクロールする (入力 UI なのでアニメーションはしない)
    val scroll = rememberScrollState()
    LaunchedEffect(sections.size, scroll.maxValue) { scroll.scrollTo(scroll.maxValue) }
    Column(modifier = modifier.verticalScroll(scroll)) {
        if (sections.isEmpty()) {
            Text("区間はまだありません", style = MaterialTheme.typography.bodyMedium)
        }
        sections.forEachIndexed { i, s ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${i + 1}. ${s.from.name} → ${s.to.name}  ${s.lines.joinToString(" → ")}  ${"%.1f".format(s.lengthMeters / 1000)}km",
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { onRemove(i) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("削除") }
            }
        }
    }
    if (sections.isNotEmpty()) {
        OutlinedButton(onClick = onClear, modifier = Modifier.heightIn(min = 48.dp)) { Text("すべて削除") }
    }
}
