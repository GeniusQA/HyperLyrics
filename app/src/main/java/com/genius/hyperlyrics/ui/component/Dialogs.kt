package com.genius.hyperlyrics.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.genius.hyperlyrics.R
import com.genius.hyperlyrics.utils.LogManager
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.RadioButtonPreference
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun NumberInputDialog(
    show: Boolean,
    title: String,
    label: String,
    useLabelAsPlaceholder: Boolean = false,
    initialValue: Int,
    min: Int,
    max: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    var inputValue by remember { mutableStateOf(initialValue.toString()) }
    LaunchedEffect(show, initialValue) {
        if (show) inputValue = initialValue.toString()
    }

    WindowDialog(title = title, show = show, onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth()) {
            TextField(
                value = inputValue,
                onValueChange = { newValue -> if (newValue.all { it.isDigit() }) inputValue = newValue },
                label = label,
                useLabelAsPlaceholder = useLabelAsPlaceholder,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                maxLines = 1
            )
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                TextButton(text = stringResource(id = R.string.cancel), onClick = onDismiss, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(20.dp))
                TextButton(
                    text = stringResource(id = R.string.confirm),
                    onClick = {
                        inputValue.toIntOrNull()?.let {
                            onConfirm(it.coerceIn(min, max))
                            onDismiss()
                        }
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary()
                )
            }
        }
    }
}

@Composable
fun NumberRangeInputDialog(
    show: Boolean,
    title: String,
    minLabel: String,
    maxLabel: String,
    initialMinValue: Int,
    initialMaxValue: Int,
    allowedMin: Int,
    allowedMax: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int, Int) -> Unit,
) {
    var minInputValue by remember { mutableStateOf(initialMinValue.toString()) }
    var maxInputValue by remember { mutableStateOf(initialMaxValue.toString()) }
    LaunchedEffect(show, initialMinValue, initialMaxValue) {
        if (show) {
            minInputValue = initialMinValue.toString()
            maxInputValue = initialMaxValue.toString()
        }
    }

    WindowDialog(title = title, show = show, onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth()) {
            TextField(
                value = minInputValue,
                onValueChange = { newValue ->
                    if (newValue.all(Char::isDigit)) minInputValue = newValue
                },
                label = minLabel,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
                maxLines = 1,
            )
            Spacer(modifier = Modifier.height(12.dp))
            TextField(
                value = maxInputValue,
                onValueChange = { newValue ->
                    if (newValue.all(Char::isDigit)) maxInputValue = newValue
                },
                label = maxLabel,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
                maxLines = 1,
            )
            Spacer(modifier = Modifier.height(24.dp))
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                TextButton(
                    text = stringResource(id = R.string.cancel),
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(20.dp))
                TextButton(
                    text = stringResource(id = R.string.confirm),
                    onClick = {
                        val enteredMin = minInputValue.toIntOrNull() ?: return@TextButton
                        val enteredMax = maxInputValue.toIntOrNull() ?: return@TextButton
                        val clampedMin = enteredMin.coerceIn(allowedMin, allowedMax)
                        val clampedMax = enteredMax.coerceIn(allowedMin, allowedMax)
                        onConfirm(
                            minOf(clampedMin, clampedMax),
                            maxOf(clampedMin, clampedMax),
                        )
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        }
    }
}

@Composable
fun FloatRangeInputDialog(
    show: Boolean,
    title: String,
    minLabel: String,
    maxLabel: String,
    initialMinValue: Float,
    initialMaxValue: Float,
    allowedMin: Float,
    allowedMax: Float,
    onDismiss: () -> Unit,
    onConfirm: (Float, Float) -> Unit,
) {
    var minInputValue by remember { mutableStateOf(initialMinValue.toString()) }
    var maxInputValue by remember { mutableStateOf(initialMaxValue.toString()) }
    LaunchedEffect(show, initialMinValue, initialMaxValue) {
        if (show) {
            minInputValue = initialMinValue.toString()
            maxInputValue = initialMaxValue.toString()
        }
    }
    val acceptsDecimal = { value: String ->
        value.count { it == '.' } <= 1 && value.all { it.isDigit() || it == '.' }
    }

    WindowDialog(title = title, show = show, onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth()) {
            TextField(
                value = minInputValue,
                onValueChange = { if (acceptsDecimal(it)) minInputValue = it },
                label = minLabel,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
                maxLines = 1,
            )
            Spacer(modifier = Modifier.height(12.dp))
            TextField(
                value = maxInputValue,
                onValueChange = { if (acceptsDecimal(it)) maxInputValue = it },
                label = maxLabel,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
                maxLines = 1,
            )
            Spacer(modifier = Modifier.height(24.dp))
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                TextButton(
                    text = stringResource(id = R.string.cancel),
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(20.dp))
                TextButton(
                    text = stringResource(id = R.string.confirm),
                    onClick = {
                        val enteredMin = minInputValue.toFloatOrNull() ?: return@TextButton
                        val enteredMax = maxInputValue.toFloatOrNull() ?: return@TextButton
                        val clampedMin = enteredMin.coerceIn(allowedMin, allowedMax)
                        val clampedMax = enteredMax.coerceIn(allowedMin, allowedMax)
                        onConfirm(
                            minOf(clampedMin, clampedMax),
                            maxOf(clampedMin, clampedMax),
                        )
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        }
    }
}

@Composable
fun TextInputDialog(
    show: Boolean,
    title: String,
    initialValue: String,
    label: String = title,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    confirmText: String = stringResource(id = R.string.confirm),
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var inputValue by remember { mutableStateOf(initialValue) }
    LaunchedEffect(show, initialValue) {
        if (show) inputValue = initialValue
    }

    WindowDialog(title = title, show = show, onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth()) {
            TextField(
                value = inputValue,
                onValueChange = { inputValue = it },
                label = label,
                keyboardOptions = keyboardOptions,
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                maxLines = 15
            )
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                TextButton(text = stringResource(id = R.string.cancel), onClick = onDismiss, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(20.dp))
                TextButton(
                    text = confirmText,
                    onClick = { onConfirm(inputValue); onDismiss() },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary()
                )
            }
        }
    }
}

