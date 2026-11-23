package gamalprojects.autosavecontactscrm.akramalahmadi

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import gamalprojects.autosavecontactscrm.akramalahmadi.database.AppDatabase
import gamalprojects.autosavecontactscrm.akramalahmadi.database.Contact
import gamalprojects.autosavecontactscrm.akramalahmadi.managers.WhatsAppMonitoringConfig
import gamalprojects.autosavecontactscrm.akramalahmadi.presentation.matchesContactFilter
import gamalprojects.autosavecontactscrm.akramalahmadi.repositories.ContactsRepository
import gamalprojects.autosavecontactscrm.akramalahmadi.services.ContactAccessibilityService
import gamalprojects.autosavecontactscrm.akramalahmadi.services.NotificationListenerServiceImpl
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WhatsAppSeparationTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("whatsapp_monitoring_prefs", Context.MODE_PRIVATE).edit().clear().apply()
        context.getSharedPreferences("free_quota_prefs", Context.MODE_PRIVATE).edit().clear().apply()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `notification monitoring toggles operate independently for whatsapp and whatsapp business`() = runTest {
        val repo = ContactsRepository(db)
        val listener = Robolectric.buildService(NotificationListenerServiceImpl::class.java).get()
        listener.repo = repo

        // Disable regular WhatsApp notification monitoring; keep WhatsApp Business enabled
        WhatsAppMonitoringConfig.setWhatsAppNotificationMonitoringEnabled(context, false)
        WhatsAppMonitoringConfig.setWhatsAppBusinessNotificationMonitoringEnabled(context, true)

        val savedFromWa = listener.processNotificationContentSync(
            packageName = WhatsAppMonitoringConfig.PKG_WHATSAPP,
            title = "+967 777 111 222",
            text = "مرحبا من واتساب"
        )
        assertFalse(savedFromWa)

        val savedFromW4b = listener.processNotificationContentSync(
            packageName = WhatsAppMonitoringConfig.PKG_WHATSAPP_BUSINESS,
            title = "+967 777 333 444",
            text = "مرحبا من واتساب الأعمال"
        )
        assertTrue(savedFromW4b)

        val contacts = db.contactDao().getAllContactsSync()
        assertEquals(1, contacts.size)
        assertEquals(WhatsAppMonitoringConfig.SOURCE_WHATSAPP_BUSINESS, contacts[0].source)

        // Now reverse: enable regular WhatsApp and disable WhatsApp Business
        WhatsAppMonitoringConfig.setWhatsAppNotificationMonitoringEnabled(context, true)
        WhatsAppMonitoringConfig.setWhatsAppBusinessNotificationMonitoringEnabled(context, false)

        val blockedW4b = listener.processNotificationContentSync(
            packageName = WhatsAppMonitoringConfig.PKG_WHATSAPP_BUSINESS,
            title = "+967 777 555 666",
            text = "رسالة أعمال جديدة"
        )
        assertFalse(blockedW4b)

        val allowedWa = listener.processNotificationContentSync(
            packageName = WhatsAppMonitoringConfig.PKG_WHATSAPP,
            title = "+967 777 111 222",
            text = "مرحبا مجدداً"
        )
        assertTrue(allowedWa)

        val allContacts = db.contactDao().getAllContactsSync()
        assertEquals(2, allContacts.size)
        assertNotNull(allContacts.find { it.source == WhatsAppMonitoringConfig.SOURCE_WHATSAPP })
        assertNotNull(allContacts.find { it.source == WhatsAppMonitoringConfig.SOURCE_WHATSAPP_BUSINESS })
    }

    @Test
    fun `accessibility capture respects independent app toggles while preserving dialer`() = runTest {
        val repo = ContactsRepository(db)
        val accessService = Robolectric.buildService(ContactAccessibilityService::class.java).get()
        accessService.repo = repo

        WhatsAppMonitoringConfig.setWhatsAppAccessibilityMonitoringEnabled(context, true)
        WhatsAppMonitoringConfig.setWhatsAppBusinessAccessibilityMonitoringEnabled(context, false)

        val w4bSkipped = accessService.processAccessibilityTextsSync(
            packageName = WhatsAppMonitoringConfig.PKG_WHATSAPP_BUSINESS,
            texts = listOf("+967777888111")
        )
        assertEquals(0, w4bSkipped)

        val waCaptured = accessService.processAccessibilityTextsSync(
            packageName = WhatsAppMonitoringConfig.PKG_WHATSAPP,
            texts = listOf("+967777888222")
        )
        assertEquals(1, waCaptured)

        // Dialer monitoring remains unaffected when both WhatsApp accessibility flags are toggled off
        WhatsAppMonitoringConfig.setWhatsAppAccessibilityMonitoringEnabled(context, false)
        assertEquals(
            "مكالمة / الهاتف",
            WhatsAppMonitoringConfig.resolveAccessibilitySourceIfEnabled(context, "com.android.dialer")
        )
        assertNull(
            WhatsAppMonitoringConfig.resolveAccessibilitySourceIfEnabled(
                context,
                WhatsAppMonitoringConfig.PKG_WHATSAPP
            )
        )
        assertNull(
            WhatsAppMonitoringConfig.resolveAccessibilitySourceIfEnabled(
                context,
                WhatsAppMonitoringConfig.PKG_WHATSAPP_BUSINESS
            )
        )
    }

    @Test
    fun `contacts source filter separates whatsapp and whatsapp business contacts cleanly`() {
        val waContact = Contact(
            name = "عميل 1",
            phone = "967777100100",
            originalPhone = "+967777100100",
            source = WhatsAppMonitoringConfig.SOURCE_WHATSAPP,
            isSyncedToPhone = true
        )
        val w4bContact = Contact(
            name = "عميل 2",
            phone = "967777200200",
            originalPhone = "+967777200200",
            source = WhatsAppMonitoringConfig.SOURCE_WHATSAPP_BUSINESS,
            isSyncedToPhone = true
        )
        val smsContact = Contact(
            name = "عميل 3",
            phone = "967777300300",
            originalPhone = "+967777300300",
            source = "رسالة SMS",
            isSyncedToPhone = true
        )

        val list = listOf(waContact, w4bContact, smsContact)
        assertEquals(3, list.filter { matchesContactFilter(it, "الكل") }.size)
        assertEquals(listOf(waContact), list.filter { matchesContactFilter(it, "واتساب") })
        assertEquals(listOf(w4bContact), list.filter { matchesContactFilter(it, "واتساب الأعمال") })
        assertEquals(listOf(smsContact), list.filter { matchesContactFilter(it, "رسالة SMS") })
    }
}
