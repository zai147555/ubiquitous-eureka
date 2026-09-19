package com.nekonyan.assistant.ui.screen

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nekonyan.assistant.data.db.KnowledgeCategory
import com.nekonyan.assistant.data.db.KnowledgeItem
import com.nekonyan.assistant.ui.theme.NekoTheme
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch

/**
 * 知识库（需求：多个知识库，分类可独立开关，支持文本图片；支持导入、整体导出恢复）
 *
 * M1 实现：知识库/分类的创建与选择、分类独立开关、文本条目增删。
 * 图片条目、导入（本地/分享/剪贴板/压缩包/OCR）与整体导出在 M13 完成 ——
 * 数据层已支持 imageUri，界面后续只需加一个入口。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KnowledgeScreen(
    onBack: () -> Unit,
    vm: KnowledgeViewModel = viewModel(factory = KnowledgeViewModel.Factory)
) {
    val bases by vm.bases.collectAsStateWithLifecycle()
    val categories by vm.categories.collectAsStateWithLifecycle()
    val items by vm.items.collectAsStateWithLifecycle()
    val selectedBase by vm.selectedBaseId.collectAsStateWithLifecycle()
    val selectedCategory by vm.selectedCategoryId.collectAsStateWithLifecycle()

    var newBaseName by remember { mutableStateOf("") }
    var newCategoryName by remember { mutableStateOf("") }
    var newItemText by remember { mutableStateOf("") }

    // 需求：知识库支持文本图片 —— 用系统照片选择器（用户显式选择，不申请整库权限）
    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) vm.addImage(uri.toString())
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("知识库") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        },
        floatingActionButton = {
            if (selectedCategory != null) {
                ExtendedFloatingActionButton(
                    onClick = {
                        if (newItemText.isNotBlank()) {
                            vm.addText(newItemText)
                            newItemText = ""
                        }
                    },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text("添加条目") }
                )
            }
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            AiKnowledgeSection(vm)

            // ---- 第一层：知识库 ----
            SectionLabel("知识库（可多个）")
            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(bases, key = { it.id }) { b ->
                    FilterChip(
                        selected = b.id == selectedBase,
                        onClick = { vm.selectBase(b.id) },
                        label = { Text(b.name) },
                        trailingIcon = {
                            IconButton(onClick = { vm.deleteBase(b) }, modifier = Modifier.width(28.dp)) {
                                Icon(Icons.Filled.Delete, contentDescription = "删除知识库", modifier = Modifier.height(16.dp))
                            }
                        }
                    )
                }
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = newBaseName,
                    onValueChange = { newBaseName = it },
                    label = { Text("新建知识库") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = {
                    if (newBaseName.isNotBlank()) {
                        vm.createBase(newBaseName)
                        newBaseName = ""
                    }
                }) { Text("创建") }
            }

            // ---- 第二层：分类（可独立开关） ----
            if (selectedBase != null) {
                SectionLabel("分类（点右侧开关控制 AI 是否读取）")
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(categories, key = { it.id }) { c ->
                        CategoryChip(
                            category = c,
                            selected = c.id == selectedCategory,
                            onSelect = { vm.selectCategory(c.id) },
                            onToggle = { vm.toggleCategory(c) },
                            onDelete = { vm.deleteCategory(c) }
                        )
                    }
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = newCategoryName,
                        onValueChange = { newCategoryName = it },
                        label = { Text("新建分类") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = {
                        if (newCategoryName.isNotBlank()) {
                            vm.createCategory(newCategoryName)
                            newCategoryName = ""
                        }
                    }) { Text("创建") }
                }
            }

            // ---- 第三层：条目 ----
            if (selectedCategory != null) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = newItemText,
                        onValueChange = { newItemText = it },
                        label = { Text("新增文本条目") },
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = {
                        vm.addText(newItemText)
                        newItemText = ""
                    }) { Text("添加") }
                    IconButton(onClick = {
                        pickImage.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    }) {
                        Icon(Icons.Filled.Add, contentDescription = "添加图片条目")
                    }
                }
                Spacer(Modifier.height(6.dp))
                if (items.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("该分类暂无条目", color = NekoTheme.extra.muted)
                    }
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(items, key = { it.id }) { item -> KnowledgeItemCard(item, vm) }
                    }
                }
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "请选择或新建一个知识库与分类",
                        color = NekoTheme.extra.muted
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 14.dp, top = 12.dp, bottom = 4.dp)
    )
}

@Composable
private fun CategoryChip(
    category: KnowledgeCategory,
    selected: Boolean,
    onSelect: () -> Unit,
    onToggle: () -> Unit,
    onDelete: () -> Unit
) {
    FilterChip(
        selected = selected,
        onClick = onSelect,
        label = { Text(category.name) },
        trailingIcon = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 需求：分类可独立开关
                TextButton(onClick = onToggle) {
                    Text(
                        if (category.enabledForAI) "AI 开" else "AI 关",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (category.enabledForAI) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            NekoTheme.extra.muted
                        }
                    )
                }
                IconButton(onClick = onDelete, modifier = Modifier.width(28.dp)) {
                    Icon(Icons.Filled.Delete, contentDescription = "删除分类", modifier = Modifier.height(16.dp))
                }
            }
        }
    )
}

@Composable
private fun KnowledgeItemCard(item: KnowledgeItem, vm: KnowledgeViewModel) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (item.type == KnowledgeItem.TYPE_IMAGE) "图片条目" else item.textContent,
                    style = MaterialTheme.typography.bodyMedium
                )
                if (item.type == KnowledgeItem.TYPE_IMAGE) {
                    Text(
                        item.textContent.ifBlank { "图片条目" } + " · " +
                                (item.imageUri?.takeLast(24) ?: "无 URI"),
                        style = MaterialTheme.typography.labelSmall,
                        color = NekoTheme.extra.muted
                    )
                }
            }
            IconButton(onClick = { vm.deleteItem(item) }) {
                Icon(Icons.Filled.Delete, contentDescription = "删除条目")
            }
        }
    }
}

/**
 * AI 知识库卡片（`修改.ds` 第三项）。
 *
 * 需求要点全部落在这里：
 *   · **固定显示**在知识库页顶部，用主题色卡片 + 星标图标做特殊标识；
 *   · 明确标注「AI 可读取 · 用户不可删除」；
 *   · 用户**可以查看**分类与条目，但界面**不提供**任何删除/重命名/清空入口
 *     （不是"按钮置灰"，而是根本没有这个入口 —— 从源头杜绝误删）；
 *   · 「允许 AI 读取」开关默认开启，改动落库并写只读日志；
 *   · AI 的写入走 [com.nekonyan.assistant.data.repo.AIKnowledgeRepository.writeFromAI]，
 *     写前必须给总结、同标题合并、可带截图。
 */
