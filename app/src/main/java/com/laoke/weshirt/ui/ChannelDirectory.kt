package com.laoke.weshirt.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apartment
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.laoke.weshirt.data.Channel

@Composable
internal fun ChannelDirectory(channels: List<Channel>, building: String, accountKey: String,
                              mentioned: Set<String>, onSelect: (Channel) -> Unit, retry: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    LazyColumn(Modifier.fillMaxSize().testTag("channel-directory"), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("选择频道", style = MaterialTheme.typography.headlineSmall)
            Text("公共频道和你的住宿楼优先显示", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
        }
        if (channels.isEmpty()) item {
            Text("频道暂未显示，可以重新获取。", style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = retry) { Text("重新获取频道") }
        }
        items(channels.sortedBy { when (it.code) { "public" -> 0; building -> 1; else -> 2 } }, key = { it.code }) { channel ->
            Card(onClick = { onSelect(channel) }, modifier = Modifier.fillMaxWidth().testTag("channel:${channel.code}"), shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainerLow)) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    val public = channel.code == "public"
                    Surface(Modifier.size(48.dp).testTag("channel-icon:${channel.code}"), shape = RoundedCornerShape(16.dp),
                        color = if (public) scheme.primaryContainer else scheme.tertiaryContainer) {
                        if (channel.avatar.isNotBlank()) AsyncImage(
                            ImageRequest.Builder(LocalContext.current).data(channel.avatar)
                                .memoryCacheKey("channel-avatar|$accountKey|${channel.avatar}").diskCachePolicy(CachePolicy.DISABLED).build(),
                            "${channel.name}头像", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        else Box(contentAlignment = Alignment.Center) {
                            Icon(if (public) Icons.Outlined.Forum else Icons.Outlined.Apartment, null, Modifier.size(25.dp),
                                tint = if (public) scheme.onPrimaryContainer else scheme.onTertiaryContainer)
                        }
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(channel.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(channel.summary?.preview?.takeIf(String::isNotBlank) ?: "还没有消息", style = MaterialTheme.typography.bodyMedium,
                            color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.testTag("channel-summary:${channel.code}"))
                        if (channel.code in mentioned) Text("有人 @ 你", color = androidx.compose.ui.graphics.Color(0xFFD87717), style = MaterialTheme.typography.labelMedium)
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (channel.unreadCount > 0) Badge(Modifier.testTag("channel-unread:${channel.code}").semantics { contentDescription = "${channel.unreadCount} 条未读消息" }) {
                            Text(if (channel.unreadCount > 99) "99+" else channel.unreadCount.toString())
                        }
                        Icon(Icons.Outlined.ChevronRight, null, Modifier.size(20.dp), tint = scheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
