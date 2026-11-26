package com.umbra.hooks.update

/** Only stable, canonical release tags can become links. */
data class AppUpdate(val tag: String, val downloadUrl: String? = null) {
    val url: String get() = "https://github.com/RHineIx/Umbra/releases/tag/$tag"
}

internal object UpdateRules {
    private val stable = Regex("""v?(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)""")
    private val installed = Regex("""v?(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(-[0-9A-Za-z.-]+)?""")

    private data class Version(val major: Int, val minor: Int, val patch: Int, val prerelease: Boolean) :
        Comparable<Version> {
        override fun compareTo(other: Version): Int {
            if (major != other.major) return major.compareTo(other.major)
            if (minor != other.minor) return minor.compareTo(other.minor)
            if (patch != other.patch) return patch.compareTo(other.patch)
            return (!prerelease).compareTo(!other.prerelease)
        }
    }

    private fun parse(match: MatchResult): Version? {
        return Version(
        match.groupValues[1].toIntOrNull() ?: return null,
        match.groupValues[2].toIntOrNull() ?: return null,
        match.groupValues[3].toIntOrNull() ?: return null,
        match.groupValues.getOrNull(4)?.isNotEmpty() == true,
        )
    }

    fun apkName(tag: String): String = "Umbra-$tag-app-release.apk"

    // The tag and exact asset name are part of the official release workflow.
    // Never accept an arbitrary URL supplied by an IPC caller or release body.
    fun downloadUrl(tag: String, name: String, url: String): String? {
        if (!tag.startsWith("v") || stable.matchEntire(tag)?.let(::parse) == null) return null
        if (name != apkName(tag)) return null
        return url.takeIf { it == "https://github.com/RHineIx/Umbra/releases/download/$tag/$name" }
    }

    fun available(current: String, tag: String, draft: Boolean, prerelease: Boolean): AppUpdate? {
        if (draft || prerelease || !tag.startsWith("v")) return null
        val old = installed.matchEntire(current)?.let(::parse) ?: return null
        val latest = stable.matchEntire(tag)?.let(::parse) ?: return null
        return if (latest > old) AppUpdate(tag) else null
    }
}
