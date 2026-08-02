package com.beauty.app.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VisitDaoTest {
    private lateinit var database: BeautyDatabase

    @Before
    fun createDatabase() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            BeautyDatabase::class.java
        ).allowMainThreadQueries().build()
    }

    @After
    fun closeDatabase() = database.close()

    @Test
    fun pendingVisitsAreSelectedAndSyncUpdateRemovesThemFromQueue() = runBlocking {
        database.clientDao().insertClient(client("client-1", ORG_A))
        database.visitDao().insertVisit(visit("local-1", "client-1", ORG_A))

        assertEquals(listOf("local-1"), database.visitDao().getUnsyncedVisits().map { it.id })

        database.visitDao().markVisitSynced("local-1", "remote-1")

        assertEquals(emptyList<String>(), database.visitDao().getUnsyncedVisits().map { it.id })
    }

    /**
     * The cache holds rows for every organization the device has seen, so the
     * directory query has to separate them. If it ever stops doing so, one
     * salon's clients appear under another's name — the exact failure
     * multi-tenancy exists to prevent, and one that a purely server-side test
     * cannot catch.
     */
    @Test
    fun clientsAreVisibleOnlyToTheirOwnOrganization() = runBlocking {
        database.clientDao().insertClient(client("client-a", ORG_A))
        database.clientDao().insertClient(client("client-b", ORG_B))

        assertEquals(
            listOf("client-a"),
            database.clientDao().getAllClients(ORG_A).first().map { it.id }
        )
        assertEquals(
            listOf("client-b"),
            database.clientDao().getAllClients(ORG_B).first().map { it.id }
        )
    }

    /**
     * A snapshot describes one organization only, so "absent from it" says
     * nothing about anybody else's rows. Reconciling without the scope would
     * wipe every other cached salon on each refresh.
     */
    @Test
    fun reconcilingOneOrganizationLeavesTheOthersAlone() = runBlocking {
        database.clientDao().insertClient(client("client-a", ORG_A))
        database.clientDao().insertClient(client("client-b", ORG_B))

        database.clientDao().reconcileClients(ORG_A, listOf(client("client-a2", ORG_A)))

        assertEquals(
            listOf("client-a2"),
            database.clientDao().getAllClients(ORG_A).first().map { it.id }
        )
        assertEquals(
            listOf("client-b"),
            database.clientDao().getAllClients(ORG_B).first().map { it.id }
        )
    }

    private fun client(id: String, orgId: String) =
        ClientEntity(id, orgId, "Ada", "+100", null, "[]", "{}", 0)

    private fun visit(id: String, clientId: String, orgId: String) =
        VisitEntity(id, null, orgId, clientId, "2026-07-24T10:00:00", 45, "Treatment", "COMPLETED", true)

    private companion object {
        const val ORG_A = "org-a"
        const val ORG_B = "org-b"
    }
}
