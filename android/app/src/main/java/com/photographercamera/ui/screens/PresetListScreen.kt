package com.photographercamera.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.photographercamera.core.profile.ProfileLoader
import com.photographercamera.ui.theme.AccentOrange
import com.photographercamera.ui.theme.DarkBackground
import com.photographercamera.ui.theme.SurfaceDark
import com.photographercamera.ui.theme.TextPrimary
import com.photographercamera.ui.theme.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

private val presetEntries = listOf(
    Pair("VINTAGE 400", "暖调人像 · 细腻颗粒 · 高宽容度"),
    Pair("ACROS 100", "黑白 · 细颗粒 · 高锐度"),
    Pair("CINEMA 80", "电影感 · 青橙调 · 柔光晕"),
    Pair("MONO 400", "黑白 · 高反差 · 粗颗粒"),
    Pair("FRESH 200", "清新 · 冷调 · 高通透"),
    Pair("CLASSIC 320", "经典胶片 · 低饱和 · 柔和"),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PresetListScreen(
    navController: NavController,
) {
    val context = LocalContext.current
    val profiles = remember { mutableStateListOf<String>() }
    var selected by remember { mutableStateOf("") }
    // edit mode: tap an imported preset to delete it (bundled ones protected)
    var editing by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        runBlocking {
            ProfileLoader.init(context)
            ProfileLoader.importFromPublicInbox(context)
        }
        profiles.clear(); profiles.addAll(ProfileLoader.listProfiles())
        // 选中态回退链：本次会话用户选过的（savedStateHandle）→ 冷启动恢复的
        // sp.last_preset（相机端已用它渲染，列表高亮必须跟上）→ 列表第一个。
        // 0.3.2 bug：冷启动只回退到第一个，列表高亮与相机实际滤镜不一致。
        val current = navController.previousBackStackEntry?.savedStateHandle?.get<String>("selected_preset")
        val savedLast = context
            .getSharedPreferences("pc_settings", android.content.Context.MODE_PRIVATE)
            .getString("last_preset", null)
        selected = current
            ?: savedLast?.takeIf { profiles.contains(it) }
            ?: profiles.firstOrNull()
            ?: ""
    }

    val entries: List<Triple<String, String, String>> = if (profiles.isNotEmpty()) {
        profiles.map { id ->
            val p = ProfileLoader.getProfile(id)
            // display.name / display.intro come from Studio ("滤镜名称/简介");
            // fall back to the file name, which IS the preset identity.
            Triple(
                id,
                p?.display?.name?.takeIf { it.isNotBlank() } ?: id,
                p?.display?.intro?.takeIf { it.isNotBlank() }
                    ?: presetEntries.toMap()[id] // built-in presets keep their design copy
                    ?: "自定义胶片预设",
            )
        }
    } else {
        presetEntries.map { (t, s) -> Triple(t, t, s) }
    }

    // 0.3.6 UI 修复：LazyListState 默认 rememberSaveable，导航返回栈会恢复上
    // 次浏览留下的滚动位置 → 再次进入列表时视口停在中间，第一个滤镜在视口
    // 上方看不见。进入屏幕（含 profiles 加载完成）后强制归顶。
    val listState = rememberLazyListState()
    LaunchedEffect(profiles.size) { listState.scrollToItem(0) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("相机预设", color = TextPrimary, fontSize = 18.sp) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = TextPrimary)
                    }
                },
                actions = {
                    Text(
                        if (editing) "完成" else "编辑",
                        color = if (editing) AccentOrange else TextSecondary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .clickable { editing = !editing }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = DarkBackground,
                    titleContentColor = TextPrimary,
                ),
            )
        },
        containerColor = DarkBackground,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(DarkBackground),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(entries, key = { it.first }) { (id, title, subtitle) ->
                    val isSelected = id == selected
                    val bundled = remember(id) { ProfileLoader.isBundled(id, context) }
                    PresetItem(
                        id = id,
                        title = title,
                        subtitle = subtitle,
                        selected = isSelected,
                        editing = editing,
                        deletable = !bundled,
                        onClick = {
                            if (editing) {
                                // edit mode: tap selects for deletion (bundled protected)
                                if (!bundled) deleteTarget = id
                            } else {
                                selected = id
                                navController.previousBackStackEntry
                                    ?.savedStateHandle
                                    ?.set("selected_preset", id)
                                navController.popBackStack()
                            }
                        },
                    )
                }
            }

            if (editing) {
                Text(
                    "点击已导入的滤镜可删除 · 内置滤镜不可删除",
                    color = TextSecondary,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(bottom = 6.dp),
                )
            }
            Text(
                "共 ${entries.size} 款胶片预设" + if (editing) "（编辑中）" else "",
                color = TextSecondary,
                fontSize = 13.sp,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(bottom = 20.dp),
            )
        }
    }

    // delete confirmation
    deleteTarget?.let { target ->
        val name = entries.firstOrNull { it.first == target }?.second ?: target
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除滤镜", color = TextPrimary) },
            text = { Text("确定删除「$name」吗？其配置与图标文件将从应用中移除。", color = TextSecondary) },
            confirmButton = {
                Text(
                    "删除",
                    color = AccentOrange,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clickable {
                            deleteTarget = null
                            val removed = runBlocking { ProfileLoader.deleteProfile(context, target) }
                            if (removed) {
                                if (selected == target) selected = ""
                                profiles.clear(); profiles.addAll(ProfileLoader.listProfiles())
                            }
                        }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            },
            dismissButton = {
                Text(
                    "取消",
                    color = TextSecondary,
                    modifier = Modifier
                        .clickable { deleteTarget = null }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            },
            containerColor = SurfaceDark,
        )
    }
}

