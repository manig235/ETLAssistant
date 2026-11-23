package kr.snu.etlassistant.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Slideshow
import androidx.compose.material.icons.outlined.TableChart
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role as SemRole
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.wanted.android.wanted.design.theme.DesignSystemTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kr.snu.etlassistant.chat.AttachmentLogic.FileKind
import java.io.File

/** 첨부를 누르면 열고, 길게 누르면 공유한다. */
class AttachmentActions(
    val onOpen: (Attachment) -> Unit,
    val onShare: (Attachment) -> Unit,
)

private fun Attachment.exists(): Boolean = path?.let { File(it).isFile } == true || uri != null

/**
 * 메시지에 붙은 첨부 묶음. 사진은 미리보기, 그 밖의 파일은 카드로 보인다.
 * [alignEnd]면 오른쪽(사용자), 아니면 왼쪽(비서).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AttachmentGroup(
    attachments: List<Attachment>,
    alignEnd: Boolean,
    maxWidth: Dp,
    actions: AttachmentActions,
    modifier: Modifier = Modifier,
) {
    val pages = attachments.filter { it.isImage && it.pagePreview }
    val images = attachments.filter { it.isImage && !it.pagePreview }
    val files = attachments.filter { !it.isImage }
    Column(
        modifier.fillMaxWidth(),
        horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (images.isNotEmpty()) {
            val side = if (images.size == 1) 200.dp else 120.dp
            FlowRow(
                Modifier.widthIn(max = maxWidth),
                horizontalArrangement = Arrangement.spacedBy(6.dp, if (alignEnd) Alignment.End else Alignment.Start),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                images.forEach { ImageThumb(it, side, actions) }
            }
        }
        if (pages.isNotEmpty()) {
            val c = DesignSystemTheme.colors
            val t = DesignSystemTheme.typography
            Text("비서가 본 쪽 ${pages.size}장", style = t.caption1Regular, color = c.foregroundNeutralTertiary)
            Row(
                Modifier
                    .widthIn(max = maxWidth)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                pages.forEach { ImageThumb(it, 110.dp, actions, page = true) }
            }
        }
        files.forEach { FileCard(it, maxWidth, actions) }
    }
}

@Composable
private fun rememberThumb(path: String?, side: Dp): ImageBitmap? {
    val px = with(LocalDensity.current) { side.roundToPx() }
    val bmp by produceState<ImageBitmap?>(null, path, px) {
        value = if (path == null) null else withContext(Dispatchers.IO) { Thumbnails.load(path, px) }
    }
    return bmp
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ImageThumb(a: Attachment, side: Dp, actions: AttachmentActions, page: Boolean = false) {
    val c = DesignSystemTheme.colors
    // 문서 쪽은 세로형(A4)도 가로형(슬라이드)도 있어서 정사각형으로 자르면 내용이 잘린다. 쪽의 실제 비율대로 통째로 보인다.
    val bmp = rememberThumb(a.path, side)
    val sizing = when {
        !page -> Modifier.size(side)
        bmp != null && bmp.height > 0 -> Modifier.width(side).aspectRatio(bmp.width.toFloat() / bmp.height)
        else -> Modifier.size(side, side * 1.41f)
    }
    Box(
        Modifier
            .then(sizing)
            .clip(RoundedCornerShape(14.dp))
            .background(c.surfaceNeutralSecondary)
            .combinedClickable(
                role = SemRole.Image,
                onClickLabel = "열기",
                onLongClickLabel = "공유",
                onClick = { actions.onOpen(a) },
                onLongClick = { actions.onShare(a) },
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (bmp != null) {
            Image(bmp, contentDescription = a.name, contentScale = if (page) ContentScale.Fit else ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Icon(
                if (a.exists()) Icons.Outlined.Image else Icons.Outlined.BrokenImage,
                contentDescription = a.name,
                tint = c.foregroundNeutralTertiary,
            )
        }
    }
}

private fun iconOf(kind: FileKind): ImageVector = when (kind) {
    FileKind.PDF -> Icons.Outlined.PictureAsPdf
    FileKind.IMAGE -> Icons.Outlined.Image
    FileKind.SLIDES -> Icons.Outlined.Slideshow
    FileKind.SHEET -> Icons.Outlined.TableChart
    FileKind.DOC -> Icons.Outlined.Description
    FileKind.HWP -> Icons.AutoMirrored.Outlined.Article
    FileKind.ARCHIVE -> Icons.Outlined.FolderZip
    FileKind.CODE -> Icons.Outlined.Code
    FileKind.OTHER -> Icons.AutoMirrored.Outlined.InsertDriveFile
}

private fun typeLabel(a: Attachment): String {
    val ext = a.name.substringAfterLast('.', "").uppercase()
    return if (ext.isNotEmpty() && ext.length <= 5) ext else "파일"
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileCard(a: Attachment, maxWidth: Dp, actions: AttachmentActions) {
    val c = DesignSystemTheme.colors
    val t = DesignSystemTheme.typography
    val exists = a.exists()
    val detail = buildList {
        add(typeLabel(a))
        if (a.size > 0) add(AttachmentLogic.formatSize(a.size))
        when {
            !exists -> add("폰에서 정리된 파일")
            a.shownPath != null -> add(if (kr.snu.etlassistant.files.GeneratedFilesLogic.isGeneratedPath(a.shownPath)) "내 폰 Download/etl/${kr.snu.etlassistant.files.GeneratedFilesLogic.SUBDIR}" else "내 폰 Download/etl")
        }
    }.joinToString(" · ")
    Row(
        Modifier
            .widthIn(max = maxWidth)
            .clip(RoundedCornerShape(16.dp))
            .background(c.surfaceNeutralSecondary)
            .combinedClickable(
                enabled = exists,
                role = SemRole.Button,
                onClickLabel = "열기",
                onLongClickLabel = "공유",
                onClick = { actions.onOpen(a) },
                onLongClick = { actions.onShare(a) },
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(c.surfaceBrandSubtle),
            contentAlignment = Alignment.Center,
        ) {
            Icon(iconOf(AttachmentLogic.kindOf(a.name, a.mime)), contentDescription = null, tint = c.foregroundBrandPrimary, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f, fill = false)) {
            Text(a.name, style = t.body2Medium, color = c.foregroundNeutralPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(detail, style = t.caption1Regular, color = c.foregroundNeutralSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** 입력창 위: 보내기 전에 고른 첨부. X로 뺀다. */
