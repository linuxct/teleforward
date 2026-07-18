package space.linuxct.teleforward.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure tests for the action codec and remote-button selection.
 *
 * The fixtures mirror flag combinations captured from real device dumps, because the whole design
 * hinges on them: almost every app reports `semanticAction = 0`, and almost every action is
 * immutable — neither of which prevents firing it.
 */
class NotificationActionsTest {

    /**
     * The English wording, as `strings.xml` defines it.
     *
     * The labels are passed in rather than read from resources precisely so these tests can stay pure
     * JVM tests. Asserting against a fixture also means a *translation* can never break them — only a
     * change to the button-selection logic itself can, which is what they are actually about.
     */
    private companion object {
        val LABELS = ButtonLabels(
            dismiss = "🗑 Dismiss",
            playPause = "⏯ Play/Pause",
            replyFallback = "Reply",
            actionFallback = "Action",
        )
    }

    // --- fixtures taken from real captures -----------------------------------------------------

    /** WhatsApp: sem=1, has RemoteInput, MUTABLE — the one action we can inject text into. */
    private val waReply = NotificationActionInfo(
        index = 0,
        title = "Reply",
        semantic = NotificationActionInfo.SEMANTIC_REPLY,
        remoteInput = true,
        immutable = false,
    )

    /** WhatsApp: sem=2, immutable, background service. */
    private val waMarkRead = NotificationActionInfo(
        index = 1,
        title = "Mark as read",
        semantic = NotificationActionInfo.SEMANTIC_MARK_AS_READ,
        immutable = true,
    )

    /** WhatsApp: sem=0, immutable, ACTIVITY — firing it opens the app. */
    private val waMute =
        NotificationActionInfo(index = 2, title = "Mute", immutable = true, opensApp = true)

    /** K-9 Mail: sem=0, immutable, activity. */
    private val k9Reply =
        NotificationActionInfo(index = 0, title = "Reply", immutable = true, opensApp = true)

    /** K-9 Mail: sem=0, immutable, background service — fires silently. */
    private val k9MarkRead = NotificationActionInfo(index = 1, title = "Mark Read", immutable = true)
    private val k9Delete = NotificationActionInfo(index = 2, title = "Delete", immutable = true)

    // --- codec ---------------------------------------------------------------------------------

    @Test
    fun encodeDecodeRoundTrips() {
        val actions = listOf(waReply, waMarkRead, waMute)
        assertEquals(actions, NotificationActions.decode(NotificationActions.encode(actions)))
    }

    @Test
    fun encodeIsNullForNoActions() {
        assertNull(NotificationActions.encode(emptyList()))
    }

    @Test
    fun decodeIsEmptyForNullOrGarbage() {
        assertTrue(NotificationActions.decode(null).isEmpty())
        assertTrue(NotificationActions.decode("").isEmpty())
        assertTrue(NotificationActions.decode("not json").isEmpty())
        assertTrue(NotificationActions.decode("""{"unexpected":true}""").isEmpty())
    }

    @Test
    fun decodesRowsWrittenBeforeOpensAppExisted() {
        // Older rows have no `opensApp` field; they must decode with a safe default, not blow up.
        val legacy = """[{"index":0,"title":"Mark Read","immutable":true}]"""
        val decoded = NotificationActions.decode(legacy).single()
        assertEquals("Mark Read", decoded.title)
        assertEquals(false, decoded.opensApp)
    }

    // --- WhatsApp must not regress -------------------------------------------------------------

    @Test
    fun whatsAppKeepsReplyMarkReadAndDismiss() {
        val buttons = remoteButtons(listOf(waReply, waMarkRead, waMute), LABELS)
        assertEquals(
            listOf(
                RemoteActionKind.REPLY,
                RemoteActionKind.FIRE,
                RemoteActionKind.FIRE,
                RemoteActionKind.DISMISS,
            ),
            buttons.map { it.kind },
        )
        // Reply still targets the real reply action, by index AND semantic.
        val reply = buttons.first { it.kind == RemoteActionKind.REPLY }
        assertEquals(0, reply.actionIndex)
        assertEquals(NotificationActionInfo.SEMANTIC_REPLY, reply.semantic)
        // Mark-as-read keeps its index/semantic so it fires exactly as before.
        val markRead = buttons[1]
        assertEquals(1, markRead.actionIndex)
        assertEquals(NotificationActionInfo.SEMANTIC_MARK_AS_READ, markRead.semantic)
        assertEquals("✅ Mark as read", markRead.label)
    }

    // --- generic support for everything else ---------------------------------------------------