@Composable
fun SimpleDialog(
    show: Boolean,
    title: String,
    summary: String? = null,
    confirmText: String = stringResource(id = R.string.confirm),
    cancelText: String = stringResource(id = R.string.cancel),
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    WindowDialog(
        title = title,
        summary = summary,
        show = show,
        onDismissRequest = onDismiss
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(text = cancelText, onClick = onDismiss, modifier = Modifier.weight(1f))
            Spacer(modifier = Modifier.width(20.dp))
            TextButton(
                text = confirmText,
                colors = ButtonDefaults.textButtonColorsPrimary(),
                modifier = Modifier.weight(1f),
                onClick = {
                    onConfirm()
                    onDismiss()
                }
            )
        }
    }
}

@Composable
fun FloatInputDialog(
    show: Boolean,
    title: String,
    label: String,
    initialValue: Float,
    min: Float,
    max: Float,
    onDismiss: () -> Unit,
    onConfirm: (Float) -> Unit
) {
    var inputValue by remember { mutableStateOf(initialValue.toString()) }
    LaunchedEffect(show, initialValue) {
        if (show) inputValue = initialValue.toString()
    }

    WindowDialog(title = title, show = show, onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth()) {
            TextField(
                value = inputValue,
                onValueChange = { newValue -> if (newValue.all { it.isDigit() || it == '.' }) inputValue = newValue },
                label = label,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                maxLines = 1
            )
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                TextButton(text = stringResource(id = R.string.cancel), onClick = onDismiss, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(20.dp))
                TextButton(
                    text = stringResource(id = R.string.confirm),
                    onClick = {
                        inputValue.toFloatOrNull()?.let {
                            onConfirm(it.coerceIn(min, max))
                            onDismiss()
                        }
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary()
                )
            }
        }
    }
}

@Composable
fun PaddingInputDialog(
    show: Boolean,
    title: String,
    initialLeft: Int,
    initialRight: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int, Int) -> Unit
) {
    var leftValue by remember { mutableStateOf(initialLeft.toString()) }
    var rightValue by remember { mutableStateOf(initialRight.toString()) }
    LaunchedEffect(show, initialLeft, initialRight) {
        if (show) {
            leftValue = initialLeft.toString()
            rightValue = initialRight.toString()
        }
    }

    WindowDialog(title = title, show = show, onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth()) {
            val filter = { text: String ->
                if (text == "-" || text.isEmpty()) true
                else text.toIntOrNull() != null
            }
            TextField(
                value = leftValue,
                onValueChange = { if (filter(it)) leftValue = it },
                label = stringResource(id = R.string.label_left_padding_range),
                useLabelAsPlaceholder = false,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
                maxLines = 1
            )
            Spacer(modifier = Modifier.height(12.dp))
            TextField(
                value = rightValue,
                onValueChange = { if (filter(it)) rightValue = it },
                label = stringResource(id = R.string.label_right_padding_range),
                useLabelAsPlaceholder = false,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
                maxLines = 1
            )
            Spacer(modifier = Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                TextButton(text = stringResource(id = R.string.cancel), onClick = onDismiss, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(20.dp))
                TextButton(
                    text = stringResource(id = R.string.confirm),
                    onClick = {
                        val l = leftValue.toIntOrNull() ?: 0
                        val r = rightValue.toIntOrNull() ?: 0
                        onConfirm(l.coerceIn(-50, 100), r.coerceIn(-50, 100))
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary()
                )
            }
        }
    }
}

