package space.bitos.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NotificationGroupingTest {

    private val now = 1_700_000_000L // 2023-11-14T22:13:20Z
    private val todayStart = NotificationSections.epochDayOf(now) * NotificationSections.SECONDS_PER_DAY

    private fun item(
        id: String,
        kind: NotificationKind,
        author: String,
        target: String? = null,
        offsetSeconds: Long = 0,
    ) = NotificationItem(
        id = id,
        authorPubkey = author,
        kind = kind,
        targetEventId = target,
        summary = "s-$id",
        createdAt = todayStart + offsetSeconds,
    )

    @Test
    fun aggregatesSameKindSameTargetAcrossActors() {
        val sections = NotificationSections.sections(
            listOf(
                item("a", NotificationKind.REACTION, "pk1", "note1", 100),
                item("b", NotificationKind.REACTION, "pk2", "note1", 200),
                item("c", NotificationKind.REACTION, "pk3", "note1", 300),
                item("d", NotificationKind.REACTION, "pk1", "note2", 50), // different target
            ),
            now,
        )
        assertEquals(1, sections.size)
        val groups = sections[0].groups
        assertEquals(2, groups.size)
        val note1 = groups.first { it.targetEventId == "note1" }
        assertEquals(3, note1.actorCount)
        assertEquals(listOf("pk3", "pk2", "pk1"), note1.actors) // newest actor first
        assertEquals(listOf("c", "b", "a"), note1.itemIds)
        assertEquals("a", note1.id) // oldest item id is the stable group id
        assertEquals(300 + todayStart, note1.newestAt)
    }

    @Test
    fun sameActorRepeatedAndDifferentKindStaySeparate() {
        val sections = NotificationSections.sections(
            listOf(
                item("a", NotificationKind.REACTION, "pk1", "note1", 100),
                item("b", NotificationKind.REACTION, "pk1", "note1", 200), // same actor again
                item("c", NotificationKind.REPOST, "pk2", "note1", 300), // different kind
                item("d", NotificationKind.ZAP, "", "note1", 400), // zap server key
            ),
            now,
        )
        val groups = sections[0].groups
        assertEquals(3, groups.size)
        val reactions = groups.first { it.kind == NotificationKind.REACTION }
        assertEquals(1, reactions.actorCount) // distinct actors
        assertEquals(2, reactions.itemIds.size)
        val zaps = groups.first { it.kind == NotificationKind.ZAP }
        assertEquals(0, zaps.actorCount) // zap receipts carry server keys; count = receipts
        assertEquals(1, zaps.itemIds.size)
    }

    @Test
    fun followsRepliesAndMentionsNeverAggregate() {
        val sections = NotificationSections.sections(
            listOf(
                item("f1", NotificationKind.FOLLOW, "pk1", null, 100),
                item("f2", NotificationKind.FOLLOW, "pk2", null, 200),
                item("r1", NotificationKind.REPLY, "pk3", "note1", 300),
                item("r2", NotificationKind.REPLY, "pk4", "note1", 400),
                item("m1", NotificationKind.MENTION, "pk5", null, 500),
                item("m2", NotificationKind.MENTION, "pk6", null, 600),
            ),
            now,
        )
        assertEquals(6, sections[0].groups.size)
        assertTrue(sections[0].groups.all { it.itemIds.size == 1 })
    }

    @Test
    fun zapGroupsSumAmountsAndCountReceipts() {
        val sections = NotificationSections.sections(
            listOf(
                item("z1", NotificationKind.ZAP, "server1", "note1", 100).copy(amountMsat = 21_000),
                item("z2", NotificationKind.ZAP, "server2", "note1", 200).copy(amountMsat = 1_000),
                item("z3", NotificationKind.ZAP, "server3", "note2", 300), // different target
            ),
            now,
        )
        val note1 = sections[0].groups.first { it.targetEventId == "note1" }
        assertEquals(22_000L, note1.totalMsat)
        assertEquals(2, note1.itemIds.size)
        val note2 = sections[0].groups.first { it.targetEventId == "note2" }
        assertEquals(0L, note2.totalMsat)
    }

    @Test
    fun dayBoundariesSplitSectionsAndOrderNewestFirst() {
        val yesterdayEnd = todayStart - 1
        val sections = NotificationSections.sections(
            listOf(
                item("y1", NotificationKind.REACTION, "pk1", "note1").copy(createdAt = yesterdayEnd),
                item("t1", NotificationKind.REACTION, "pk2", "note1", offsetSeconds = 1),
                item("t2", NotificationKind.REACTION, "pk3", "note2", offsetSeconds = 2),
            ),
            now,
        )
        assertEquals(2, sections.size)
        assertEquals(NotificationSections.epochDayOf(now), sections[0].epochDay)
        assertEquals(NotificationSections.epochDayOf(now) - 1, sections[1].epochDay)
        // Newest group first within a section.
        assertEquals("t2", sections[0].groups[0].id)
        assertEquals("t1", sections[0].groups[1].id)
        assertEquals("y1", sections[1].groups[0].id)
    }

    @Test
    fun dropsItemsOlderThanTheSectionWindow() {
        val ancient = todayStart - (NotificationSections.MAX_SECTIONS + 2) * NotificationSections.SECONDS_PER_DAY
        val sections = NotificationSections.sections(
            listOf(item("old", NotificationKind.REACTION, "pk1", "note1").copy(createdAt = ancient)),
            now,
        )
        assertEquals(0, sections.size)
    }

    @Test
    fun actorDisplayListIsCappedButCountIsTotal() {
        val items = (1..6).map { index ->
            item("i$index", NotificationKind.REACTION, "pk$index", "note1", offsetSeconds = index.toLong())
        }
        val sections = NotificationSections.sections(items, now)
        val group = sections[0].groups.single()
        assertEquals(6, group.actorCount)
        assertEquals(NotificationSections.MAX_ACTORS_SHOWN, group.actors.size)
    }
}
