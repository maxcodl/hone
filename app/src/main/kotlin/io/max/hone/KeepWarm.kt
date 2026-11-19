@file:OptIn(ExperimentalMaterial3Api::class)

package io.max.hone

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Picks apps that Free memory leaves running; launcher, keyboard and Hone are always kept. */
@Composable
fun KeepWarmSheet(hone: Hone, onClose: () -> Unit) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val always = remember { hone.alwaysKept() }
    var query by remember { mutableStateOf("") }
    val apps by produceState<List<Pair<String, String>>>(emptyList()) {
        value = withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            pm.queryIntentActivities(launcher, 0).map { it.activityInfo.packageName }.distinct()
                .map { it to hone.label(it) }
                .sortedWith(compareByDescending<Pair<String, String>> { it.first in always || it.first in hone.keepWarm }.thenBy { it.second.lowercase() })
        }
    }
    ModalBottomSheet(onDismissRequest = onClose, containerColor = if (LocalLook.current.mono || LocalLook.current.amoled) Color.Black else cs.surfaceContainerLow) {
        Column(Modifier.padding(horizontal = 16.dp).navigationBarsPadding()) {
            Text("Keep warm", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Free memory skips these, so they open instantly. Your launcher, keyboard and Hone are always kept.",
                style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true, shape = CircleShape,
                leadingIcon = { Icon(Sym.Search, null) }, placeholder = { Text("Search apps") }, modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            LazyColumn(Modifier.fillMaxWidth().height(460.dp)) {
                items(apps.filter { query.isBlank() || it.second.contains(query, true) }, key = { it.first }) { (pkg, label) ->
                    val locked = pkg in always
                    val on = locked || pkg in hone.keepWarm
                    Row(
                        Modifier.fillMaxWidth().clickable(enabled = !locked) {
                            hone.updateKeepWarm(if (on) hone.keepWarm - pkg else hone.keepWarm + pkg)
                        }.padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AppIcon(hone, pkg, 40.dp)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                            if (locked) Text("Always kept", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                        }
                        Checkbox(checked = on, enabled = !locked, onCheckedChange = {
                            hone.updateKeepWarm(if (on) hone.keepWarm - pkg else hone.keepWarm + pkg)
                        })
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}
