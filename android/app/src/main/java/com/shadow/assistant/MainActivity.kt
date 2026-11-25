package com.shadow.assistant

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.BLACK
        window.navigationBarColor = android.graphics.Color.BLACK
        setContent { ShadowHome() }
    }
}

private val Night = Color(0xFF03060C)
private val Blue = Color(0xFF328BFF)
private val Ice = Color(0xFF8BD8FF)

@Composable
private fun ShadowHome() {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = Night) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(listOf(Color.Black, Night, Color(0xFF071323))))
            ) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 26.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(modifier = Modifier.fillMaxSize().weight(0.12f), verticalAlignment = Alignment.CenterVertically) {
                        Text("S", color = Ice, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        Text("  S H A D O W", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 3.sp)
                        Spacer(Modifier.weight(1f))
                        Text("●  READY", color = Color(0xFF73D6A6), fontSize = 10.sp, letterSpacing = 1.5.sp)
                    }
                    Column(
                        modifier = Modifier.weight(0.76f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text("YOUR PERSONAL AI", color = Color(0xFF7F9DBD), fontSize = 10.sp, letterSpacing = 3.sp)
                        Spacer(Modifier.height(14.dp))
                        Text("I'm here.\nWhat shall we do?", color = Color(0xFFF1F6FF), fontSize = 27.sp, lineHeight = 34.sp, fontWeight = FontWeight.Medium)
                        ShadowOrb()
                        Text("TAP TO CONNECT", color = Ice, fontSize = 10.sp, letterSpacing = 2.sp)
                        Spacer(Modifier.height(8.dp))
                        Text("Your assistant is standing by", color = Color(0xFF71839B), fontSize = 12.sp)
                    }
                    Column(
                        modifier = Modifier.weight(0.16f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Surface(color = Color(0xFF101B2B), shape = RoundedCornerShape(28.dp)) {
                            Text("   ＋     Ask SHADOW anything       ➤   ", color = Color(0xFFBBD7F3), fontSize = 12.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 16.dp))
                        }
                        Spacer(Modifier.height(12.dp))
                        Text("PRIVATE BY DESIGN  •  YOU ARE IN CONTROL", color = Color(0xFF52647A), fontSize = 8.sp, letterSpacing = 1.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun ShadowOrb() {
    val transition = rememberInfiniteTransition(label = "shadow-orb")
    val pulse by transition.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(tween(1800), RepeatMode.Reverse),
        label = "orb-pulse"
    )
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(270.dp)) {
        Canvas(Modifier.size(250.dp)) {
            val center = this.center
            drawCircle(Blue.copy(alpha = 0.07f), radius = size.minDimension * 0.46f)
            drawCircle(Blue.copy(alpha = 0.22f), radius = size.minDimension * 0.39f, style = Stroke(width = 1.2.dp.toPx()))
            drawOval(
                color = Ice.copy(alpha = 0.65f),
                topLeft = androidx.compose.ui.geometry.Offset(size.width * 0.13f, size.height * 0.36f),
                size = androidx.compose.ui.geometry.Size(size.width * 0.74f, size.height * 0.28f),
                style = Stroke(width = 2.dp.toPx())
            )
            drawOval(
                color = Blue.copy(alpha = 0.9f),
                topLeft = androidx.compose.ui.geometry.Offset(size.width * 0.22f, size.height * 0.28f),
                size = androidx.compose.ui.geometry.Size(size.width * 0.56f, size.height * 0.42f),
                style = Stroke(width = 2.dp.toPx())
            )
            drawCircle(
                brush = Brush.radialGradient(listOf(Color(0xFF153C78), Color(0xFF06152E), Color.Transparent), center, size.minDimension * 0.28f),
                radius = size.minDimension * 0.28f * pulse
            )
        }
    }
}
