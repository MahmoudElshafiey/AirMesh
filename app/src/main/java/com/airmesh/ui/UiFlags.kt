package com.airmesh.ui

import com.airmesh.chat.ConversationSummary

/**
 * Compile-time UI switches (TASK S3). Flipping a switch back to true restores the hidden UI:
 * nothing is deleted, only gated at its call site. Pure Kotlin — no Android imports, so every
 * rule below stays unit-testable on the JVM.
 */
object UiFlags {
    /**
     * When false, all in-app unread indicators are hidden (TASK S3): the numeric badge on each
     * conversation row, the Chats tab badge, the "N unread messages" divider and its scroll
     * target, the "New messages" chip, and the unread-driven time-label emphasis. The read/unread
     * STATE is untouched — notifications and mark-read keep working; only the indicators go away.
     */
    const val SHOW_UNREAD_UI = false
}

/** Whether a conversation row shows its numeric unread badge (TASK S3). */
fun unreadBadgeVisible(showUnreadUi: Boolean, unreadCount: Int): Boolean =
    showUnreadUi && unreadCount > 0

/** Whether the bottom-bar Chats tab shows its badge (number of conversations with unreads). */
fun chatsTabBadgeVisible(showUnreadUi: Boolean, summaries: List<ConversationSummary>): Boolean =
    showUnreadUi && chatsTabBadgeCount(summaries) > 0

/**
 * The unread divider model for the conversation screen (TASK S3): null when hidden — no divider
 * item is produced and the initial scroll lands at the bottom. With the flag on this is exactly
 * [unreadDivider].
 */
fun unreadDividerModel(
    showUnreadUi: Boolean,
    rows: List<ChatTimelineRow>,
    unreadMessageIds: Set<Long>,
): UnreadDividerInfo? = if (showUnreadUi) unreadDivider(rows, unreadMessageIds) else null

/** Whether the "N new messages" chip is shown while reading history (TASK S3). */
fun newMessagesChipVisible(showUnreadUi: Boolean, follow: Boolean, unseenCount: Int): Boolean =
    showUnreadUi && !follow && unseenCount > 0
