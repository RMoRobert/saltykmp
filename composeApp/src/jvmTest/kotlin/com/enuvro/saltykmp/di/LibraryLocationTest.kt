package com.enuvro.saltykmp.di

import com.enuvro.saltykmp.db.SALTY_DB_FILE
import com.enuvro.saltykmp.db.SALTY_LIBRARY_DIR
import com.enuvro.saltykmp.db.createAppDatabase
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The per-OS mapping is host-independent path arithmetic, so all three platforms can be checked from
 * any one of them; the legacy/probe rules and the fresh-folder behaviour run against real temp
 * directories. Nothing here touches the host's actual home, Documents, or preferences.
 */
class LibraryLocationTest {

    private val home = "/home/cook"
    private val temp = mutableListOf<File>()

    private fun tempDir(): File = Files.createTempDirectory("salty-location").toFile().also { temp += it }

    @AfterTest
    fun cleanUp() {
        temp.forEach { it.setWritable(true, false); it.deleteRecursively() }
    }

    // ---- the default when Settings holds no location -------------------------------------------

    @Test
    fun macos_puts_the_library_in_documents() {
        assertEquals(
            File("$home/Documents/Salty"),
            platformLibraryParent(osName = "Mac OS X", home = home) { null },
        )
    }

    @Test
    fun windows_puts_the_library_in_local_app_data() {
        // Not Documents: OneDrive's Known Folder Move would put the live SQLite DB in a synced folder.
        // Compared as Files, not strings, so the separator the host would join with doesn't matter.
        val localAppData = File("""C:\Users\cook\AppData\Local""")
        assertEquals(
            File(localAppData, "Salty"),
            platformLibraryParent(osName = "Windows 11", home = home) {
                if (it == "LOCALAPPDATA") localAppData.path else null
            },
        )
    }

    @Test
    fun windows_falls_back_to_the_standard_local_app_data_path() {
        assertEquals(
            File("$home/AppData/Local/Salty"),
            platformLibraryParent(osName = "Windows 11", home = home) { null },
        )
    }

    @Test
    fun linux_follows_xdg_data_home() {
        assertEquals(
            File("/var/data/salty"),
            platformLibraryParent(osName = "Linux", home = home) { "/var/data" },
        )
    }

    @Test
    fun linux_defaults_to_local_share_when_xdg_is_unset_blank_or_relative() {
        val expected = File("$home/.local/share/salty")
        for (xdg in listOf(null, "", "   ", "relative/path")) {
            assertEquals(
                expected,
                platformLibraryParent(osName = "Linux", home = home) { xdg },
                "XDG_DATA_HOME=$xdg",
            )
        }
    }

    @Test
    fun an_unknown_os_has_no_platform_default() {
        assertNull(platformLibraryParent(osName = "SunOS", home = home) { null })
    }

    // ---- never disturb an install that is already somewhere ------------------------------------

    @Test
    fun an_install_that_already_lives_in_the_legacy_folder_stays_there() {
        // The whole reason there is no migration: an existing library is never left behind.
        val legacy = tempDir()
        File(legacy, "$SALTY_LIBRARY_DIR/$SALTY_DB_FILE").apply { parentFile.mkdirs(); createNewFile() }
        assertEquals(legacy, chooseLibraryParent(legacy, tempDir()))
    }

    @Test
    fun an_empty_legacy_folder_does_not_hold_the_default_back() {
        val legacy = tempDir().also { File(it, SALTY_LIBRARY_DIR).mkdirs() }
        val candidate = tempDir()
        assertEquals(candidate, chooseLibraryParent(legacy, candidate))
    }

    @Test
    fun a_fresh_install_gets_the_platform_folder_and_it_is_created() {
        val candidate = File(tempDir(), "Salty")
        assertEquals(candidate, chooseLibraryParent(File(tempDir(), "absent"), candidate))
        assertTrue(candidate.isDirectory)
        assertEquals(emptyList(), candidate.list()?.toList(), "the write probe must not leave anything behind")
    }

    @Test
    fun an_unwritable_platform_folder_falls_back_to_the_legacy_one() {
        // What a macOS user who denies the Documents-folder prompt ends up with.
        val legacy = File(tempDir(), ".salty")
        val readOnly = tempDir().also { it.setWritable(false, false) }
        assertEquals(legacy, chooseLibraryParent(legacy, File(readOnly, "Salty")))
    }

    @Test
    fun no_platform_default_falls_back_to_the_legacy_one() {
        val legacy = File(tempDir(), ".salty")
        assertEquals(legacy, chooseLibraryParent(legacy, null))
    }

    // ---- pointing Salty at a folder that has no library yet ------------------------------------

    @Test
    fun an_empty_folder_becomes_a_working_empty_library() {
        val bundle = libraryDirIn(tempDir())
        assertTrue(bundle.isDirectory, "the bundle has to exist before SQLite opens a file inside it")

        val db = createAppDatabase(File(bundle, SALTY_DB_FILE).absolutePath)
        assertTrue(File(bundle, SALTY_DB_FILE).isFile, "the DB file is created on demand")
        // Throws "no such table" if the schema was not applied, so an empty result IS the assertion.
        assertEquals(emptyList(), db.queriesQueries.selectAllRecipesByName().executeAsList())
    }

    @Test
    fun a_folder_that_does_not_exist_yet_is_created_along_the_way() {
        val bundle = libraryDirIn(File(tempDir(), "Recipes/Salty"))
        assertTrue(bundle.isDirectory)
    }

    @Test
    fun reopening_the_same_library_leaves_it_alone() {
        val parent = tempDir()
        createAppDatabase(File(libraryDirIn(parent), SALTY_DB_FILE).absolutePath)
        val reopened = createAppDatabase(File(libraryDirIn(parent), SALTY_DB_FILE).absolutePath)
        assertEquals(emptyList(), reopened.queriesQueries.selectAllRecipesByName().executeAsList())
    }

    // ---- what Settings reports when the user picks a folder ------------------------------------

    @Test
    fun picking_an_empty_folder_reports_a_new_library_and_creates_the_bundle() {
        val picked = tempDir()
        assertEquals(LibraryLocationOutcome.NewLibrary, prepareLibraryLocation(picked.path))
        assertTrue(File(picked, SALTY_LIBRARY_DIR).isDirectory)
    }

    @Test
    fun picking_a_folder_that_already_holds_a_library_reports_it() {
        val picked = tempDir()
        File(picked, "$SALTY_LIBRARY_DIR/$SALTY_DB_FILE").apply { parentFile.mkdirs(); createNewFile() }
        assertEquals(LibraryLocationOutcome.ExistingLibrary, prepareLibraryLocation(picked.path))
    }

    @Test
    fun picking_an_unwritable_folder_is_refused_rather_than_stored() {
        val picked = tempDir().also { it.setWritable(false, false) }
        assertEquals(LibraryLocationOutcome.Unusable, prepareLibraryLocation(picked.path))
        assertTrue(File(picked, SALTY_LIBRARY_DIR).list() == null, "nothing should have been created")
    }
}
