package com.airmesh.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Profile UI: the avatar circle used everywhere, the first-run "Choose your name"
 * dialog, and the profile bottom sheet. Every value shown here is derived by the
 * pure helpers in ProfileUiHelpers.kt; this file only lays them out.
 */

/** One-time explanatory line shared by onboarding and the profile sheet. */
internal const val PROFILE_PRIVACY_TEXT = "Everyone in the mesh sees this name. Names are not verified."

/**
 * Solid avatar circle with initials. The fill comes from the 8-entry palette and
 * the text stays white; the font size is density-based (not fontScale-based) so
 * a large system font never bursts the circle.
 */
@Composable
fun AvatarCircle(
    initials: String,
    colorIndex: Int,
    size: Dp,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    val fontSize = with(LocalDensity.current) { (size * 0.38f).toSp() }
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(Color(avatarColor(colorIndex)))
            .then(
                if (contentDescription == null) Modifier
                else Modifier.semantics { this.contentDescription = contentDescription },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initials,
            color = Color.White,
            fontSize = fontSize,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Clip,
        )
    }
}

/** Top-bar entry point: my initials in my color; 48dp touch target. */
@Composable
fun ProfileAvatarButton(
    name: String,
    colorIndex: Int,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, modifier = Modifier.size(48.dp)) {
        AvatarCircle(
            initials = avatarInitials(name),
            colorIndex = colorIndex,
            size = 32.dp,
            contentDescription = "Your profile",
        )
    }
}

/**
 * First-run dialog: name field with a live 0/24 counter, the sanitized preview,
 * 8 color swatches and Save disabled until the name survives sanitizing. Skip
 * leaves the node anonymous, so every list shows "User #id".
 */
@Composable
internal fun OnboardingDialog(
    nodeId: Int,
    initialName: String = "",
    initialColorIndex: Int = 0,
    onSave: (name: String, colorIndex: Int) -> Unit,
    onSkip: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(initialName) }
    var colorIndex by rememberSaveable { mutableIntStateOf(initialColorIndex) }
    val sanitized = previewProfileName(name)
    val canSave = fitsProfilePayload(sanitized)

    AlertDialog(
        // Dismissing the dialog is the same as Skip: never nag twice.
        onDismissRequest = onSkip,
        title = { Text("Choose your name") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(PROFILE_PRIVACY_TEXT, style = MaterialTheme.typography.bodyMedium)
                AvatarNameRow(
                    name = name,
                    onNameChange = { name = it },
                    colorIndex = colorIndex,
                )
                ColorSwatches(selected = colorIndex, onSelect = { colorIndex = it })
                Text(
                    "Skip keeps you as User #$nodeId.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(sanitized, colorIndex) }, enabled = canSave) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onSkip) { Text("Skip") }
        },
    )
}

/** Bottom sheet editor opened from the top-bar avatar (also the post-Skip entry point). */
@OptIn(ExperimentalMaterial3Api::class) // ModalBottomSheet is experimental in this M3 version
@Composable
fun ProfileSheet(
    nodeId: Int,
    initialName: String,
    initialColorIndex: Int,
    onSave: (name: String, colorIndex: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        ProfileSheetContent(
            nodeId = nodeId,
            initialName = initialName,
            initialColorIndex = initialColorIndex,
            onSave = onSave,
            onCancel = onDismiss,
        )
    }
}

/**
 * Sheet body, extracted so design-time previews can render it without a modal
 * window. [initialName] is the saved name (empty while the node is anonymous).
 */
@Composable
internal fun ProfileSheetContent(
    nodeId: Int,
    initialName: String,
    initialColorIndex: Int,
    onSave: (name: String, colorIndex: Int) -> Unit,
    onCancel: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(initialName) }
    var colorIndex by rememberSaveable { mutableIntStateOf(initialColorIndex) }
    val sanitized = previewProfileName(name)
    val canSave = fitsProfilePayload(sanitized)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(horizontal = 24.dp)
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Your profile", style = MaterialTheme.typography.titleLarge)
        Text(
            PROFILE_PRIVACY_TEXT,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        AvatarNameRow(
            name = name,
            onNameChange = { name = it },
            colorIndex = colorIndex,
            avatarSize = 48.dp,
        )
        ColorSwatches(selected = colorIndex, onSelect = { colorIndex = it })
        NodeIdRow(nodeId = nodeId)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onCancel) { Text("Cancel") }
            Spacer(Modifier.width(8.dp))
            Button(onClick = { onSave(sanitized, colorIndex) }, enabled = canSave) {
                Text("Save")
            }
        }
    }
}

/** Avatar preview next to the name field; the preview reflects the sanitized name. */
@Composable
private fun AvatarNameRow(
    name: String,
    onNameChange: (String) -> Unit,
    colorIndex: Int,
    avatarSize: Dp = 40.dp,
) {
    val sanitized = previewProfileName(name)
    Row(verticalAlignment = Alignment.Top) {
        AvatarCircle(
            initials = avatarInitials(sanitized.ifEmpty { "?" }),
            colorIndex = colorIndex,
            size = avatarSize,
            contentDescription = null,
        )
        Spacer(Modifier.width(12.dp))
        ProfileNameField(
            name = name,
            onNameChange = onNameChange,
            modifier = Modifier.weight(1f),
        )
    }
}

/** Name field with the live counter, the sanitized preview and the byte warning. */
@Composable
private fun ProfileNameField(
    name: String,
    onNameChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val preview = previewProfileName(name)
    val warning = profileNameByteWarning(name)
    Column(modifier = modifier) {
        OutlinedTextField(
            value = name,
            // Clamp at the payload code-point cap so the counter never runs past 24.
            onValueChange = { onNameChange(capProfileNameInput(it)) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Display name") },
            singleLine = true,
            isError = warning != null,
            supportingText = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(profileNameCounter(name))
                    Text(
                        text = if (preview.isEmpty()) "Nothing to show yet" else "Shows as \u201C$preview\u201D",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
            },
        )
        if (warning != null) {
            Text(
                warning,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/** Two rows of four swatches: 48dp touch targets that still fit a dialog. */
@Composable
private fun ColorSwatches(selected: Int, onSelect: (colorIndex: Int) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (rowStart in 0 until AVATAR_COLOR_COUNT step 4) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (index in rowStart until minOf(rowStart + 4, AVATAR_COLOR_COUNT)) {
                    val isSelected = index == selected
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .selectable(
                                selected = isSelected,
                                role = Role.RadioButton,
                                onClick = { onSelect(index) },
                            )
                            .semantics { contentDescription = "Color ${index + 1}" },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(Color(avatarColor(index)))
                                .border(
                                    width = if (isSelected) 3.dp else 1.dp,
                                    color = if (isSelected) colors.onSurface else colors.outlineVariant,
                                    shape = CircleShape,
                                ),
                        )
                    }
                }
            }
        }
    }
}

/** Node id with a copy button: the id never changes, so it is safe to share. */
@Composable
private fun NodeIdRow(nodeId: Int) {
    // LocalClipboardManager is deprecated in favor of LocalClipboard, but the new API
    // needs platform ClipData/ClipEntry construction for a one-line text write.
    @Suppress("DEPRECATION")
    val clipboardManager = LocalClipboardManager.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "Node id",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("#$nodeId", style = MaterialTheme.typography.titleMedium)
        }
        TextButton(onClick = { clipboardManager.setText(AnnotatedString(nodeId.toString())) }) {
            Text("Copy")
        }
    }
}
