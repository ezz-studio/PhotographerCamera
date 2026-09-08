package com.photographercamera.photon.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.photographercamera.photon.camera.FocusPointSource
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * 对焦指示器
 */
@Composable
fun FocusIndicator(
    position: Pair<Float, Float>?,
    source: FocusPointSource = FocusPointSource.MANUAL,
    isFocusLocked: Boolean = false,
    isFocusing: Boolean,
    focusSuccess: Boolean?,
    modifier: Modifier = Modifier
) {
    var visible by remember { mutableStateOf(position != null) }
    // 1.3.4：记住最近一次有效位置。对焦结束后（场景变化自动恢复连续对焦）
    // focusPoint 会被清成 null，旧实现此时回退到 (0,0) —— 圆圈几率性跳到
    // 左上角闪烁。淡出期间必须停留在最后一次点击的位置。
    var lastPosition by remember { mutableStateOf(position) }
    SideEffect { if (position != null) lastPosition = position }
    
    // 透明度动画
    val alpha by animateFloatAsState(
        targetValue = when {
            isFocusing -> 1f
            source == FocusPointSource.EYE && position != null -> 0.9f
            isFocusLocked && focusSuccess != false -> 0.9f
            focusSuccess == true -> 0.8f
            isFocusLocked -> 0.6f
            focusSuccess == false -> 0.5f
            else -> 0f
        },
        animationSpec = tween(300),
        label = "focusAlpha"
    )

    // 聚焦动画
    val scale by animateFloatAsState(
        targetValue = if (focusSuccess == true) 0.8f else 1f,
        animationSpec = tween(300),
        label = "focusScale"
    )
    
    // 颜色
    val color = when (focusSuccess) {
        true -> Color.Green
        false -> Color.Red
        else -> if (source == FocusPointSource.EYE) Color(0xFF64D8FF) else Color.White
    }

    LaunchedEffect(position) {
        if (position != null) {
            visible = true
        } else {
            delay(200)
            visible = false
        }
    }

    // 1.3.4：position==null 时不画（而非回退 (0,0)）；淡出用 lastPosition。
    val displayPosition = position ?: lastPosition
    val density = LocalDensity.current

    AnimatedVisibility(visible = visible, modifier = modifier.fillMaxSize()) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            Canvas(
                modifier = Modifier.fillMaxSize()
            ) {
                val pos = displayPosition ?: return@Canvas
                val x = pos.first * size.width
                val y = pos.second * size.height
                if (!x.isFinite() || !y.isFinite() || x < 0f || y < 0f) return@Canvas
                val circleSize = (if (source == FocusPointSource.EYE) 24.dp else 60.dp).toPx() * scale
                val strokeWidth = (if (source == FocusPointSource.EYE) 1.5.dp else 2.dp).toPx()

                val drawColor = color.copy(alpha = alpha)

                // 对焦指示：单个圆圈（替代旧四角框 + 锁定角标）
                drawCircle(
                    color = drawColor,
                    radius = circleSize / 2,
                    center = Offset(x, y),
                    style = Stroke(width = strokeWidth)
                )
            }
        }
    }
}
