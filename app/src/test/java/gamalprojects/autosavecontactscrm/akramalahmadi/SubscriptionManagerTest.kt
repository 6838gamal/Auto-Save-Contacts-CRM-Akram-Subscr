package gamalprojects.autosavecontactscrm.akramalahmadi

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import gamalprojects.autosavecontactscrm.akramalahmadi.database.AppDatabase
import gamalprojects.autosavecontactscrm.akramalahmadi.database.Contact
import gamalprojects.autosavecontactscrm.akramalahmadi.managers.UserIdentityManager
import gamalprojects.autosavecontactscrm.akramalahmadi.network.ClientSyncBatchRequest
import gamalprojects.autosavecontactscrm.akramalahmadi.network.ClientSyncItem
import gamalprojects.autosavecontactscrm.akramalahmadi.network.PostgresConnectionConfig
import gamalprojects.autosavecontactscrm.akramalahmadi.network.PostgresSslMode
import gamalprojects.autosavecontactscrm.akramalahmadi.network.SubscriptionActivateRequest
import gamalprojects.autosavecontactscrm.akramalahmadi.network.SubscriptionApiResponse
import gamalprojects.autosavecontactscrm.akramalahmadi.network.SubscriptionApiService
import gamalprojects.autosavecontactscrm.akramalahmadi.network.SubscriptionConnectRequest
import gamalprojects.autosavecontactscrm.akramalahmadi.network.SubscriptionReviewRequest
import gamalprojects.autosavecontactscrm.akramalahmadi.network.SubscriptionUsageRequest
import gamalprojects.autosavecontactscrm.akramalahmadi.network.SubscriptionVerifyRequest
import gamalprojects.autosavecontactscrm.akramalahmadi.repositories.ActivationResult
import gamalprojects.autosavecontactscrm.akramalahmadi.repositories.ContactsRepository
import gamalprojects.autosavecontactscrm.akramalahmadi.repositories.DbConnectionStatus
import gamalprojects.autosavecontactscrm.akramalahmadi.repositories.ReviewRequestResult
import gamalprojects.autosavecontactscrm.akramalahmadi.repositories.SubscriptionRepository
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Response
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SubscriptionManagerTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun teardown() {
        db.close()
    }

    @Test
    fun `DATABASE_URL parser handles standard postgresql URI and ignores placeholder`() {
        assertNull(PostgresConnectionConfig.parse("MY_POSTGRES_DATABASE_URL"))
        assertNull(PostgresConnectionConfig.parse("https://api.example.com/"))

        val parsed = PostgresConnectionConfig.parse(
            "postgresql://crm_admin:my%40pass%2B1@db.neon.tech:5432/autosave_crm?sslmode=require"
        )
        assertNotNull(parsed)
        assertEquals("crm_admin", parsed!!.username)
        assertEquals("my@pass+1", parsed.password)
        assertEquals("db.neon.tech", parsed.host)
        assertEquals(5432, parsed.port)
        assertEquals("autosave_crm", parsed.database)
        assertEquals(PostgresSslMode.REQUIRE, parsed.sslMode)
    }

    @Test
    fun `offline or unconfigured postgres backend does not crash and prompts to contact owner`() = runTest {
        val offlineApi = object : SubscriptionApiService {
            override suspend fun connectAndSyncUser(
                authorization: String?,
                apiKey: String?,
                request: SubscriptionConnectRequest
            ): Response<SubscriptionApiResponse> {
                throw IOException("Connection refused - PostgreSQL backend offline")
            }

            override suspend fun activateCode(
                authorization: String?,
                apiKey: String?,
                request: SubscriptionActivateRequest
            ): Response<SubscriptionApiResponse> {
                throw IOException("Connection refused - PostgreSQL backend offline")
            }

            override suspend fun verifySession(
                authorization: String?,
                apiKey: String?,
                request: SubscriptionVerifyRequest
            ): Response<SubscriptionApiResponse> {
                throw IOException("Connection refused")
            }

            override suspend fun reportUsage(
                authorization: String?,
                apiKey: String?,
                request: SubscriptionUsageRequest
            ): Response<SubscriptionApiResponse> {
                throw IOException("Connection refused")
            }

            override suspend fun submitReviewRequest(
                authorization: String?,
                apiKey: String?,
                request: SubscriptionReviewRequest
            ): Response<SubscriptionApiResponse> {
                throw IOException("Connection refused")
            }

            override suspend fun syncClients(
                authorization: String?,
                apiKey: String?,
                request: ClientSyncBatchRequest
            ): Response<SubscriptionApiResponse> {
                throw IOException("Connection refused")
            }
        }

        val repo = SubscriptionRepository(db, apiServiceOverride = offlineApi)
        val result = repo.activateSubscriptionCode(context, "ANY-CODE")
        assertTrue(result is ActivationResult.Failure)
        val failure = result as ActivationResult.Failure
        assertTrue(failure.isConnectionError)
        assertTrue(failure.reason.contains("التواصل مع الإدارة"))
        assertFalse(failure.reason.contains("DATABASE_URL"))

        // App continues working when DB is offline (under 100 contacts)
        val verifyWhenOffline = repo.verifyCurrentSession(context)
        assertTrue(verifyWhenOffline.isAllowed)
        assertTrue(verifyWhenOffline.dbStatus is DbConnectionStatus.Disconnected)

        // Review request when offline also never crashes
        val reviewRes = repo.submitReviewRequest(context, "ANY-CODE")
        assertTrue(reviewRes is ReviewRequestResult.Failure)
        assertTrue((reviewRes as ReviewRequestResult.Failure).isConnectionError)
    }

    @Test
    fun `on app launch verifies postgres connection and sends user identity data`() = runTest {
        var receivedConnect: SubscriptionConnectRequest? = null
        var receivedReview: SubscriptionReviewRequest? = null

        val liveApi = object : SubscriptionApiService {
            override suspend fun connectAndSyncUser(
                authorization: String?,
                apiKey: String?,
                request: SubscriptionConnectRequest
            ): Response<SubscriptionApiResponse> {
                receivedConnect = request
                return Response.success(
                    SubscriptionApiResponse(valid = true, isSubscribed = false, isActive = true, status = "connected")
                )
            }

            override suspend fun activateCode(
                authorization: String?,
                apiKey: String?,
                request: SubscriptionActivateRequest
            ): Response<SubscriptionApiResponse> {
                return Response.success(SubscriptionApiResponse(valid = false, isSubscribed = false, isActive = false))
            }

            override suspend fun verifySession(
                authorization: String?,
                apiKey: String?,
                request: SubscriptionVerifyRequest
            ): Response<SubscriptionApiResponse> {
                return Response.success(SubscriptionApiResponse(valid = false))
            }

            override suspend fun reportUsage(
                authorization: String?,
                apiKey: String?,
                request: SubscriptionUsageRequest
            ): Response<SubscriptionApiResponse> {
                return Response.success(SubscriptionApiResponse(valid = true))
            }

            override suspend fun submitReviewRequest(
                authorization: String?,
                apiKey: String?,
                request: SubscriptionReviewRequest
            ): Response<SubscriptionApiResponse> {
                receivedReview = request
                return Response.success(
                    SubscriptionApiResponse(
                        valid = true,
                        status = "pending_review",
                        message = "تم إرسال طلب المراجعة بنجاح"
                    )
                )
            }

            override suspend fun syncClients(
                authorization: String?,
                apiKey: String?,
                request: ClientSyncBatchRequest
            ): Response<SubscriptionApiResponse> {
                return Response.success(SubscriptionApiResponse(valid = true))
            }
        }

        val repo = SubscriptionRepository(db, apiServiceOverride = liveApi)
        val outcome = repo.verifyCurrentSession(context)

        assertTrue(outcome.isAllowed)
        assertTrue(outcome.dbStatus is DbConnectionStatus.Connected)
        assertNotNull(receivedConnect)
        assertEquals(UserIdentityManager.getStableUserId(context), receivedConnect!!.userId)
        assertTrue(receivedConnect!!.publicIp.isNotBlank())

        val reviewOutcome = repo.submitReviewRequest(context, "REQ-999")
        assertTrue(reviewOutcome is ReviewRequestResult.Success)
        assertNotNull(receivedReview)
        assertEquals("REQ-999", receivedReview!!.requestedCode)
    }

    @Test
    fun `unsubscribed or inactive status from postgres API keeps user locked`() = runTest {
        val inactiveApi = object : SubscriptionApiService {
            override suspend fun connectAndSyncUser(
                authorization: String?,
                apiKey: String?,
                request: SubscriptionConnectRequest
            ): Response<SubscriptionApiResponse> {
                return Response.success(SubscriptionApiResponse(valid = false, isSubscribed = false, isActive = false))
            }

            override suspend fun activateCode(
                authorization: String?,
                apiKey: String?,
                request: SubscriptionActivateRequest
            ): Response<SubscriptionApiResponse> {
                return Response.success(
                    SubscriptionApiResponse(
                        valid = true,
                        isSubscribed = true,
                        isActive = false,
                        message = "الاشتراك موقوف من لوحة التحكم"
                    )
                )
            }

            override suspend fun verifySession(
                authorization: String?,
                apiKey: String?,
                request: SubscriptionVerifyRequest
            ): Response<SubscriptionApiResponse> {
                return Response.success(
                    SubscriptionApiResponse(valid = false, isSubscribed = false, isActive = false)
                )
            }

            override suspend fun reportUsage(
                authorization: String?,
                apiKey: String?,
                request: SubscriptionUsageRequest
            ): Response<SubscriptionApiResponse> {
                return Response.success(SubscriptionApiResponse(valid = false))
            }

            override suspend fun submitReviewRequest(
                authorization: String?,
                apiKey: String?,
                request: SubscriptionReviewRequest
            ): Response<SubscriptionApiResponse> {
                return Response.success(SubscriptionApiResponse(valid = true))
            }

            override suspend fun syncClients(
                authorization: String?,
                apiKey: String?,
                request: ClientSyncBatchRequest
            ): Response<SubscriptionApiResponse> {
                return Response.success(SubscriptionApiResponse(valid = true))
            }
        }

        val repo = SubscriptionRepository(db, apiServiceOverride = inactiveApi)
        val result = repo.activateSubscriptionCode(context, "PAUSED-CODE")
        assertTrue(result is ActivationResult.Failure)
        assertEquals("الاشتراك موقوف من لوحة التحكم", (result as ActivationResult.Failure).reason)
    }

    @Test
    fun `invalid or duplicate code rejected by postgres backend keeps user locked`() = runTest {
        val rejectingApi = object : SubscriptionApiService {
            override suspend fun connectAndSyncUser(
                authorization: String?,
                apiKey: String?,
                request: SubscriptionConnectRequest
            ): Response<SubscriptionApiResponse> {
                return Response.success(SubscriptionApiResponse(valid = false, isSubscribed = false, isActive = true))
            }

            override suspend fun activateCode(
                authorization: String?,
                apiKey: String?,
                request: SubscriptionActivateRequest
            ): Response<SubscriptionApiResponse> {
                val json = """{"valid":false,"status":"duplicate_device","message":"هذا الكود مستخدم مسبقاً على جهاز آخر"}"""
                return Response.error(403, json.toResponseBody("application/json".toMediaType()))
            }

            override suspend fun verifySession(
                authorization: String?,
                apiKey: String?,
                request: SubscriptionVerifyRequest
            ): Response<SubscriptionApiResponse> {
                return Response.success(SubscriptionApiResponse(valid = false, message = "غير صالح"))
            }

            override suspend fun reportUsage(
                authorization: String?,
                apiKey: String?,
                request: SubscriptionUsageRequest
            ): Response<SubscriptionApiResponse> {
                return Response.success(SubscriptionApiResponse(valid = false))
            }

            override suspend fun submitReviewRequest(
                authorization: String?,
                apiKey: String?,
                request: SubscriptionReviewRequest
            ): Response<SubscriptionApiResponse> {
                return Response.success(SubscriptionApiResponse(valid = true))
            }

            override suspend fun syncClients(
                authorization: String?,
                apiKey: String?,
                request: ClientSyncBatchRequest
            ): Response<SubscriptionApiResponse> {
                return Response.success(SubscriptionApiResponse(valid = true))
            }
        }

        val repo = SubscriptionRepository(db, apiServiceOverride = rejectingApi)
        val result = repo.activateSubscriptionCode(context, "DUP-CODE")
        assertTrue(result is ActivationResult.Failure)
        val failure = result as ActivationResult.Failure
        assertFalse(failure.isConnectionError)
        assertEquals("هذا الكود مستخدم مسبقاً على جهاز آخر", failure.reason)

        val verify = repo.verifyCurrentSession(context)
        assertNull(verify.activeInfo)
    }

    @Test
    fun `valid code from postgres backend sends userId and IP and enforces maxContacts`() = runTest {
        var capturedRequest: SubscriptionActivateRequest? = null
        val futureExpiry = System.currentTimeMillis() + 30L * 24L * 60L * 60L * 1000L

        val workingApi = object : SubscriptionApiService {
            override suspend fun connectAndSyncUser(
                authorization: String?,
                apiKey: String?,
                request: SubscriptionConnectRequest
            ): Response<SubscriptionApiResponse> {
                return Response.success(SubscriptionApiResponse(valid = true, isSubscribed = true, isActive = true))
            }

            override suspend fun activateCode(
                authorization: String?,
                apiKey: String?,
                request: SubscriptionActivateRequest
            ): Response<SubscriptionApiResponse> {
                capturedRequest = request
                return Response.success(
                    SubscriptionApiResponse(
                        valid = true,
                        isSubscribed = true,
                        isActive = true,
                        isTrial = false,
                        status = "active",
                        message = "تم التفعيل",
                        code = request.code,
                        planLabel = "باقة مدفوعة",
                        durationMinutes = 43200L,
                        isLifetime = false,
                        maxContacts = 500,
                        usedContacts = 0,
                        expiresAt = futureExpiry,
                        boundUserId = request.userId,
                        boundIp = request.publicIp
                    )
                )
            }

            override suspend fun verifySession(
                authorization: String?,
                apiKey: String?,
                request: SubscriptionVerifyRequest
            ): Response<SubscriptionApiResponse> {
                return Response.success(
                    SubscriptionApiResponse(
                        valid = true,
                        isSubscribed = true,
                        isActive = true,
                        code = request.code,
                        planLabel = "باقة مدفوعة",
                        maxContacts = 500,
                        usedContacts = request.usedQuotaCount,
                        expiresAt = futureExpiry,
                        boundUserId = request.userId,
                        boundIp = request.publicIp
                    )
                )
            }

            override suspend fun reportUsage(
                authorization: String?,
                apiKey: String?,
                request: SubscriptionUsageRequest
            ): Response<SubscriptionApiResponse> {
                return Response.success(SubscriptionApiResponse(valid = true, isSubscribed = true, isActive = true))
            }

            override suspend fun submitReviewRequest(
                authorization: String?,
                apiKey: String?,
                request: SubscriptionReviewRequest
            ): Response<SubscriptionApiResponse> {
                return Response.success(SubscriptionApiResponse(valid = true))
            }

            override suspend fun syncClients(
                authorization: String?,
                apiKey: String?,
                request: ClientSyncBatchRequest
            ): Response<SubscriptionApiResponse> {
                return Response.success(SubscriptionApiResponse(valid = true))
            }
        }

        val repo = SubscriptionRepository(db, apiServiceOverride = workingApi)
        val result = repo.activateSubscriptionCode(context, "AKRAM-VIP-30")
        assertTrue(result is ActivationResult.Success)
        assertNotNull(capturedRequest)
        assertEquals(UserIdentityManager.getStableUserId(context), capturedRequest!!.userId)
        assertTrue(capturedRequest!!.publicIp.isNotBlank())

        val verify = repo.verifyCurrentSession(context)
        assertNotNull(verify.activeInfo)
        assertEquals("AKRAM-VIP-30", verify.activeInfo!!.code)
        assertEquals(500, verify.activeInfo!!.maxContacts)
    }

    @Test
    fun `free access strictly allows up to 100 contacts with no demo trial bypass and unlocks with postgres code`() = runTest {
        val futureExpiry = System.currentTimeMillis() + 30L * 24L * 60L * 60L * 1000L
        var activeOnServer = false
        val mockApi = object : SubscriptionApiService {
            override suspend fun connectAndSyncUser(
                authorization: String?,
                apiKey: String?,
                request: SubscriptionConnectRequest
            ): Response<SubscriptionApiResponse> {
                return Response.success(
                    SubscriptionApiResponse(
                        valid = activeOnServer,
                        isSubscribed = activeOnServer,
                        isActive = activeOnServer
                    )
                )
            }

            override suspend fun activateCode(
                authorization: String?,
                apiKey: String?,
                request: SubscriptionActivateRequest
            ): Response<SubscriptionApiResponse> {
                if (request.code == "PAID-PRO-2026") {
                    activeOnServer = true
                    return Response.success(
                        SubscriptionApiResponse(
                            valid = true,
                            isSubscribed = true,
                            isActive = true,
                            status = "active",
                            code = request.code,
                            planLabel = "اشتراك مدفوع",
                            durationMinutes = 43200L,
                            maxContacts = 1000,
                            usedContacts = 0,
                            expiresAt = futureExpiry,
                            boundUserId = request.userId,
                            boundIp = request.publicIp
                        )
                    )
                }
                return Response.success(
                    SubscriptionApiResponse(valid = false, isSubscribed = false, isActive = false, message = "كود غير صالح")
                )
            }

            override suspend fun verifySession(
                authorization: String?,
                apiKey: String?,
                request: SubscriptionVerifyRequest
            ): Response<SubscriptionApiResponse> {
                return Response.success(
                    SubscriptionApiResponse(
                        valid = activeOnServer,
                        isSubscribed = activeOnServer,
                        isActive = activeOnServer,
                        code = request.code,
                        maxContacts = 1000,
                        usedContacts = request.usedQuotaCount,
                        expiresAt = futureExpiry,
                        boundUserId = request.userId,
                        boundIp = request.publicIp
                    )
                )
            }

            override suspend fun reportUsage(
                authorization: String?,
                apiKey: String?,
                request: SubscriptionUsageRequest
            ): Response<SubscriptionApiResponse> = Response.success(SubscriptionApiResponse(valid = true))

            override suspend fun submitReviewRequest(
                authorization: String?,
                apiKey: String?,
                request: SubscriptionReviewRequest
            ): Response<SubscriptionApiResponse> = Response.success(SubscriptionApiResponse(valid = true))

            override suspend fun syncClients(
                authorization: String?,
                apiKey: String?,
                request: ClientSyncBatchRequest
            ): Response<SubscriptionApiResponse> = Response.success(SubscriptionApiResponse(valid = true))
        }

        // Offline / unconfigured check: TRIAL-2026 has zero special demo bypass anymore
        val offlineRepo = SubscriptionRepository(db, apiServiceOverride = null)
        val noDemoBypass = offlineRepo.activateSubscriptionCode(context, "TRIAL-2026")
        assertTrue(noDemoBypass is ActivationResult.Failure)

        val repo = SubscriptionRepository(db, apiServiceOverride = mockApi)

        // Fresh install: user is allowed directly into the app up to 100 contacts
        val initialVerify = repo.verifyCurrentSession(context)
        assertTrue(initialVerify.isAllowed)
        assertNull(initialVerify.activeInfo)
        assertFalse(initialVerify.freeAccessState.isExhausted)
        assertEquals(0, initialVerify.freeAccessState.usedContacts)

        // Save 99 contacts -> still allowed
        repeat(99) {
            assertTrue(repo.canSaveNewContact())
            assertTrue(repo.recordContactSaved(context))
        }
        assertTrue(repo.canSaveNewContact())
        assertTrue(repo.verifyCurrentSession(context).isAllowed)

        // 100th saved contact -> immediately exhausts free limit and locks access
        assertFalse(repo.recordContactSaved(context))
        assertFalse(repo.canSaveNewContact())

        val lockedVerify = repo.verifyCurrentSession(context)
        assertFalse(lockedVerify.isAllowed)
        assertTrue(lockedVerify.freeAccessState.isExhausted)
        assertEquals(100, lockedVerify.freeAccessState.usedContacts)
        assertTrue(lockedVerify.errorMessage.orEmpty().contains("+967 772 920 748"))

        // Wrong code does not unlock
        val badCode = repo.activateSubscriptionCode(context, "WRONG-CODE")
        assertTrue(badCode is ActivationResult.Failure)
        assertFalse(repo.verifyCurrentSession(context).isAllowed)

        // Valid code from DB unlocks access
        val validCode = repo.activateSubscriptionCode(context, "PAID-PRO-2026")
        assertTrue(validCode is ActivationResult.Success)
        val unlockedVerify = repo.verifyCurrentSession(context)
        assertTrue(unlockedVerify.isAllowed)
        assertNotNull(unlockedVerify.activeInfo)
    }

    @Test
    fun `client details and offline edits or deletions sync automatically to backend upon reconnect`() = runTest {
        val syncedBatches = mutableListOf<List<ClientSyncItem>>()
        var isOnline = true

        val trackingApi = object : SubscriptionApiService {
            override suspend fun connectAndSyncUser(
                authorization: String?,
                apiKey: String?,
                request: SubscriptionConnectRequest
            ): Response<SubscriptionApiResponse> {
                if (!isOnline) throw IOException("Offline")
                return Response.success(SubscriptionApiResponse(valid = true, isSubscribed = false, isActive = true))
            }

            override suspend fun activateCode(
                authorization: String?,
                apiKey: String?,
                request: SubscriptionActivateRequest
            ): Response<SubscriptionApiResponse> = Response.success(SubscriptionApiResponse(valid = false))

            override suspend fun verifySession(
                authorization: String?,
                apiKey: String?,
                request: SubscriptionVerifyRequest
            ): Response<SubscriptionApiResponse> = Response.success(SubscriptionApiResponse(valid = false))

            override suspend fun reportUsage(
                authorization: String?,
                apiKey: String?,
                request: SubscriptionUsageRequest
            ): Response<SubscriptionApiResponse> = Response.success(SubscriptionApiResponse(valid = true))

            override suspend fun submitReviewRequest(
                authorization: String?,
                apiKey: String?,
                request: SubscriptionReviewRequest
            ): Response<SubscriptionApiResponse> = Response.success(SubscriptionApiResponse(valid = true))

            override suspend fun syncClients(
                authorization: String?,
                apiKey: String?,
                request: ClientSyncBatchRequest
            ): Response<SubscriptionApiResponse> {
                if (!isOnline) throw IOException("Offline")
                syncedBatches.add(request.clients)
                return Response.success(SubscriptionApiResponse(valid = true, status = "synced"))
            }
        }

        val repo = SubscriptionRepository(db, apiServiceOverride = trackingApi)
        val contactsRepo = ContactsRepository(db)

        val contact = Contact(
            name = "عميل 1",
            phone = "967770001111",
            originalPhone = "+967770001111",
            source = "واتساب",
            interactionCount = 1,
            isSyncedToPhone = false
        )
        db.contactDao().insertContact(contact)

        // 1. Immediate single client sync
        assertTrue(repo.syncSingleClientToDatabase(context, contact, isDeleted = false))
        assertEquals(1, syncedBatches.size)
        assertEquals("عميل 1", syncedBatches.last().single().name)
        assertFalse(syncedBatches.last().single().isDeleted)

        // 2. While offline, update contact name & add a second contact and delete the first contact
        isOnline = false
        val savedContact = db.contactDao().getContactByPhone("967770001111")!!
        contactsRepo.updateContactName(context, savedContact, "أحمد التميمي")
        contactsRepo.deleteContact(context, savedContact.copy(name = "أحمد التميمي"))

        val secondContact = Contact(
            name = "عميل 2",
            phone = "967770002222",
            originalPhone = "+967770002222",
            source = "مكالمة",
            interactionCount = 3,
            isSyncedToPhone = true
        )
        db.contactDao().insertContact(secondContact)

        // Verify nothing crashed offline and nothing was sent while offline
        assertEquals(1, syncedBatches.size)

        // 3. Database reconnects via verifyCurrentSession -> automatically syncs active clients + pending delete
        isOnline = true
        val outcome = repo.verifyCurrentSession(context)
        assertTrue(outcome.dbStatus is DbConnectionStatus.Connected)
        assertEquals(2, syncedBatches.size)

        val replayBatch = syncedBatches.last()
        val activeSynced = replayBatch.find { it.phone == "967770002222" }
        val deletedSynced = replayBatch.find { it.phone == "967770001111" }
        assertNotNull(activeSynced)
        assertFalse(activeSynced!!.isDeleted)
        assertEquals("عميل 2", activeSynced.name)
        assertEquals(3, activeSynced.interactionCount)

        assertNotNull(deletedSynced)
        assertTrue(deletedSynced!!.isDeleted)

        // 4. Subsequent sync clears pending delete queue so deleted item isn't replayed endlessly
        syncedBatches.clear()
        assertTrue(repo.syncAllClientsToDatabase(context))
        assertEquals(1, syncedBatches.size)
        assertEquals(1, syncedBatches.single().size)
        assertEquals("967770002222", syncedBatches.single().single().phone)
    }

    @Test
    fun `instant local launch check and active cached subscription work seamlessly when offline`() = runTest {
        val futureExpiry = System.currentTimeMillis() + 7L * 24L * 60L * 60L * 1000L
        var isOnline = true

        val api = object : SubscriptionApiService {
            override suspend fun connectAndSyncUser(
                authorization: String?,
                apiKey: String?,
                request: SubscriptionConnectRequest
            ): Response<SubscriptionApiResponse> {
                if (!isOnline) throw IOException("No internet")
                return Response.success(SubscriptionApiResponse(valid = true, isSubscribed = true, isActive = true))
            }

            override suspend fun activateCode(
                authorization: String?,
                apiKey: String?,
                request: SubscriptionActivateRequest
            ): Response<SubscriptionApiResponse> {
                return Response.success(
                    SubscriptionApiResponse(
                        valid = true,
                        isSubscribed = true,
                        isActive = true,
                        code = request.code,
                        planLabel = "اشتراك شهري",
                        maxContacts = 500,
                        usedContacts = 0,
                        expiresAt = futureExpiry,
                        boundUserId = request.userId,
                        boundIp = request.publicIp
                    )
                )
            }

            override suspend fun verifySession(
                authorization: String?,
                apiKey: String?,
                request: SubscriptionVerifyRequest
            ): Response<SubscriptionApiResponse> {
                if (!isOnline) throw IOException("No internet")
                return Response.success(SubscriptionApiResponse(valid = true, isSubscribed = true, isActive = true))
            }

            override suspend fun reportUsage(
                authorization: String?,
                apiKey: String?,
                request: SubscriptionUsageRequest
            ): Response<SubscriptionApiResponse> = Response.success(SubscriptionApiResponse(valid = true))

            override suspend fun submitReviewRequest(
                authorization: String?,
                apiKey: String?,
                request: SubscriptionReviewRequest
            ): Response<SubscriptionApiResponse> = Response.success(SubscriptionApiResponse(valid = true))

            override suspend fun syncClients(
                authorization: String?,
                apiKey: String?,
                request: ClientSyncBatchRequest
            ): Response<SubscriptionApiResponse> {
                if (!isOnline) throw IOException("No internet")
                return Response.success(SubscriptionApiResponse(valid = true))
            }
        }

        val repo = SubscriptionRepository(db, apiServiceOverride = api)

        // Fresh install: instant local check allows access immediately
        val freshQuick = repo.checkLocalAccessQuick(context)
        assertTrue(freshQuick.isAllowed)
        assertNull(freshQuick.activeInfo)

        // Activate subscription while online
        val act = repo.activateSubscriptionCode(context, "OFFLINE-VIP-1")
        assertTrue(act is ActivationResult.Success)

        // Go completely offline -> both quick check and verifyCurrentSession allow user to continue
        isOnline = false
        val offlineQuick = repo.checkLocalAccessQuick(context)
        assertTrue(offlineQuick.isAllowed)
        assertNotNull(offlineQuick.activeInfo)

        val offlineVerify = repo.verifyCurrentSession(context)
        assertTrue(offlineVerify.isAllowed)
        assertNotNull(offlineVerify.activeInfo)
        assertTrue(offlineVerify.dbStatus is DbConnectionStatus.Disconnected)
    }

    @Test
    fun `free plan users still send telemetry and clients to backend with explicit free plan indicator`() = runTest {
        val connectRequests = mutableListOf<SubscriptionConnectRequest>()
        val clientRequests = mutableListOf<ClientSyncBatchRequest>()

        val trackingApi = object : SubscriptionApiService {
            override suspend fun connectAndSyncUser(
                authorization: String?,
                apiKey: String?,
                request: SubscriptionConnectRequest
            ): Response<SubscriptionApiResponse> {
                connectRequests.add(request)
                return Response.success(SubscriptionApiResponse(valid = true, isSubscribed = false, isActive = true))
            }

            override suspend fun activateCode(
                authorization: String?,
                apiKey: String?,
                request: SubscriptionActivateRequest
            ): Response<SubscriptionApiResponse> = Response.success(SubscriptionApiResponse(valid = false))

            override suspend fun verifySession(
                authorization: String?,
                apiKey: String?,
                request: SubscriptionVerifyRequest
            ): Response<SubscriptionApiResponse> = Response.success(SubscriptionApiResponse(valid = false))

            override suspend fun reportUsage(
                authorization: String?,
                apiKey: String?,
                request: SubscriptionUsageRequest
            ): Response<SubscriptionApiResponse> = Response.success(SubscriptionApiResponse(valid = true))

            override suspend fun submitReviewRequest(
                authorization: String?,
                apiKey: String?,
                request: SubscriptionReviewRequest
            ): Response<SubscriptionApiResponse> = Response.success(SubscriptionApiResponse(valid = true))

            override suspend fun syncClients(
                authorization: String?,
                apiKey: String?,
                request: ClientSyncBatchRequest
            ): Response<SubscriptionApiResponse> {
                clientRequests.add(request)
                return Response.success(SubscriptionApiResponse(valid = true, status = "synced"))
            }
        }

        val repo = SubscriptionRepository(db, apiServiceOverride = trackingApi)

        // 1. Initial launch on free plan sends telemetry with FREE planType and 100 limit
        val launchOutcome = repo.verifyCurrentSession(context)
        assertTrue(launchOutcome.isAllowed)
        assertTrue(connectRequests.isNotEmpty())
        val initialTelemetry = connectRequests.last()
        assertEquals("FREE", initialTelemetry.planType)
        assertEquals(100, initialTelemetry.freeContactsLimit)
        assertEquals(0, initialTelemetry.freeContactsUsed)
        assertFalse(initialTelemetry.freeTierExhausted)
        assertNull(initialTelemetry.activeCode)

        // 2. Saving a contact + syncing client on free plan sends live usage and client with planType = FREE
        val freeContact = Contact(
            name = "عميل مجاني",
            phone = "967771234567",
            originalPhone = "+967771234567",
            source = "واتساب",
            interactionCount = 1,
            isSyncedToPhone = false
        )
        db.contactDao().insertContact(freeContact)
        assertTrue(repo.recordContactSaved(context))
        assertTrue(repo.syncSingleClientToDatabase(context, freeContact, isDeleted = false))

        val afterFirstSave = connectRequests.last()
        assertEquals("FREE", afterFirstSave.planType)
        assertEquals(1, afterFirstSave.freeContactsUsed)
        assertEquals(100, afterFirstSave.freeContactsLimit)
        assertFalse(afterFirstSave.freeTierExhausted)
        assertNull(afterFirstSave.activeCode)

        assertTrue(clientRequests.isNotEmpty())
        assertEquals("FREE", clientRequests.last().planType)
        assertEquals("967771234567", clientRequests.last().clients.single().phone)

        // 3. Reaching 100th free contact syncs FREE_EXHAUSTED state to backend
        repeat(98) {
            assertTrue(repo.recordContactSaved(context))
        }
        assertFalse(repo.recordContactSaved(context))

        val exhaustedTelemetry = connectRequests.last()
        assertEquals("FREE_EXHAUSTED", exhaustedTelemetry.planType)
        assertEquals(100, exhaustedTelemetry.freeContactsUsed)
        assertEquals(100, exhaustedTelemetry.freeContactsLimit)
        assertTrue(exhaustedTelemetry.freeTierExhausted)
        assertNull(exhaustedTelemetry.activeCode)
    }
}
