package com.wludy.rolithax.launcher.data.resources

data class ResourceFilter(
    val type: String,
    val query: String = "",
    val gameVersion: String = "",
    val loader: String = "",
    val serverOnly: Boolean = false,
    val providerId: String = "modrinth",
) {
    val key: String get() = listOf(providerId.lowercase(), type, query.trim().lowercase(), gameVersion, loader, serverOnly).joinToString("|")
}

data class ResourceProject(
    val id: String,
    val name: String,
    val summary: String,
    val author: String,
    val iconUrl: String?,
    val downloads: Long,
    val updatedAt: String,
    val type: String,
    val loaders: List<String>,
    val gameVersions: List<String>,
    val serverCompatible: Boolean,
    val clientCompatible: Boolean = true,
)

data class ResourceFile(
    val filename: String,
    val url: String,
    val sha512: String,
    val size: Long,
    val primary: Boolean,
)

data class ResourceDependency(
    val projectId: String?,
    val versionId: String?,
    val type: String,
)

data class ResourceVersion(
    val id: String,
    val name: String,
    val number: String,
    val gameVersions: List<String>,
    val loaders: List<String>,
    val files: List<ResourceFile>,
    val dependencies: List<ResourceDependency>,
    val serverCompatible: Boolean,
)

data class ResourceCartEntry(
    val project: ResourceProject,
    val version: ResourceVersion,
    val providerId: String = "modrinth",
)

data class ResourceIssue(
    val severity: Severity,
    val message: String,
    val projectIds: Set<String> = emptySet(),
) {
    enum class Severity { INFO, WARNING, BLOCKING }
}
