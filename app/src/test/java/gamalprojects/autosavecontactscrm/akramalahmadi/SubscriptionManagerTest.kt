package gamalprojects.autosavecontactscrm.akramalahmadi

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import gamalprojects.autosavecontactscrm.akramalahmadi.database.AppDatabase
import gamalprojects.autosavecontactscrm.akramalahmadi.managers.UserIdentityManager
import gamalprojects.autosavecontactscrm.akramalahmadi.network.SubscriptionActivateRequest
import gamalprojects.autosavecontactscrm.akramalahmadi.network.SubscriptionApiResponse
import gamalprojects.autosavecontactscrm.akramalahmadi.network.SubscriptionApiService
import gamalprojects.autosavecontactscrm.akramalahmadi.network.SubscriptionUsageRequest
import gamalprojects.autosavecontactscrm.akramalahmadi.network.SubscriptionVerifyRequest
import gamalprojects.autosavecontactscrm.akramalahmadi.repositories.ActivationResult
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
    fun `offline or unconfigured postgres backend does not crash and prompts to contact owner`() = runTest {
        val offlineApi = object : SubscriptionApiService {
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
        }

        val repo = SubscriptionRepository(db, apiServiceOverride = offlineApi)
        val result = repo.activateSubscriptionCode(context, "ANY-CODE")
        assertTrue(result is ActivationResult.Failure)
        val failure = result as ActivationResult.Failure
        assertTrue(failure.isConnectionError)
        assertTrue(failure.reason.contains("التواصل مع صاحب المشروع"))
    }

    @Test
    fun `unsubscribed or inactive status from postgres API keeps user locked`() = runTest {
        val inactiveApi = object : SubscriptionApiService {
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
        }

        val repo = SubscriptionRepository(db, apiServiceOverride = inactiveApi)
        val result = repo.activateSubscriptionCode(context, "PAUSED-CODE")
        assertTrue(result is ActivationResult.Failure)
        assertEquals("الاشتراك موقوف من لوحة التحكم", (result as ActivationResult.Failure).reason)
    }

    @Test
    fun `invalid or duplicate code rejected by postgres backend keeps user locked`() = runTest {
        val rejectingApi = object : SubscriptionApiService {
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
}
