package gamalprojects.autosavecontactscrm.akramalahmadi

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import gamalprojects.autosavecontactscrm.akramalahmadi.database.AppDatabase
import gamalprojects.autosavecontactscrm.akramalahmadi.managers.UserIdentityManager
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
    fun `trial code TRIAL-2026 allows 200 contacts then locks session forcing paid code`() = runTest {
        val repo = SubscriptionRepository(db, apiServiceOverride = null)

        val trial = repo.activateSubscriptionCode(context, "trial-2026")
        assertTrue(trial is ActivationResult.Success)
        val info = (trial as ActivationResult.Success).info
        assertEquals(200, info.maxContacts)
        assertTrue(info.isTrial)

        // Save 199 contacts -> still allowed
        repeat(199) {
            assertTrue(repo.canSaveNewContact())
            assertTrue(repo.recordContactSaved(context))
        }
        assertTrue(repo.canSaveNewContact())

        // 200th contact -> saved, then immediately locks session and exhausts trial
        assertFalse(repo.recordContactSaved(context))
        assertFalse(repo.canSaveNewContact())
        assertTrue(repo.isDemoTrialExhausted())

        // Session verify must return null & require paid code
        val verify = repo.verifyCurrentSession(context)
        assertNull(verify.activeInfo)

        // Re-entering TRIAL-2026 must be rejected
        val retry = repo.activateSubscriptionCode(context, "TRIAL-2026")
        assertTrue(retry is ActivationResult.Failure)
    }

    @Test
    fun `free access allows up to 100 saved contacts then locks until valid code entered`() = runTest {
        val repo = SubscriptionRepository(db, apiServiceOverride = null)

        // Fresh install: user is allowed directly into the app without code
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

        // Valid code unlocks access
        val validCode = repo.activateSubscriptionCode(context, "TRIAL-2026")
        assertTrue(validCode is ActivationResult.Success)
        val unlockedVerify = repo.verifyCurrentSession(context)
        assertTrue(unlockedVerify.isAllowed)
        assertNotNull(unlockedVerify.activeInfo)
    }
}