    @Test
    fun k9MailGetsItsRealActions() {
        // Every K-9 action reports semantic 0 and is immutable — under semantic-only curation this
        // app got nothing but Dismiss, even though these actions fire perfectly.
        val buttons = remoteButtons(listOf(k9Reply, k9MarkRead, k9Delete), LABELS)
        assertEquals(
            listOf("Reply ↗", "Mark Read", "Delete", "🗑 Dismiss"),
            buttons.map { it.label },
        )
        assertEquals(
            listOf(
                RemoteActionKind.FIRE,
                RemoteActionKind.FIRE,
                RemoteActionKind.FIRE,
                RemoteActionKind.DISMISS,
            ),
            buttons.map { it.kind },
        )
    }

    @Test
    fun activityActionsAreMarkedAsOpeningTheApp() {
        // The marker is the honest signal that this won't act silently.
        assertTrue(remoteButtons(listOf(k9Reply), LABELS).first().label.endsWith(OPENS_APP_MARKER))
        assertTrue(remoteButtons(listOf(waMute), LABELS).first().label.endsWith(OPENS_APP_MARKER))
        // A background action carries no marker.
        assertEquals("Mark Read", remoteButtons(listOf(k9MarkRead), LABELS).first().label)
    }

    @Test
    fun mediaTransportControlsAreOffered() {
        val media = listOf(
            NotificationActionInfo(index = 0, title = "Rewind to previous item", immutable = true),
            NotificationActionInfo(index = 1, title = "Pause", immutable = true),
            NotificationActionInfo(index = 2, title = "Forward to next item", immutable = true),
        )
        val buttons = remoteButtons(media, LABELS)
        assertEquals(4, buttons.size)
        // Long labels are truncated so the keyboard stays readable.
        assertTrue(buttons[0].label.length <= 22)
        assertTrue(buttons[0].label.endsWith("…"))
        assertEquals("Pause", buttons[1].label)
    }

    @Test
    fun appleMusicGetsItsTransportControls() {
        // The real captured action list: eight controls, every one semantic=0 + immutable + service.
        // Immutability is irrelevant for firing, so all of them work remotely.
        val appleMusic = listOf(
            "Rewind to previous item", "Pause", "Forward to next item", "Favourite",
            "Shuffle", "Repeat", "Autoplay", "Create Station",
        ).mapIndexed { index, title ->
            NotificationActionInfo(index = index, title = title, immutable = true)
        }

        val buttons = remoteButtons(appleMusic, LABELS)
        assertEquals(
            listOf(
                "Rewind to previous it…", // truncated to keep the keyboard readable
                "Pause",
                "Forward to next item",
                "Favourite",
                "Shuffle",
                "Repeat",
                "🗑 Dismiss",
            ),
            buttons.map { it.label },
        )
        // None open the app — they all act silently, which is what makes remote control useful.
        assertTrue(buttons.none { it.label.endsWith(OPENS_APP_MARKER) })
    }

    @Test
    fun youtubeSeparatesSilentActionsFromAppOpeningOnes() {
        // Real captured flags: Play/Turn Off are activities, but "Watch Later" is a BROADCAST — so it
        // fires silently and genuinely works from Telegram without touching the phone.
        val youtube = listOf(
            NotificationActionInfo(index = 0, title = "Play", immutable = true, opensApp = true),
            NotificationActionInfo(index = 1, title = "Turn Off", immutable = true, opensApp = true),
            NotificationActionInfo(index = 2, title = "Watch Later", immutable = true),
        )

        val buttons = remoteButtons(youtube, LABELS)
        assertEquals(
            listOf("Play ↗", "Turn Off ↗", "Watch Later", "🗑 Dismiss"),
            buttons.map { it.label },
        )
        // The silent one is the one worth pressing remotely, and it carries no marker.
        assertEquals("Watch Later", buttons[2].label)
        assertEquals(2, buttons[2].actionIndex)
    }

    @Test
    fun tooManyActionsAreCapped() {
        // Media notifications expose up to nine actions; the keyboard must stay usable.
        val many = (0 until 9).map { NotificationActionInfo(index = it, title = "A$it", immutable = true) }
        val buttons = remoteButtons(many, LABELS)
        assertEquals(7, buttons.size) // 6 actions + Dismiss
        assertEquals(RemoteActionKind.DISMISS, buttons.last().kind)
    }

    @Test
    fun unfillableReplyActionIsSkipped() {
        // A RemoteInput on an immutable intent can't receive text; firing it bare could send an empty
        // message, so it is dropped rather than offered.
        val immutableReply = waReply.copy(immutable = true)
        assertEquals(
            listOf(RemoteActionKind.DISMISS),
            remoteButtons(listOf(immutableReply), LABELS).map { it.kind },
        )
    }

    // --- media / now-playing --------------------------------------------------------------------

    @Test
    fun detectsMediaNotifications() {
        assertTrue(isMediaNotification(category = "transport", template = null))
        assertTrue(
            isMediaNotification(category = null, template = "android.app.Notification\$MediaStyle"),
        )
        assertEquals(false, isMediaNotification(category = "msg", template = null))
        assertEquals(false, isMediaNotification(category = null, template = null))
    }

