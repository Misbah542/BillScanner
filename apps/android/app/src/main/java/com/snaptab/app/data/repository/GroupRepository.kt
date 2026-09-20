package com.snaptab.app.data.repository

import com.snaptab.app.core.ApiResult
import com.snaptab.app.core.map
import com.snaptab.app.data.local.GroupDao
import com.snaptab.app.data.local.GroupEntity
import com.snaptab.app.data.remote.SnapTabApi
import com.snaptab.app.data.remote.apiCall
import com.snaptab.app.data.remote.dto.*
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GroupRepository @Inject constructor(
    private val api: SnapTabApi,
    private val dao: GroupDao
) {

    /**
     * The cached list, which is what the "log this alert to a group" sheet shows. It
     * offers only groups that already exist — that sheet never creates one.
     */
    fun observe(): Flow<List<GroupEntity>> = dao.observe()

    fun observeOne(id: String): Flow<GroupEntity?> = dao.observeOne(id)

    suspend fun refresh(): ApiResult<List<GroupDto>> =
        apiCall { api.groups() }.map { response ->
            dao.upsert(response.groups.map { it.toEntity() })
            response.groups
        }

    suspend fun detail(id: String): ApiResult<GroupDetailResponse> =
        apiCall { api.group(id) }.map { response ->
            dao.upsert(listOf(response.group.toEntity()))
            response
        }

    suspend fun create(
        name: String,
        iconKey: String = "home",
        invite: List<String> = emptyList(),
        currency: String = "INR"
    ): ApiResult<CreateGroupResponse> =
        apiCall {
            api.createGroup(
                CreateGroupRequest(
                    name = name.trim(),
                    iconKey = iconKey,
                    invite = invite.map { it.trim() }.filter { it.isNotEmpty() },
                    currency = currency
                )
            )
        }.map { response ->
            dao.upsert(listOf(response.group.toEntity()))
            response
        }

    /** Adds people by email or phone; someone with no account gets a placeholder. */
    suspend fun addMembers(
        groupId: String,
        contacts: List<String>,
        displayNames: Map<String, String> = emptyMap()
    ): ApiResult<AddMembersResponse> =
        apiCall {
            api.addMembers(
                groupId,
                AddMembersRequest(
                    contacts = contacts.map { it.trim() }.filter { it.isNotEmpty() },
                    displayNames = displayNames.takeIf { it.isNotEmpty() }
                )
            )
        }.map { response ->
            dao.upsert(listOf(response.group.toEntity()))
            response
        }

    suspend fun removeMember(groupId: String, userId: String): ApiResult<Unit> =
        apiCall { api.removeMember(groupId, userId) }.map { }

    suspend fun activity(groupId: String): ApiResult<List<GroupActivityEntryDto>> =
        apiCall { api.groupActivity(groupId) }.map { it.activity }

    suspend fun balance(groupId: String): ApiResult<BalanceDto> =
        apiCall { api.groupBalance(groupId) }.map { it.balance }

    private fun GroupDto.toEntity() = GroupEntity(
        id = id,
        name = name,
        iconKey = iconKey,
        currency = currency,
        memberCount = members.count { !it.user.pending },
        pendingCount = members.count { it.user.pending },
        netMinor = balance?.netMinor ?: 0,
        owedToYouMinor = balance?.owedToYouMinor ?: 0,
        owedByYouMinor = balance?.owedByYouMinor ?: 0,
        cachedAtEpoch = System.currentTimeMillis()
    )
}