@Composable
private fun PresetItem(
    id: String,
    title: String,
    subtitle: String,
    selected: Boolean,
    editing: Boolean = false,
    deletable: Boolean = false,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    var iconBitmap by remember(id) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(id) {
        val loc = ProfileLoader.iconPath(id, context) ?: return@LaunchedEffect
        val bmp = withContext(Dispatchers.IO) {
            runCatching {
                val bytes = if (loc.startsWith("assets://")) {
                    context.assets.open("profiles/" + loc.removePrefix("assets://")).use { it.readBytes() }
                } else {
                    java.io.File(loc).readBytes()
                }
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }.getOrNull()
        }
        if (bmp != null) iconBitmap = bmp
    }
    val borderColor = if (selected) AccentOrange else Color.Transparent
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(SurfaceDark)
            .border(1.5.dp, borderColor, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(58.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(if (selected) AccentOrange.copy(alpha = 0.18f) else Color(0xFF3A3A3C))
                .border(1.dp, if (selected) AccentOrange else Color.Transparent, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (iconBitmap != null) {
                Image(
                    bitmap = iconBitmap!!.asImageBitmap(),
                    contentDescription = title,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    imageVector = Icons.Default.PhotoCamera,
                    contentDescription = title,
                    tint = if (selected) AccentOrange else TextPrimary,
                    modifier = Modifier.size(28.dp),
                )
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 14.dp),
        ) {
            Text(
                title,
                color = TextPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                subtitle,
                color = TextSecondary,
                fontSize = 13.sp,
            )
        }

        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(RoundedCornerShape(13.dp))
                .background(if (selected) AccentOrange.copy(alpha = 0.15f) else Color(0xFF3A3A3C))
                .border(1.5.dp, if (selected) AccentOrange else TextSecondary.copy(alpha = 0.3f), RoundedCornerShape(13.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (editing && deletable) {
                // delete affordance: minus badge in an orange ring
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "删除",
                    tint = AccentOrange,
                    modifier = Modifier.size(16.dp),
                )
            } else if (selected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "已选",
                    tint = AccentOrange,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}