    @Test
    fun detectsAnyThirdPartyPlayer() {
        // Detection is by shape, never by package, so any player is picked up. A media session token
        // alone is enough for a player that sets neither a transport category nor a known template.
        assertTrue(isMediaNotification(category = null, template = null, hasMediaSession = true))
        // androidx MediaStyle renders under a different class name but still matches.
        assertTrue(
            isMediaNotification(
                category = null,
                template = "androidx.media.app.NotificationCompat\$MediaStyle",
            ),
        )
        // A plain chat notification with no media signal at all is still not media.
        assertEquals(
            false,
            isMediaNotification(category = "msg", template = null, hasMediaSession = false),
        )
    }

    @Test
    fun oneShotMediaNamesTheToggleInsteadOfTheState() {
        // A forwarded media notification never updates, so "Pause" would be wrong the moment it is
        // pressed — pressing it again would play. All three spellings collapse to one honest label.
        listOf("Pause", "Play", "Play/Pause").forEach { title ->
            val actions = listOf(NotificationActionInfo(index = 1, title = title, immutable = true))
            assertEquals(
                "'$title' should be relabelled",
                LABELS.playPause,
                remoteButtons(actions, LABELS, includeDismiss = false, stableLabels = true).first().label,
            )
        }
        // The action it targets is unchanged — only the wording differs.
        val buttons = remoteButtons(
            listOf(NotificationActionInfo(index = 1, title = "Pause", immutable = true)),
            LABELS,
            includeDismiss = false,
            stableLabels = true,
        )
        assertEquals(1, buttons.first().actionIndex)
    }

    @Test
    fun theLiveStateLabelIsOnlyKeptWhenNotAskedForStableOnes() {
        // Both media paths (one-shot forward AND the now-playing control) pass stableLabels = true, so
        // this branch is what a NON-media notification with a "Pause"-titled action would get. Kept as
        // the negative half of the pair: the relabelling must be driven by the flag, not sniffed.
        val actions = listOf(NotificationActionInfo(index = 1, title = "Pause", immutable = true))
        assertEquals(
            "Pause",
            remoteButtons(actions, LABELS, includeDismiss = false, stableLabels = false).first().label,
        )
    }

    @Test
    fun otherMediaLabelsAreLeftAlone() {
        // Only the play/pause toggle flips underneath us; the rest keep the app's own wording.
        val actions = listOf(
            NotificationActionInfo(index = 0, title = "Next", immutable = true),
            NotificationActionInfo(index = 1, title = "Shuffle", immutable = true),
        )
        assertEquals(
            listOf("Next", "Shuffle"),
            remoteButtons(actions, LABELS, includeDismiss = false, stableLabels = true).map { it.label },
        )
    }

    @Test
    fun shuffleAndRepeatKeepThePlayersOwnWording() {
        // No ON/OFF suffix: the notification carries no shuffle/repeat state at all (identical titles
        // and icons in both states across every captured sample), so anything we appended would be a
        // guess. The player's own label is the only honest thing to show.
        val actions = listOf(
            NotificationActionInfo(index = 0, title = "Shuffle", immutable = true),
            NotificationActionInfo(index = 1, title = "Repeat", immutable = true),
        )
        assertEquals(
            listOf("Shuffle", "Repeat"),
            remoteButtons(actions, LABELS, includeDismiss = false).map { it.label },
        )
    }

    @Test
    fun mediaControlsOmitDismiss() {
        // Media notifications are NO_CLEAR + foreground-service; the system refuses to let a listener
        // cancel them, so a Dismiss button would silently do nothing. Their own "Stop" is the answer.
        val youtubePlayback = listOf(
            NotificationActionInfo(index = 0, title = "Previous", immutable = true),
            NotificationActionInfo(index = 1, title = "Pause", immutable = true),
            NotificationActionInfo(index = 2, title = "Next", immutable = true),
            NotificationActionInfo(index = 3, title = "Stop", immutable = true),
        )
        val buttons = remoteButtons(youtubePlayback, LABELS, includeDismiss = false)
        assertEquals(listOf("Previous", "Pause", "Next", "Stop"), buttons.map { it.label })
        assertTrue(buttons.none { it.kind == RemoteActionKind.DISMISS })
        // Index-addressed, because the label at index 1 flips between Play and Pause.
        assertEquals(1, buttons[1].actionIndex)
    }

    @Test
    fun dismissIsAlwaysOffered() {
        val buttons = remoteButtons(emptyList(), LABELS)
        assertEquals(listOf(RemoteActionKind.DISMISS), buttons.map { it.kind })
        assertEquals(RemoteActionButton.NO_ACTION, buttons.single().actionIndex)
    }
}
