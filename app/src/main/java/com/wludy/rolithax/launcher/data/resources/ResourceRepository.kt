package com.wludy.rolithax.launcher.data.resources

import androidx.paging.ExperimentalPagingApi
import androidx.paging.LoadType
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.RemoteMediator
import androidx.paging.PagingState
import androidx.paging.map
import androidx.room.withTransaction
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.wludy.rolithax.launcher.data.remote.MslServerCoreApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import retrofit2.HttpException

class ResourceRepository(
    private val database: ResourceDatabase,
    providers: List<ResourceProvider>,
    private val mslApi: MslServerCoreApi,
) {
    private val dao = database.resourceDao()
    private val gson = Gson()
    private val providerRegistry = providers.associateBy { it.id.lowercase() }

    @OptIn(ExperimentalPagingApi::class)
    fun projects(filter: ResourceFilter): Flow<PagingData<ResourceProject>> =
        Pager(
            config = PagingConfig(pageSize = PAGE_SIZE, initialLoadSize = PAGE_SIZE, enablePlaceholders = false),
            remoteMediator = ResourceRemoteMediator(database, providerFor(filter.providerId), filter),
            pagingSourceFactory = { dao.pagingSource(filter.key) },
        ).flow
            .mapEntities()

    suspend fun versions(projectId: String, filter: ResourceFilter): List<ResourceVersion> {
        val cacheProjectId = "${filter.providerId.lowercase()}:$projectId"
        val cached = dao.versions(cacheProjectId)
            .mapNotNull { runCatching { it.toDomain(gson) }.getOrNull() }
            .filter { version ->
                (filter.gameVersion.isBlank() || version.gameVersions.isEmpty() || filter.gameVersion in version.gameVersions) &&
                    (filter.loader.isBlank() || version.loaders.isEmpty() || version.loaders.any { it.equals(filter.loader, ignoreCase = true) })
            }
        return runCatching {
            val parsed = providerFor(filter.providerId).versions(projectId, filter)
            dao.putVersions(parsed.map { it.toEntity(gson, cacheProjectId) })
            parsed
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            cached
        }
    }

    suspend fun project(projectId: String, providerId: String): ResourceProject? = try {
        providerFor(providerId).project(projectId)
    } catch (error: CancellationException) {
        throw error
    } catch (_: Throwable) {
        null
    }

    suspend fun addToCart(instanceKey: String, project: ResourceProject, version: ResourceVersion, providerId: String) {
        val source = providerFor(providerId).id.lowercase()
        val id = "$source:${project.id}"
        dao.putCartItem(
            ResourceCartEntity(
                projectId = id,
                instanceKey = instanceKey,
                provider = source,
                type = project.type,
                versionId = version.id,
                projectJson = gson.toJson(project),
                versionJson = gson.toJson(version),
                addedAt = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun removeFromCart(instanceKey: String, providerId: String, projectId: String) =
        dao.removeCartItem(instanceKey, "${providerId.lowercase()}:$projectId")

    suspend fun cart(instanceKey: String): List<ResourceCartEntry> = dao.cart(instanceKey).mapNotNull { row ->
        runCatching {
            ResourceCartEntry(
                gson.fromJson(row.projectJson, ResourceProject::class.java),
                gson.fromJson(row.versionJson, ResourceVersion::class.java),
                row.provider,
            )
        }.getOrNull()
    }

    suspend fun clearCart(instanceKey: String) = dao.clearCart(instanceKey)

    suspend fun validateCart(
        instanceKey: String,
        entries: List<ResourceCartEntry>,
        gameVersion: String,
        loader: String,
    ): List<ResourceIssue> {
        val issues = mutableListOf<ResourceIssue>()
        val selected = entries.associateBy { resourceKey(it.providerId, it.project.id) }.toMutableMap()
        val queue = ArrayDeque(entries)
        val checked = mutableSetOf<String>()
        while (queue.isNotEmpty()) {
            val entry = queue.removeFirst()
            if (!checked.add(resourceKey(entry.providerId, entry.project.id))) continue
            if (checked.size > MAX_DEPENDENCIES) {
                issues += ResourceIssue(ResourceIssue.Severity.BLOCKING, "依赖数量超过安全上限，无法继续检查")
                break
            }
            entry.version.dependencies.forEach { dependency ->
                val projectId = dependency.projectId ?: dependency.versionId?.let { projectIdForVersion(entry.providerId, it) }
                if (projectId.isNullOrBlank()) {
                    if (dependency.type == "required") {
                        issues += ResourceIssue(ResourceIssue.Severity.BLOCKING, "${entry.project.name} 有无法识别的必需依赖")
                    }
                    return@forEach
                }
                val normalizedId = resourceKey(entry.providerId, projectId)
                when (dependency.type) {
                    "incompatible" -> selected[normalizedId]?.let { existing ->
                        issues += ResourceIssue(
                            ResourceIssue.Severity.BLOCKING,
                            "${entry.project.name} 与 ${existing.project.name} 不兼容",
                            setOf(entry.project.id, existing.project.id),
                        )
                    }
                    "required" -> {
                        val existing = selected[normalizedId]
                        if (existing != null) {
                            if (!dependency.versionId.isNullOrBlank() && existing.version.id != dependency.versionId) {
                                issues += ResourceIssue(
                                    ResourceIssue.Severity.BLOCKING,
                                    "${existing.project.name} 需要指定版本 ${dependency.versionId}",
                                    setOf(entry.project.id, existing.project.id),
                                )
                            }
                        } else {
                            val requiredProject = project(projectId, entry.providerId)
                            val type = requiredProject?.type ?: "mod"
                            val candidates = versions(
                                projectId,
                                ResourceFilter(type, gameVersion = gameVersion, loader = loader, providerId = entry.providerId),
                            )
                            val requiredVersion = dependency.versionId?.let { id -> candidates.firstOrNull { it.id == id } }
                                ?: candidates.firstOrNull { it.serverCompatible }
                            if (requiredProject != null && requiredVersion != null && requiredProject.serverCompatible) {
                                val added = ResourceCartEntry(requiredProject, requiredVersion, entry.providerId)
                                selected[normalizedId] = added
                                queue.addLast(added)
                                issues += ResourceIssue(ResourceIssue.Severity.INFO, "已加入必需依赖：${requiredProject.name}", setOf(projectId))
                            } else {
                                issues += ResourceIssue(
                                    ResourceIssue.Severity.BLOCKING,
                                    "缺少必需依赖 ${requiredProject?.name ?: projectId}，无法找到服务端兼容版本",
                                    setOf(entry.project.id, projectId),
                                )
                            }
                        }
                    }
                }
            }
            if (gameVersion.isNotBlank() && entry.version.gameVersions.isNotEmpty() && gameVersion !in entry.version.gameVersions) {
                issues += ResourceIssue(ResourceIssue.Severity.BLOCKING, "${entry.project.name} 不支持 Minecraft $gameVersion", setOf(entry.project.id))
            }
            if (loader.isNotBlank() && entry.version.loaders.isNotEmpty() && entry.version.loaders.none { it.equals(loader, ignoreCase = true) }) {
                issues += ResourceIssue(ResourceIssue.Severity.BLOCKING, "${entry.project.name} 不支持 $loader", setOf(entry.project.id))
            }
            if (!entry.project.serverCompatible || !entry.version.serverCompatible) {
                issues += ResourceIssue(ResourceIssue.Severity.BLOCKING, "${entry.project.name} 不支持服务端安装", setOf(entry.project.id))
            }
        }

        val originalIds = entries.mapTo(mutableSetOf()) { resourceKey(it.providerId, it.project.id) }
        selected.values.filter { resourceKey(it.providerId, it.project.id) !in originalIds }
            .forEach { addToCart(instanceKey, it.project, it.version, it.providerId) }
        return issues.distinctBy { it.message }
    }

    private suspend fun projectIdForVersion(providerId: String, versionId: String): String? =
        providerFor(providerId).projectIdForVersion(versionId)

    private fun providerFor(providerId: String): ResourceProvider =
        providerRegistry[providerId.lowercase()] ?: error("尚未配置资源来源：$providerId")

    private fun resourceKey(providerId: String, projectId: String): String =
        "${providerId.lowercase()}:${projectId.lowercase()}"

    suspend fun mslApiServerCores(): List<ResourceProject> = runCatching {
        val data = mslApi.classify().data ?: throw IllegalStateException("MSLAPI 未返回核心列表")
        val root = data.asRootObject()
        val result = root.entrySet().flatMap { (group, value) ->
            value.asJsonArrayOrEmpty().mapNotNull { core ->
                core.takeIf(JsonElement::isJsonPrimitive)?.asString?.let { name ->
                    ResourceProject(
                        id = name,
                        name = name,
                        summary = group.toCoreLabel(),
                        author = "MSLAPI",
                        iconUrl = null,
                        downloads = 0,
                        updatedAt = "",
                        type = "core",
                        loaders = emptyList(),
                        gameVersions = emptyList(),
                        serverCompatible = true,
                    )
                }
            }
        }.distinctBy(ResourceProject::id)
        database.withTransaction {
            dao.clearItems(CORE_QUERY_KEY)
            dao.putItems(result.mapIndexed { index, project -> project.toEntity(CORE_QUERY_KEY, index) })
        }
        result
    }.getOrElse {
        database.withTransaction {
            dao.pagingSource(CORE_QUERY_KEY).load(
                androidx.paging.PagingSource.LoadParams.Refresh(null, 100, false),
            ).let { page ->
                (page as? androidx.paging.PagingSource.LoadResult.Page)?.data.orEmpty().map(ResourceItemEntity::toDomain)
            }
        }
    }

    suspend fun mslApiCoreVersions(core: String): List<String> = runCatching {
        val data = mslApi.gameVersion(core).data ?: return@runCatching emptyList()
        val root = data.asRootObject()
        root.getAsJsonArray("versions")?.mapNotNull { it.takeIf(JsonElement::isJsonPrimitive)?.asString }.orEmpty()
    }.getOrDefault(emptyList())

    private fun JsonElement.asRootObject(): JsonObject = when {
        isJsonObject -> asJsonObject
        isJsonArray -> asJsonArray.firstOrNull()?.takeIf(JsonElement::isJsonObject)?.asJsonObject ?: JsonObject()
        else -> JsonObject()
    }

    private fun JsonElement?.asJsonArrayOrEmpty(): JsonArray = this?.takeIf(JsonElement::isJsonArray)?.asJsonArray ?: JsonArray()

    private fun String.toCoreLabel(): String = when (this) {
        "pluginsCore" -> "插件服务端"
        "pluginsAndModsCore_Forge" -> "Forge 混合服务端"
        "pluginsAndModsCore_Fabric" -> "Fabric 混合服务端"
        "modsCore_Forge" -> "Forge / NeoForge 模组服务端"
        "modsCore_Fabric" -> "Fabric 模组服务端"
        "vanillaCore" -> "原版服务端"
        "bedrockCore" -> "基岩版服务端"
        "proxyCore" -> "代理核心"
        else -> "服务端核心"
    }

    companion object {
        const val PAGE_SIZE = 20
        const val CORE_QUERY_KEY = "mslapi:server-cores"
        private const val MAX_DEPENDENCIES = 100
    }
}

@OptIn(ExperimentalPagingApi::class)
private class ResourceRemoteMediator(
    private val database: ResourceDatabase,
    private val provider: ResourceProvider,
    private val filter: ResourceFilter,
) : RemoteMediator<Int, ResourceItemEntity>() {
    private val dao = database.resourceDao()

    override suspend fun initialize(): InitializeAction = InitializeAction.LAUNCH_INITIAL_REFRESH

    override suspend fun load(loadType: LoadType, state: PagingState<Int, ResourceItemEntity>): MediatorResult {
        return try {
            val sync = dao.syncInfo(filter.key)
            if (loadType == LoadType.APPEND && sync?.endReached == true) return MediatorResult.Success(true)
            var offset = if (loadType == LoadType.REFRESH) 0 else sync?.nextOffset ?: 0
            val etag = if (loadType == LoadType.REFRESH) sync?.etag else null
            var lastTag: String? = sync?.etag
            val refreshOffset = if (loadType == LoadType.REFRESH) maxOf(sync?.nextOffset ?: 0, state.config.initialLoadSize) else 0
            val desiredItems = if (loadType == LoadType.REFRESH) state.config.initialLoadSize else state.config.pageSize
            val maxAttempts = if (loadType == LoadType.REFRESH && sync != null) {
                maxOf(MAX_EMPTY_PAGE_SCAN, refreshOffset / ResourceRepository.PAGE_SIZE + 2)
            } else {
                MAX_EMPTY_PAGE_SCAN
            }
            var total = sync?.totalHits ?: 0
            var endReached = false
            val items = mutableListOf<ResourceItemEntity>()
            var rawFetched = 0
            var attempts = 0
            do {
                val page = provider.search(filter, ResourceRepository.PAGE_SIZE, offset, if (attempts == 0) etag else null)
                if (page.notModified) {
                    sync?.let { cached ->
                        dao.saveSyncInfo(cached.copy(syncedAt = System.currentTimeMillis()))
                    }
                    return MediatorResult.Success(sync?.endReached ?: true)
                }
                if (offset == 0) lastTag = page.etag ?: lastTag
                total = page.totalHits.takeIf { it > 0 } ?: total
                rawFetched = page.resultCount
                val pageItems = page.projects.filter { !filter.serverOnly || it.serverCompatible }
                val start = if (loadType == LoadType.REFRESH) items.size else dao.itemCount(filter.key) + items.size
                items += pageItems.mapIndexed { index, project -> project.toEntity(filter.key, start + index) }
                offset += rawFetched
                endReached = rawFetched == 0 || offset >= total
                attempts++
            } while (!endReached && attempts < maxAttempts && (items.size < desiredItems || (loadType == LoadType.REFRESH && offset < refreshOffset)))

            database.withTransaction {
                if (loadType == LoadType.REFRESH) dao.clearItems(filter.key)
                dao.putItems(items)
                dao.saveSyncInfo(
                    ResourceSyncEntity(
                        queryKey = filter.key,
                        nextOffset = offset,
                        totalHits = total,
                        endReached = endReached,
                        syncedAt = System.currentTimeMillis(),
                        etag = lastTag,
                    ),
                )
            }
            MediatorResult.Success(endReached)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            MediatorResult.Error(error)
        }
    }

    companion object {
        private const val MAX_EMPTY_PAGE_SCAN = 5
    }
}

private fun Flow<PagingData<ResourceItemEntity>>.mapEntities(): Flow<PagingData<ResourceProject>> =
    map { data -> data.map(ResourceItemEntity::toDomain) }

private fun ResourceProject.toEntity(queryKey: String, position: Int) = ResourceItemEntity(
    queryKey = queryKey,
    position = position,
    id = id,
    name = name,
    summary = summary,
    author = author,
    iconUrl = iconUrl,
    downloads = downloads,
    updatedAt = updatedAt,
    type = type,
    loaders = loaders.joinToString(","),
    gameVersions = gameVersions.joinToString(","),
    serverCompatible = serverCompatible,
    clientCompatible = clientCompatible,
)

private fun ResourceItemEntity.toDomain() = ResourceProject(
    id = id,
    name = name,
    summary = summary,
    author = author,
    iconUrl = iconUrl,
    downloads = downloads,
    updatedAt = updatedAt,
    type = type,
    loaders = loaders.split(',').filter(String::isNotBlank),
    gameVersions = gameVersions.split(',').filter(String::isNotBlank),
    serverCompatible = serverCompatible,
    clientCompatible = clientCompatible,
)

private fun parseVersion(json: JsonObject): ResourceVersion? {
    val id = json.get("id")?.takeIf(JsonElement::isJsonPrimitive)?.asString ?: return null
    val files = json.getAsJsonArray("files")?.mapNotNull { item ->
        val file = item.asJsonObject
        val hashes = file.getAsJsonObject("hashes")
        val sha512 = hashes?.get("sha512")?.takeIf(JsonElement::isJsonPrimitive)?.asString.orEmpty()
        val sha256 = hashes?.get("sha256")?.takeIf(JsonElement::isJsonPrimitive)?.asString.orEmpty()
        val url = file.get("url")?.takeIf(JsonElement::isJsonPrimitive)?.asString ?: return@mapNotNull null
        if (sha512.isBlank() && sha256.isBlank()) return@mapNotNull null
        ResourceFile(
            filename = file.get("filename")?.takeIf(JsonElement::isJsonPrimitive)?.asString.orEmpty(),
            url = url,
            sha512 = if (sha512.isNotBlank()) "sha512:$sha512" else "sha256:$sha256",
            size = file.get("size")?.asLong ?: 0,
            primary = file.get("primary")?.asBoolean ?: false,
        )
    }.orEmpty()
    if (files.isEmpty()) return null
    val dependencies = json.getAsJsonArray("dependencies")?.mapNotNull { item ->
        val dependency = item.asJsonObject
        ResourceDependency(
            projectId = dependency.get("project_id")?.takeIf(JsonElement::isJsonPrimitive)?.asString,
            versionId = dependency.get("version_id")?.takeIf(JsonElement::isJsonPrimitive)?.asString,
            type = dependency.get("dependency_type")?.takeIf(JsonElement::isJsonPrimitive)?.asString.orEmpty(),
        )
    }.orEmpty()
    val environment = json.get("server_side")?.takeIf(JsonElement::isJsonPrimitive)?.asString
    return ResourceVersion(
        id = id,
        name = json.get("name")?.takeIf(JsonElement::isJsonPrimitive)?.asString.orEmpty(),
        number = json.get("version_number")?.takeIf(JsonElement::isJsonPrimitive)?.asString.orEmpty(),
        gameVersions = (json.getAsJsonArray("game_versions") ?: JsonArray()).mapNotNull { it.stringOrNull() },
        loaders = (json.getAsJsonArray("loaders") ?: JsonArray()).mapNotNull { it.stringOrNull() },
        files = files,
        dependencies = dependencies,
        serverCompatible = environment != "unsupported",
    )
}

private fun ResourceVersion.toEntity(gson: Gson, projectId: String) = ResourceVersionEntity(
    projectId = projectId,
    id = id,
    name = name,
    number = number,
    gameVersions = gson.toJson(gameVersions),
    loaders = gson.toJson(loaders),
    filesJson = gson.toJson(files),
    dependenciesJson = gson.toJson(dependencies),
    serverCompatible = serverCompatible,
)

private fun ResourceVersionEntity.toDomain(gson: Gson) = ResourceVersion(
    id = id,
    name = name,
    number = number,
    gameVersions = gson.fromJson(gameVersions, Array<String>::class.java)?.toList().orEmpty(),
    loaders = gson.fromJson(loaders, Array<String>::class.java)?.toList().orEmpty(),
    files = gson.fromJson(filesJson, Array<ResourceFile>::class.java)?.toList().orEmpty(),
    dependencies = gson.fromJson(dependenciesJson, Array<ResourceDependency>::class.java)?.toList().orEmpty(),
    serverCompatible = serverCompatible,
)

private fun JsonArray.asStringValues(): List<String> = mapNotNull(JsonElement::stringOrNull)

private fun JsonElement?.asStringValues(): List<String> =
    this?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull(JsonElement::stringOrNull).orEmpty()

private fun JsonElement?.stringOrNull(): String? =
    this?.takeIf { it.isJsonPrimitive }?.asString

private fun parseProject(json: JsonObject, fallbackType: String): ResourceProject {
    val environment = json.getAsJsonObject("project_loader_fields")?.get("environment")
        ?: json.get("environment")
    val env = environment.asStringValues().ifEmpty { environment.stringOrNull()?.let(::listOf).orEmpty() }
    val clientSide = json.get("client_side").asStringValues()
    val serverSide = json.get("server_side").asStringValues()
    val projectType = json.getAsJsonArray("project_types")?.firstOrNull()?.stringOrNull()
        ?: json.get("project_type").stringOrNull()
        ?: fallbackType
    return ResourceProject(
        id = json.get("project_id").stringOrNull() ?: json.get("id").stringOrNull().orEmpty(),
        name = json.get("name").stringOrNull() ?: json.get("title").stringOrNull().orEmpty(),
        summary = json.get("summary").stringOrNull() ?: json.get("description").stringOrNull().orEmpty(),
        author = json.get("author").stringOrNull().orEmpty(),
        iconUrl = json.get("icon_url").stringOrNull(),
        downloads = json.get("downloads")?.asLong ?: 0,
        updatedAt = json.get("date_modified").stringOrNull().orEmpty(),
        type = projectType,
        loaders = json.getAsJsonArray("loaders").asStringValues(),
        gameVersions = json.getAsJsonObject("project_loader_fields")?.getAsJsonArray("game_versions").asStringValues()
            .ifEmpty { json.getAsJsonArray("game_versions").asStringValues() },
        serverCompatible = "client_only" !in env && "unsupported" !in serverSide,
        clientCompatible = "server_only" !in env && "unsupported" !in clientSide,
    )
}
