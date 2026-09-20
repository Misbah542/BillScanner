package com.snaptab.app.data.remote

import com.snaptab.app.data.remote.dto.*
import okhttp3.MultipartBody
import retrofit2.Response
import retrofit2.http.*

/**
 * The whole API surface in one interface.
 *
 * Note what the scan endpoints do NOT do: any recognition. The client uploads the
 * image and polls; the server runs OCR, parses the items and suggests a category, so
 * the rules can be fixed for everyone without an app release.
 */
interface SnapTabApi {

    // ----------------------------------------------------------------- config ---

    @GET("v1/config")
    suspend fun config(): Response<ConfigResponse>

    // ------------------------------------------------------------------- auth ---

    @POST("v1/auth/otp/start")
    suspend fun startOtp(@Body body: OtpStartRequest): Response<OtpStartResponse>

    @POST("v1/auth/otp/verify")
    suspend fun verifyOtp(@Body body: OtpVerifyRequest): Response<AuthResponse>

    @POST("v1/auth/google")
    suspend fun signInWithGoogle(@Body body: GoogleSignInRequest): Response<AuthResponse>

    @POST("v1/auth/refresh")
    suspend fun refresh(@Body body: RefreshRequest): Response<AuthResponse>

    @POST("v1/auth/logout")
    suspend fun logout(): Response<Unit>

    @POST("v1/auth/logout-everywhere")
    suspend fun logoutEverywhere(): Response<Unit>

    @GET("v1/auth/methods")
    suspend fun authMethods(): Response<AuthMethodsResponse>

    // ------------------------------------------------------------------ users ---

    @GET("v1/users/me")
    suspend fun me(): Response<MeResponse>

    @PATCH("v1/users/me")
    suspend fun updateMe(@Body body: UpdateMeRequest): Response<MeResponse>

    @DELETE("v1/users/me")
    suspend fun deleteAccount(): Response<Unit>

    @GET("v1/users/lookup")
    suspend fun lookup(@Query("contact") contact: String): Response<LookupResponse>

    @GET("v1/users/recent")
    suspend fun recentPeople(): Response<RecentPeopleResponse>

    @PUT("v1/users/me/device")
    suspend fun registerDevice(@Body body: DeviceRequest): Response<DeviceResponse>

    @GET("v1/users/me/notifications")
    suspend fun notifications(
        @Query("unreadOnly") unreadOnly: Boolean = false,
        @Query("limit") limit: Int = 30
    ): Response<NotificationListResponse>

    @POST("v1/users/me/notifications/read")
    suspend fun markNotificationsRead(@Body body: MarkReadRequest): Response<Unit>

    // ------------------------------------------------------------- categories ---

    @GET("v1/categories")
    suspend fun categories(): Response<CategoriesResponse>

    // --------------------------------------------------------------- expenses ---

    @GET("v1/expenses")
    suspend fun expenses(
        /** `all`, `personal` or `shared` — the column the home screen switches on. */
        @Query("kind") kind: String = "all",
        @Query("groupId") groupId: String? = null,
        @Query("categorySlug") categorySlug: String? = null,
        @Query("from") from: String? = null,
        @Query("to") to: String? = null,
        @Query("search") search: String? = null,
        @Query("limit") limit: Int = 25,
        @Query("cursor") cursor: String? = null
    ): Response<ExpenseListResponse>

    @POST("v1/expenses")
    suspend fun createExpense(@Body body: CreateExpenseRequest): Response<ExpenseResponse>

    @GET("v1/expenses/{id}")
    suspend fun expense(@Path("id") id: String): Response<ExpenseResponse>

    @PATCH("v1/expenses/{id}")
    suspend fun updateExpense(
        @Path("id") id: String,
        @Body body: UpdateExpenseRequest
    ): Response<ExpenseResponse>

    @DELETE("v1/expenses/{id}")
    suspend fun deleteExpense(@Path("id") id: String): Response<Unit>

    @PUT("v1/expenses/{id}/split")
    suspend fun setSplit(
        @Path("id") id: String,
        @Body body: SetSplitRequest
    ): Response<ExpenseResponse>

    /** Drops the split and makes it a personal expense again. */
    @DELETE("v1/expenses/{id}/split")
    suspend fun removeSplit(@Path("id") id: String): Response<ExpenseResponse>

    @GET("v1/expenses/suggest/category")
    suspend fun suggestCategory(
        @Query("merchant") merchant: String? = null,
        /** Item names joined with `|`. */
        @Query("items") items: String? = null,
        @Query("direction") direction: String? = null
    ): Response<SuggestionsResponse>

    // ------------------------------------------------------------------ scans ---

    /**
     * Uploads a receipt photo. Comes back 202 with a scan id; the reading happens in a
     * worker. Send `Idempotency-Key` so a retry after a dropped connection does not
     * queue the same photo twice.
     */
    @Multipart
    @POST("v1/scans")
    suspend fun uploadScan(
        @Part image: MultipartBody.Part,
        @Header("Idempotency-Key") idempotencyKey: String? = null
    ): Response<ScanResponse>

