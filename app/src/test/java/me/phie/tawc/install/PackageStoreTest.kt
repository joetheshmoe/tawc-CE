package me.phie.tawc.install

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Catalog sanity for the in-app store ([PackageStore]): every item has a
 * non-empty package list, a detection binary, and ids are unique across the
 * two shelves.
 */
class PackageStoreTest {

    @Test
    fun allItemsHavePackagesAndBin() {
        for (item in PackageStore.all()) {
            assertTrue("${item.id} has no packages", item.packages.isNotEmpty())
            assertTrue("${item.id} has no bin", item.bin.isNotBlank())
        }
    }

    @Test
    fun idsAreUnique() {
        val ids = PackageStore.all().map { it.id }
        assertTrue(ids.size == ids.toSet().size)
    }

    @Test
    fun byIdResolvesEveryItem() {
        for (item in PackageStore.all()) {
            assertTrue(item === PackageStore.byId(item.id))
        }
    }
}