@Composable
fun SingleChoiceDialog(
    show: Boolean,
    title: String,
    options: List<String>,
    selectedIndex: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    var currentSelection by remember { mutableStateOf(selectedIndex) }
    LaunchedEffect(show, selectedIndex) {
        if (show) currentSelection = selectedIndex
    }

    WindowDialog(title = title, show = show, onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, option ->
                RadioButtonPreference(
                    title = option,
                    selected = currentSelection == index,
                    onClick = { currentSelection = index }
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                TextButton(
                    text = stringResource(id = R.string.cancel),
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(20.dp))
                TextButton(
                    text = stringResource(id = R.string.confirm),
                    onClick = {
                        onConfirm(currentSelection)
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary()
                )
            }
        }
    }
}

@Composable
fun CleanupHistoryDialog(
    show: Boolean,
    records: List<LogManager.CleanupRecord>,
    onDismiss: () -> Unit
) {
    val timeFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()) }

    WindowDialog(
        title = stringResource(id = R.string.cleanup_history_title),
        show = show,
        onDismissRequest = onDismiss
    ) {
        if (records.isEmpty()) {
            Text(
                text = stringResource(id = R.string.cleanup_history_empty),
                color = MiuixTheme.colorScheme.disabledOnSecondaryVariant,
                fontSize = 14.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 24.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp)
            ) {
                items(records, key = { it.timestamp }) { record ->
                    val triggerLabel = when (record.trigger) {
                        LogManager.TRIGGER_SCHEDULED -> stringResource(id = R.string.cleanup_trigger_scheduled)
                        else -> stringResource(id = R.string.cleanup_trigger_manual)
                    }
                    val allSuccess = record.files.isNotEmpty() && record.files.all { it.success }
                    val allFailed = record.files.isNotEmpty() && record.files.all { !it.success }
                    val failedFiles = record.files.filter { !it.success }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp)
                    ) {
                        // 清理时间
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(id = R.string.cleanup_time_label),
                                fontSize = 12.sp,
                                color = MiuixTheme.colorScheme.onSecondaryVariant
                            )
                            Text(
                                text = timeFormat.format(Date(record.timestamp)),
                                fontSize = 12.sp,
                                color = MiuixTheme.colorScheme.onSurface
                            )
                        }
                        // 文件路径：显示真实路径，多个文件用逗号分隔
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.Top
                        ) {
                            Text(
                                text = stringResource(id = R.string.cleanup_file_path),
                                fontSize = 12.sp,
                                color = MiuixTheme.colorScheme.onSecondaryVariant,
                                modifier = Modifier.padding(end = 8.dp)
                            )
                            val pathText = if (record.files.isEmpty()) {
                                "-"
                            } else {
                                record.files.joinToString(separator = ", ") {
                                    "...${it.path.substringAfterLast('/')}"
                                }
                            }
                            Text(
                                text = pathText,
                                fontSize = 12.sp,
                                color = MiuixTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f, fill = false),
                                textAlign = TextAlign.End,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        // 状态与触发来源合并显示
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.Top
                        ) {
                            Text(
                                text = stringResource(id = R.string.cleanup_status_label),
                                fontSize = 12.sp,
                                color = MiuixTheme.colorScheme.onSecondaryVariant,
                                modifier = Modifier.padding(end = 8.dp)
                            )
                            val statusText = when {
                                record.files.isEmpty() -> stringResource(
                                    id = R.string.cleanup_status_no_files,
                                    triggerLabel
                                )
                                allSuccess -> stringResource(
                                    id = R.string.cleanup_status_all_success,
                                    triggerLabel
                                )
                                allFailed -> stringResource(
                                    id = R.string.cleanup_status_all_failed,
                                    triggerLabel
                                )
                                else -> stringResource(
                                    id = R.string.cleanup_status_partial_failed,
                                    triggerLabel,
                                    failedFiles.joinToString(", ") { "...${it.path.substringAfterLast('/')}" }
                                )
                            }
                            val statusColor = if (allSuccess) {
                                Color(0xFF4CAF50)
                            } else {
                                Color(0xFFF44336)
                            }
                            Text(
                                text = statusText,
                                fontSize = 12.sp,
                                color = statusColor,
                                modifier = Modifier.weight(1f, fill = false),
                                textAlign = TextAlign.End,
                                maxLines = 4,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        // 失败文件与可行解决方案
                        if (failedFiles.isNotEmpty()) {
                            val failedNames = failedFiles.joinToString(", ") {
                                "...${it.path.substringAfterLast('/')}"
                            }
                            Text(
                                text = stringResource(
                                    id = R.string.cleanup_status_failed_files,
                                    failedNames
                                ),
                                fontSize = 11.sp,
                                color = Color(0xFFF44336),
                                modifier = Modifier.padding(top = 4.dp)
                            )
                            Text(
                                text = stringResource(id = R.string.cleanup_failure_solution),
                                fontSize = 11.sp,
                                color = MiuixTheme.colorScheme.disabledOnSecondaryVariant,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        Row(
            horizontalArrangement = Arrangement.End,
            modifier = Modifier.fillMaxWidth()
        ) {
            TextButton(
                text = stringResource(id = R.string.confirm),
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColorsPrimary()
            )
        }
    }
}
