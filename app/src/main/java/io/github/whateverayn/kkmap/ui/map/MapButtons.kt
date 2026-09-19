package io.github.whateverayn.kkmap.ui.map

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import io.github.whateverayn.kkmap.R
import io.github.whateverayn.kkmap.core.map.FollowMode

/** ボタン1つの大きさ. タップ領域の下限 (48dp) を満たす */
private val BUTTON_SIZE = 48.dp

/**
 * 地図の操作ボタン (上から 拡大, 縮小, 追従). アイコンは Material Symbols.
 *
 * 追従ボタン: 追従中は塗りつぶし, 追従していないときは枠線だけ. アイコンで方式を表す
 * (route = 全体表示, near_me = 現在地中心). 追従していないときのアイコンは, 押すと再開する方式
 */
@Composable
fun MapButtons(
    followMode: FollowMode,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onFollow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var lastActive by remember { mutableStateOf(FollowMode.FIT) }
    if (followMode != FollowMode.NONE) lastActive = followMode

    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = modifier) {
        OutlinedMapButton(R.drawable.ic_add, "拡大", onZoomIn)
        OutlinedMapButton(R.drawable.ic_remove, "縮小", onZoomOut)
        val followIcon = if (lastActive == FollowMode.CENTER) R.drawable.ic_near_me else R.drawable.ic_route
        val description = when (followMode) {
            FollowMode.NONE -> "追従を再開"
            FollowMode.FIT -> "追従: 全体表示 (押すと現在地中心に切り替え)"
            FollowMode.CENTER -> "追従: 現在地中心 (押すと全体表示に切り替え)"
        }
        if (followMode == FollowMode.NONE) {
            OutlinedMapButton(followIcon, description, onFollow)
        } else {
            FilledIconButton(onClick = onFollow, modifier = Modifier.size(BUTTON_SIZE)) {
                Icon(painterResource(followIcon), contentDescription = description)
            }
        }
    }
}

@Composable
private fun OutlinedMapButton(@DrawableRes icon: Int, description: String, onClick: () -> Unit) {
    OutlinedIconButton(
        onClick = onClick,
        colors = IconButtonDefaults.outlinedIconButtonColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.size(BUTTON_SIZE),
    ) {
        Icon(painterResource(icon), contentDescription = description)
    }
}
