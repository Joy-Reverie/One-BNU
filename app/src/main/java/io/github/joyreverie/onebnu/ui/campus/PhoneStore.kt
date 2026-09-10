package io.github.joyreverie.onebnu.ui.campus

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** 用户自己添加的号码，如学院教务办、导师办公室。 */
data class PhoneEntry(val name: String, val number: String)

/** 自添加号码的本地存储：一行一条，名称与号码用 U+0001 分隔（与旧版本兼容）。 */
object PhoneStore {
    private const val PREFS = "onebnu_phones"
    private const val KEY = "entries"
    private val SEP: Char = 1.toChar()

    fun load(context: Context): List<PhoneEntry> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "").orEmpty()
        return raw.split('\n').mapNotNull { line ->
            val i = line.indexOf(SEP)
            if (i <= 0) null else PhoneEntry(line.substring(0, i), line.substring(i + 1))
        }
    }

    fun save(context: Context, entries: List<PhoneEntry>) {
        val raw = entries.joinToString("\n") { "${it.name}$SEP${it.number}" }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, raw).apply()
    }
}

@Composable
internal fun AddPhoneDialog(onDismiss: () -> Unit, onConfirm: (String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var number by remember { mutableStateOf("") }
    val valid = name.isNotBlank() && number.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加号码") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名称，如「学院教务办」") },
                    singleLine = true,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = number,
                    onValueChange = { number = it },
                    label = { Text("号码") },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name.trim(), number.trim()) }, enabled = valid) { Text("添加") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
