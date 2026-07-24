package com.beauty.app.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
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
        database.clientDao().insertClient(
            ClientEntity("client-1", "Ada", "+100", null, "[]", "{}", 0)
        )
        database.visitDao().insertVisit(
            VisitEntity("local-1", null, "client-1", "2026-07-24T10:00:00", 45, "Treatment", "COMPLETED", true)
        )

        assertEquals(listOf("local-1"), database.visitDao().getUnsyncedVisits().map { it.id })

        database.visitDao().markVisitSynced("local-1", "remote-1")

        assertEquals(emptyList<String>(), database.visitDao().getUnsyncedVisits().map { it.id })
    }
}