    @GET("v1/scans/{id}")
    suspend fun scan(@Path("id") id: String): Response<ScanResponse>

    @POST("v1/scans/{id}/retry")
    suspend fun retryScan(@Path("id") id: String): Response<ScanResponse>

    // ------------------------------------------------------------------ alerts ---

    @POST("v1/alerts")
    suspend fun ingestAlerts(@Body body: IngestAlertsRequest): Response<IngestAlertsResponse>

    @GET("v1/alerts")
    suspend fun alerts(
        @Query("status") status: String? = null,
        @Query("direction") direction: String? = null,
        @Query("limit") limit: Int = 30,
        @Query("cursor") cursor: String? = null
    ): Response<AlertListResponse>

    /**
     * The one-tap route out of the inbox. `kind = PERSONAL` keeps it to yourself;
     * `kind = SHARED` with a `groupId` puts it on a group you already have and splits
     * it equally across that group's current members.
     */
    @POST("v1/alerts/{id}/expense")
    suspend fun alertToExpense(
        @Path("id") id: String,
        @Body body: AlertToExpenseRequest
    ): Response<ExpenseResponse>

    @POST("v1/alerts/{id}/link")
    suspend fun linkAlert(
        @Path("id") id: String,
        @Body body: LinkAlertRequest
    ): Response<LinkAlertResponse>

    @POST("v1/alerts/{id}/ignore")
    suspend fun ignoreAlert(@Path("id") id: String): Response<Unit>

    @GET("v1/alerts/{id}/suggestions")
    suspend fun alertSuggestions(@Path("id") id: String): Response<AlertSuggestionsResponse>

    @GET("v1/alerts/rules/version")
    suspend fun smsRulesVersion(): Response<RulesVersionSingle>

    // ----------------------------------------------------------------- groups ---

    @GET("v1/groups")
    suspend fun groups(): Response<GroupListResponse>

    @POST("v1/groups")
    suspend fun createGroup(@Body body: CreateGroupRequest): Response<CreateGroupResponse>

    @GET("v1/groups/{id}")
    suspend fun group(@Path("id") id: String): Response<GroupDetailResponse>

    @PATCH("v1/groups/{id}")
    suspend fun updateGroup(
        @Path("id") id: String,
        @Body body: CreateGroupRequest
    ): Response<CreateGroupResponse>

    @POST("v1/groups/{id}/members")
    suspend fun addMembers(
        @Path("id") id: String,
        @Body body: AddMembersRequest
    ): Response<AddMembersResponse>

    @DELETE("v1/groups/{id}/members/{userId}")
    suspend fun removeMember(
        @Path("id") id: String,
        @Path("userId") userId: String
    ): Response<Unit>

    @GET("v1/groups/{id}/balance")
    suspend fun groupBalance(@Path("id") id: String): Response<BalanceResponse>

    @GET("v1/groups/{id}/activity")
    suspend fun groupActivity(
        @Path("id") id: String,
        @Query("limit") limit: Int = 25
    ): Response<GroupActivityResponse>

    // ------------------------------------------------------------ settlements ---

    @GET("v1/settlements/balance")
    suspend fun balance(@Query("groupId") groupId: String? = null): Response<BalanceResponse>

    @POST("v1/settlements")
    suspend fun createSettlement(
        @Body body: CreateSettlementRequest
    ): Response<CreateSettlementResponse>

    @GET("v1/settlements")
    suspend fun settlements(
        @Query("groupId") groupId: String? = null,
        @Query("limit") limit: Int = 30
    ): Response<SettlementListResponse>

    @DELETE("v1/settlements/{id}")
    suspend fun reverseSettlement(@Path("id") id: String): Response<Unit>

    @POST("v1/settlements/remind")
    suspend fun remind(@Body body: RemindRequest): Response<RemindResponse>

    // ------------------------------------------------------------------ share ---

    @POST("v1/share-links")
    suspend fun createShareLink(@Body body: CreateShareLinkRequest): Response<ShareLinkResponse>

    @DELETE("v1/share-links/{id}")
    suspend fun revokeShareLink(@Path("id") id: String): Response<Unit>

    // --------------------------------------------------------------- insights ---

    @GET("v1/insights/monthly")
    suspend fun monthlySummary(
        @Query("month") month: String? = null,
        @Query("kind") kind: String = "ALL"
    ): Response<MonthlySummaryResponse>

    @GET("v1/insights/trend")
    suspend fun trend(
        @Query("months") months: Int = 6,
        @Query("kind") kind: String = "ALL"
    ): Response<TrendResponse>

    @GET("v1/insights/merchants")
    suspend fun topMerchants(
        @Query("month") month: String? = null,
        @Query("limit") limit: Int = 10
    ): Response<MerchantsResponse>
}