@Composable
fun PendingAttachments(
    items: List<Attachment>,
    importing: Boolean,
    onRemove: (Attachment) -> Unit,
    actions: AttachmentActions,
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty() && !importing) return
    val c = DesignSystemTheme.colors
    val t = DesignSystemTheme.typography
    Row(
        modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (a in items) {
            Box {
                if (a.isImage) {
                    val bmp = rememberThumb(a.path, 64.dp)
                    Box(
                        Modifier
                            .size(64.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(c.surfaceNeutralSecondary)
                            .clickable { actions.onOpen(a) },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (bmp != null) Image(bmp, a.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        else Icon(Icons.Outlined.Image, null, tint = c.foregroundNeutralTertiary)
                    }
                } else {
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(c.surfaceNeutralSecondary)
                            .clickable { actions.onOpen(a) }
                            .padding(start = 10.dp, end = 28.dp, top = 10.dp, bottom = 10.dp)
                            .widthIn(max = 200.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(iconOf(AttachmentLogic.kindOf(a.name, a.mime)), null, tint = c.foregroundBrandPrimary, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(a.name, style = t.caption1Medium, color = c.foregroundNeutralPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(3.dp)
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(c.surfaceNeutralInverse)
                        .clickable(role = SemRole.Button, onClickLabel = "첨부 빼기") { onRemove(a) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Outlined.Close, contentDescription = "${a.name} 빼기", tint = c.foregroundNeutralInverse, modifier = Modifier.size(14.dp))
                }
            }
        }
        if (importing) {
            Text("가져오는 중…", style = t.caption1Regular, color = c.foregroundNeutralSecondary)
        }
    }
}
