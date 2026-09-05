package com.photographercamera.ui.screens

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.NavController
import com.photographercamera.core.storage.CaptureSaver
import com.photographercamera.core.storage.CaptureSaver.SavedPhoto
import com.photographercamera.ui.theme.AccentOrange
import com.photographercamera.ui.theme.DarkBackground
import com.photographercamera.ui.theme.SurfaceDark
import com.photographercamera.ui.theme.TextPrimary
import com.photographercamera.ui.theme.TextSecondary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(navController: NavController) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val photos = remember { mutableStateListOf<SavedPhoto>() }
    var viewerPhoto by remember { mutableStateOf<SavedPhoto?>(null) }

    DisposableEffect(lifecycleOwner) {
        photos.clear(); photos.addAll(CaptureSaver.list(context))
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                photos.clear(); photos.addAll(CaptureSaver.list(context))
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Full-screen photo viewer: tap a thumb to open, tap anywhere / back to close.
    viewerPhoto?.let { photo ->
        PhotoViewer(photo = photo, onClose = { viewerPhoto = null })
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("相册", color = TextPrimary, fontSize = 18.sp) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = TextPrimary)
                    }
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
            // Real capture count (was hard-coded fake data)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("全部照片", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text("共 ${photos.size} 张", color = TextSecondary, fontSize = 14.sp)
            }

            if (photos.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("暂无照片", color = TextSecondary, fontSize = 15.sp)
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(4),
                    contentPadding = PaddingValues(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(photos, key = { it.uri.toString() }) { photo ->
                        GalleryThumb(photo = photo, onClick = { viewerPhoto = photo })
                    }
                }
            }
        }
    }

    // Floating "+" button anchored to bottom-right
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomEnd) {
        Box(
            modifier = Modifier
                .padding(20.dp)
                .size(52.dp)
                .clip(CircleShape)
                .background(AccentOrange)
                .clickable { navController.popBackStack() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = "返回相机",
                tint = Color.Black,
                modifier = Modifier.size(28.dp),
            )
        }
    }
}

@Composable
private fun GalleryThumb(photo: SavedPhoto, onClick: () -> Unit) {
    // Coil: async MediaStore load with built-in downsampling + caching — no
    // hand-rolled decode, no main-thread stalls, works for every format.
    coil.compose.AsyncImage(
        model = photo.uri,
        contentDescription = "照片",
        modifier = Modifier
            .size(88.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(SurfaceDark)
            .clickable(onClick = onClick)
            .padding(2.dp),
        contentScale = ContentScale.Crop,
    )
}

/**
 * Full-screen photo viewer — decodes the full-resolution image, fits it inside
 * the screen on a black background, shows the file name on top. Tap anywhere
 * (or system back) to close.
 */
@Composable
private fun PhotoViewer(photo: SavedPhoto, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    // Coil decodes off the main thread and downsamples to the display size —
    // no manual inSampleSize math, no OOM on 12MP files.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(onClick = onClose),
        contentAlignment = Alignment.Center,
    ) {
        coil.compose.AsyncImage(
            model = photo.uri,
            contentDescription = photo.name,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit,
        )
        Text(
            photo.name,
            color = Color.White.copy(alpha = 0.75f),
            fontSize = 12.sp,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 40.dp),
        )
    }
}
