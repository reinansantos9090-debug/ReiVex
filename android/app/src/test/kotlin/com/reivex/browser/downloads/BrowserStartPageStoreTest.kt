package com.reivex.browser.downloads

import android.content.Context
import android.graphics.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BrowserStartPageStoreTest {
    private lateinit var store: BrowserStartPageStore

    @Before fun setUp() {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("reivex.start_page", Context.MODE_PRIVATE).edit().clear().commit()
        store = BrowserStartPageStore(app)
    }

    @Test fun visitsAreRealLocalHistoryNotPlaceholderLinks() {
        assertTrue(store.recents().isEmpty())
        assertTrue(store.favorites().isEmpty())
        store.recordVisit("https://example.com/a", "Primeira página")
        store.recordVisit("https://example.com/b", "Segunda página")
        store.recordVisit("https://example.com/a", "Título atualizado")
        assertEquals(listOf("https://example.com/a", "https://example.com/b"),
            store.recents().map { it.url })
        assertEquals("Título atualizado", store.recents()[0].title)
    }

    @Test fun favoritesPersistAndCanBeRemoved() {
        val entry = BrowserStartLink("https://example.com", "Exemplo")
        store.toggleFavorite(entry)
        assertEquals(listOf(entry), BrowserStartPageStore(RuntimeEnvironment.getApplication()).favorites())
        store.toggleFavorite(entry)
        assertTrue(store.favorites().isEmpty())
    }

    @Test fun onlyHttpSitesAreStoredAndRecentsCanBeCleared() {
        store.recordVisit("javascript:alert(1)", "Inseguro")
        store.recordVisit("file:///tmp/example", "Local")
        store.recordVisit("https://example.com", "Exemplo")
        assertEquals(1, store.recents().size)
        store.removeRecent("https://example.com")
        assertTrue(store.recents().isEmpty())
        store.recordVisit("https://example.com", "Exemplo")
        store.clearRecents()
        assertFalse(store.recents().isNotEmpty())
    }

    @Test fun clearingBrowsingHistoryPreservesFavorites() {
        val favorite=BrowserStartLink("https://example.com/favorite","Favorito")
        store.toggleFavorite(favorite)
        store.recordVisit("https://example.com/recent","Visita")
        store.saveFavicon("https://example.com/recent",Bitmap.createBitmap(8,8,Bitmap.Config.ARGB_8888))
        assertTrue(store.favicon("https://example.com/recent")!=null)

        store.clearRecents()
        store.clearFavicons()

        assertTrue(store.recents().isEmpty())
        assertEquals(listOf(favorite),store.favorites())
        assertTrue(store.favicon("https://example.com/recent")==null)
    }
}
