package com.blurify.island

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF7DF08E), background = Color(0xFF0B0B0F))) {
                Box(Modifier.fillMaxSize().background(Color(0xFF0B0B0F))) { Home() }
            }
        }
    }

    @Composable
    private fun Home() {
        val ctx = LocalContext.current
        var listener by remember { mutableStateOf(false) }
        var overlay by remember { mutableStateOf(false) }
        var unlockAnim by remember { mutableStateOf(IslandController.unlockAnimEnabled(ctx)) }

        LaunchedEffect(Unit) {
            while (true) {
                listener = NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)
                overlay = isIslandEnabled()
                delay(600)
            }
        }
        val ready = listener && overlay

        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Spacer(Modifier.height(8.dp))
            // A little island as the logo
            Box(
                Modifier.align(Alignment.CenterHorizontally).width(150.dp).height(42.dp)
                    .clip(RoundedCornerShape(21.dp)).background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(Color(0xFF7DF08E)))
                    Text("Island", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                }
            }
            Text(
                "A Dynamic Island for your phone", fontSize = 24.sp, fontWeight = FontWeight.Bold,
                color = Color.White, modifier = Modifier.align(Alignment.CenterHorizontally)
            )
            Text(
                "It grows out of your camera hole: music, mic and camera activity, and an unlock animation.",
                fontSize = 14.sp, color = Color(0xFFA7ADBF), modifier = Modifier.align(Alignment.CenterHorizontally)
            )
            Spacer(Modifier.height(6.dp))

            StepCard(
                "1  Media access", "Lets the island see the song that is playing.",
                listener, "Allow"
            ) {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
            StepCard(
                "2  Turn on Island", "Lets the island draw on top of the status bar.",
                overlay, "Allow"
            ) {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }

            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color(0xFF16181F)).padding(18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Unlock animation", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    Text("The green face when you unlock", color = Color(0xFFA7ADBF), fontSize = 12.sp)
                }
                Switch(
                    checked = unlockAnim,
                    onCheckedChange = {
                        unlockAnim = it
                        IslandController.prefs(ctx).edit().putBoolean(IslandController.KEY_UNLOCK, it).apply()
                        IslandAccessibilityService.instance?.controller?.refresh()
                    },
                    colors = SwitchDefaults.colors(checkedTrackColor = Color(0xFF30D158), checkedThumbColor = Color.White)
                )
            }

            if (ready) {
                Button(
                    onClick = { IslandAccessibilityService.instance?.controller?.demo(true) },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF30D158), contentColor = Color.Black),
                    shape = RoundedCornerShape(16.dp)
                ) { Text("Try the music island", fontWeight = FontWeight.SemiBold) }
                OutlinedButton(
                    onClick = { IslandAccessibilityService.instance?.controller?.demoTorch() },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(16.dp)
                ) { Text("Try the flashlight island", color = Color.White) }
                OutlinedButton(
                    onClick = { IslandAccessibilityService.instance?.controller?.startUnlock() },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(16.dp)
                ) { Text("Preview the unlock animation", color = Color.White) }
                Text(
                    "Tap the island to open the player. Tap anywhere else to close it.",
                    fontSize = 12.sp, color = Color(0xFF7D8397), modifier = Modifier.align(Alignment.CenterHorizontally)
                )
            } else {
                Text(
                    "If \"Allow\" is greyed out, open Settings > Apps > Island, tap the three dots and choose " +
                            "\"Allow restricted settings\", then come back.",
                    fontSize = 12.sp, color = Color(0xFF7D8397)
                )
            }
        }
    }

    private fun isIslandEnabled(): Boolean {
        val me = ComponentName(this, IslandAccessibilityService::class.java).flattenToString()
        val list = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        return list.split(':').any { it.equals(me, ignoreCase = true) }
    }

    @Composable
    private fun StepCard(title: String, body: String, done: Boolean, action: String, onClick: () -> Unit) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color(0xFF16181F)).padding(18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Text(body, color = Color(0xFFA7ADBF), fontSize = 12.sp)
            }
            if (done) {
                Icon(Icons.Rounded.CheckCircle, null, tint = Color(0xFF30D158), modifier = Modifier.size(28.dp))
            } else {
                Button(
                    onClick = onClick,
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black)
                ) { Text(action) }
            }
        }
    }
}
