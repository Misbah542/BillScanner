package com.snaptab.app.data.repository

import com.snaptab.app.core.ApiResult
import com.snaptab.app.core.map
import com.snaptab.app.data.local.CategoryDao
import com.snaptab.app.data.local.CategoryEntity
import com.snaptab.app.data.remote.SnapTabApi
import com.snaptab.app.data.remote.apiCall
import com.snaptab.app.data.remote.dto.CategoryDto
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The category list. Cached so the chips are there instantly and offline, and refreshed
 * because the taxonomy is server-owned — a new category starts working without an app
 * release.
 */
@Singleton
class CategoryRepository @Inject constructor(
    private val api: SnapTabApi,
    private val dao: CategoryDao
) {

    fun observe(): Flow<List<CategoryEntity>> = dao.observe()

    suspend fun bySlug(slug: String): CategoryEntity? = dao.bySlug(slug)

    suspend fun refresh(): ApiResult<List<CategoryDto>> =
        apiCall { api.categories() }.map { response ->
            dao.upsert(
                response.categories.mapIndexed { index, category ->
                    CategoryEntity(
                        slug = category.slug,
                        name = category.name,
                        iconKey = category.iconKey,
                        colorHex = category.colorHex,
                        tintHex = category.tintHex,
                        kind = category.kind,
                        custom = category.custom,
                        sortOrder = index
                    )
                }
            )
            response.categories
        }
}
