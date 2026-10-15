package com.tunvix.app.ui

import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.first

/** Selection is the anchor; ordinary dragging never changes or re-centres it. */
@Composable fun CenteredGroups(groups: List<String>, selected: String, sourceId: String,
    modifier: Modifier = Modifier, compact: Boolean = false, onSelect: (String) -> Unit) {
    key(sourceId) {
        val state = rememberLazyListState()
        var width by remember { mutableIntStateOf(0) }
        var anchored by remember { mutableStateOf(false) }
        LaunchedEffect(selected, groups, width) {
            val index = groups.indexOf(selected)
            if (index < 0 || width == 0) return@LaunchedEffect
            val viewport = state.layoutInfo.let { it.viewportEndOffset - it.viewportStartOffset }
            val item = state.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
            if (item == null) {
                if (anchored) state.animateScrollToItem(index, -viewport / 2)
                else state.scrollToItem(index, -viewport / 2)
            }
            val measured = snapshotFlow { state.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index } }
                .first { it != null }!!
            val layout = state.layoutInfo
            val delta = measured.offset + measured.size / 2f - (layout.viewportStartOffset + layout.viewportEndOffset) / 2f
            if (anchored) state.animateScrollBy(delta) else state.scrollBy(delta)
            anchored = true
        }
        LazyRow(modifier.onSizeChanged { width=it.width },state=state,
            contentPadding=PaddingValues(horizontal=18.dp),horizontalArrangement=Arrangement.spacedBy(if(compact)8.dp else 7.dp)) {
            items(groups,key={it}) { group -> GroupChip(group,group==selected,compact) { onSelect(group) } }
        }
    }
}