@Composable
private fun AiKnowledgeSection(vm: KnowledgeViewModel) {
    val base by vm.aiBase.collectAsStateWithLifecycle()
    val categories by vm.aiCategories.collectAsStateWithLifecycle()
    val items by vm.aiItems.collectAsStateWithLifecycle()
    val selected by vm.selectedAiCategoryId.collectAsStateWithLifecycle()
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.AutoAwesome,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        base?.name?.ifBlank { "AI 知识库" } ?: "AI 知识库",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        "AI 可读取 · 用户不可删除 / 重命名 / 清空",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                TextButton(onClick = {
                    expanded = !expanded
                    if (expanded) vm.logAiView()
                }) { Text(if (expanded) "收起" else "查看") }
            }

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "允许 AI 读取（默认开启）",
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Switch(
                    checked = base?.readableByAI ?: true,
                    onCheckedChange = { vm.setAiReadable(it) }
                )
            }

            if (expanded) {
                if (base == null) {
                    Text("AI 知识库尚未初始化（首启种子数据会建立）", style = MaterialTheme.typography.bodySmall)
                } else {
                    if (categories.isEmpty()) {
                        Text("暂无分类：AI 首次写入时会自动建立「AI 自动总结」分类", style = MaterialTheme.typography.bodySmall)
                    } else {
                        Row(
                            Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            categories.forEach { c ->
                                FilterChip(
                                    selected = c.id == selected,
                                    onClick = { vm.selectAiCategory(c.id) },
                                    label = { Text(c.name) }
                                )
                            }
                        }
                    }

                    if (selected == null) {
                        Text(
                            "选择一个分类查看条目（只读）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    } else if (items.isEmpty()) {
                        Text(
                            "该分类暂无条目：AI 写入并总结后会出现在这里",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    } else {
                        items.forEach { item ->
                            Surface(
                                color = MaterialTheme.colorScheme.surface,
                                shape = MaterialTheme.shapes.medium,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(item.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                    if (item.summary.isNotBlank()) {
                                        Text(item.summary, style = MaterialTheme.typography.bodySmall)
                                    }
                                    Text(
                                        "来源 ${item.sourceType} · " + formatAiTime(item.createdAt),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }

                    Text(
                        "这里只读：AI 知识库的增删改由 AI 与设置控制，界面不提供删除入口。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }
    }
}

private val aiTimeFormat = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA)

private fun formatAiTime(ts: Long): String = aiTimeFormat.format(java.util.Date(ts))
