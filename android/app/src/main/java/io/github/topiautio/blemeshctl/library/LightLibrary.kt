package io.github.topiautio.blemeshctl.library

import io.github.topiautio.blemeshctl.script.BleMeshScript
import io.github.topiautio.blemeshctl.script.ScriptStep

/** A light the user has named. */
data class NamedLight(val address: String, val alias: String)

/** A named set of lights, stored by Bluetooth address so renaming a light never breaks it. */
data class LightGroup(val name: String, val members: List<String>)

/** A named `.blemesh` program that has passed the script parser. */
data class Scene(val name: String, val script: String)

/**
 * Flat string entries that persist the library.
 *
 * The Android implementation keeps them in an app-private SharedPreferences
 * file; tests use a map. The entries never contain mesh credentials.
 */
interface LibraryStorage {
    fun load(): Map<String, String>

    fun save(entries: Map<String, String>)
}

/** Keeps entries in memory only. */
class InMemoryLibraryStorage(initial: Map<String, String> = emptyMap()) : LibraryStorage {
    var entries: Map<String, String> = initial.toMap()
        private set

    override fun load(): Map<String, String> = entries

    override fun save(entries: Map<String, String>) {
        this.entries = entries.toMap()
    }
}

/**
 * Light aliases, groups, and scenes, with every rule that keeps them unambiguous.
 *
 * Aliases and group names share one case-insensitive namespace, so a name
 * always resolves to exactly one light or one group. Scene names have their
 * own case-insensitive namespace because a scene is applied to a target rather
 * than being one. Every change is validated completely before it is saved.
 */
class LightLibrary(private val storage: LibraryStorage) {
    private val aliasByAddress = LinkedHashMap<String, String>()
    private val groupsByKey = LinkedHashMap<String, LightGroup>()
    private val scenesByKey = LinkedHashMap<String, Scene>()

    init {
        load(storage.load())
    }

    val lights: List<NamedLight>
        get() = aliasByAddress.map { (address, alias) -> NamedLight(address, alias) }
            .sortedBy { LightNames.key(it.alias) }

    val groups: List<LightGroup>
        get() = groupsByKey.values.sortedBy { LightNames.key(it.name) }

    val scenes: List<Scene>
        get() = scenesByKey.values.sortedBy { LightNames.key(it.name) }

    fun alias(address: String): String? = aliasByAddress[address.uppercase()]

    /** The alias when there is one, otherwise the address itself. */
    fun displayName(address: String): String = alias(address) ?: address.uppercase()

    fun group(name: String): LightGroup? = groupsByKey[LightNames.key(name)]

    fun scene(name: String): Scene? = scenesByKey[LightNames.key(name)]

    /** Every address the library refers to: named lights and group members. */
    val knownAddresses: Set<String>
        get() = aliasByAddress.keys + groupsByKey.values.flatMap { it.members }

    /** Name a light, rename it, or remove its name when [alias] is null or blank. */
    fun setAlias(address: String, alias: String?) {
        val normalizedAddress = LightNames.normalizeAddress(address)
        if (alias.isNullOrBlank()) {
            if (aliasByAddress.remove(normalizedAddress) != null) persist()
            return
        }
        val name = LightNames.validateName(alias, "Light")
        aliasByAddress.entries.firstOrNull { (otherAddress, otherAlias) ->
            otherAddress != normalizedAddress && LightNames.sameName(otherAlias, name)
        }?.let { throw LibraryException("Light name '$name' is already used by ${it.key}") }
        group(name)?.let { throw LibraryException("'$name' is already a group name") }
        aliasByAddress[normalizedAddress] = name
        persist()
    }

    /**
     * Create a group, or replace the group called [replacing] (which may also rename it).
     *
     * Each member is a light alias or a Bluetooth address. An address must be
     * known: named, already a group member, or in [discoveredAddresses].
     */
    fun saveGroup(
        name: String,
        members: List<String>,
        discoveredAddresses: Set<String> = emptySet(),
        replacing: String? = null,
    ): LightGroup {
        val groupName = LightNames.validateName(name, "Group")
        val replaced = replacing?.let { group(it) ?: throw LibraryException("Group '$it' does not exist") }
        aliasByAddress.values.firstOrNull { LightNames.sameName(it, groupName) }?.let {
            throw LibraryException("'$groupName' is already a light name")
        }
        group(groupName)?.takeIf { it != replaced }?.let {
            throw LibraryException("Group '${it.name}' already exists")
        }

        val known = knownAddresses + discoveredAddresses.map { it.uppercase() }
        val resolved = members.map { member -> resolveLight(member, known) }
        if (resolved.isEmpty()) throw LibraryException("Group '$groupName' needs at least one light")
        val duplicate = resolved.groupBy { it }.entries.firstOrNull { it.value.size > 1 }
        if (duplicate != null) {
            throw LibraryException("${displayName(duplicate.key)} is listed more than once in group '$groupName'")
        }

        val group = LightGroup(groupName, resolved)
        if (replaced != null) groupsByKey.remove(LightNames.key(replaced.name))
        groupsByKey[LightNames.key(groupName)] = group
        persist()
        return group
    }

    fun deleteGroup(name: String) {
        groupsByKey.remove(LightNames.key(name)) ?: throw LibraryException("Group '$name' does not exist")
        persist()
    }

    /**
     * Resolve a light alias, group name, or Bluetooth address to the addresses it controls.
     *
     * A bare address resolves to itself even when it is not otherwise known.
     */
    fun resolveTarget(target: String): List<String> {
        if (LightNames.isAddress(target)) return listOf(LightNames.normalizeAddress(target))
        aliasByAddress.entries.firstOrNull { LightNames.sameName(it.value, target) }?.let { return listOf(it.key) }
        group(target)?.let { return it.members }
        throw LibraryException("'${target.trim()}' is not a light name, group, or Bluetooth address")
    }

    /**
     * Validate and save a scene, or replace the scene called [replacing].
     *
     * The whole script must parse before anything is stored.
     */
    fun saveScene(name: String, script: String, replacing: String? = null): Scene {
        val sceneName = LightNames.validateName(name, "Scene")
        val replaced = replacing?.let { scene(it) ?: throw LibraryException("Scene '$it' does not exist") }
        scene(sceneName)?.takeIf { it != replaced }?.let {
            throw LibraryException("Scene '${it.name}' already exists")
        }
        parseScene(sceneName, script)
        val scene = Scene(sceneName, script)
        if (replaced != null) scenesByKey.remove(LightNames.key(replaced.name))
        scenesByKey[LightNames.key(sceneName)] = scene
        persist()
        return scene
    }

    fun renameScene(oldName: String, newName: String): Scene {
        val existing = scene(oldName) ?: throw LibraryException("Scene '$oldName' does not exist")
        return saveScene(newName, existing.script, replacing = existing.name)
    }

    fun deleteScene(name: String) {
        scenesByKey.remove(LightNames.key(name)) ?: throw LibraryException("Scene '$name' does not exist")
        persist()
    }

    /** Parse a stored scene again, so it is always validated just before it runs. */
    fun program(scene: Scene): List<ScriptStep> = parseScene(scene.name, scene.script)

    private fun parseScene(name: String, script: String): List<ScriptStep> {
        if (script.length > MAX_SCENE_CHARACTERS) {
            throw LibraryException("Scene '$name' is longer than $MAX_SCENE_CHARACTERS characters")
        }
        return BleMeshScript.parse(script, "scene '$name'")
    }

    private fun resolveLight(member: String, known: Set<String>): String {
        if (LightNames.isAddress(member)) {
            val address = LightNames.normalizeAddress(member)
            if (address !in known) {
                throw LibraryException("$address is not a known light; scan for it or name it first")
            }
            return address
        }
        aliasByAddress.entries.firstOrNull { LightNames.sameName(it.value, member) }?.let { return it.key }
        if (group(member) != null) throw LibraryException("A group cannot contain another group ('${member.trim()}')")
        throw LibraryException("'${member.trim()}' is not a known light name or Bluetooth address")
    }

    /** Read stored entries, skipping any that are malformed or conflict with earlier ones. */
    private fun load(entries: Map<String, String>) {
        for ((key, value) in entries.toSortedMap()) {
            if (!key.startsWith(ALIAS_PREFIX)) continue
            runCatching {
                val address = LightNames.normalizeAddress(key.removePrefix(ALIAS_PREFIX))
                val alias = LightNames.validateName(value, "Light")
                if (aliasByAddress.values.none { LightNames.sameName(it, alias) }) aliasByAddress[address] = alias
            }
        }
        for ((key, value) in entries.toSortedMap()) {
            if (!key.startsWith(GROUP_PREFIX)) continue
            runCatching {
                val name = LightNames.validateName(key.removePrefix(GROUP_PREFIX), "Group")
                val members = value.split(',').filter { it.isNotBlank() }.map { LightNames.normalizeAddress(it) }.distinct()
                if (members.isNotEmpty() && group(name) == null && aliasByAddress.values.none { LightNames.sameName(it, name) }) {
                    groupsByKey[LightNames.key(name)] = LightGroup(name, members)
                }
            }
        }
        for ((key, value) in entries.toSortedMap()) {
            if (!key.startsWith(SCENE_PREFIX)) continue
            runCatching {
                val name = LightNames.validateName(key.removePrefix(SCENE_PREFIX), "Scene")
                parseScene(name, value)
                if (scene(name) == null) scenesByKey[LightNames.key(name)] = Scene(name, value)
            }
        }
    }

    private fun persist() {
        storage.save(
            buildMap {
                aliasByAddress.forEach { (address, alias) -> put(ALIAS_PREFIX + address, alias) }
                groupsByKey.values.forEach { put(GROUP_PREFIX + it.name, it.members.joinToString(",")) }
                scenesByKey.values.forEach { put(SCENE_PREFIX + it.name, it.script) }
            },
        )
    }

    companion object {
        const val MAX_SCENE_CHARACTERS = 65_536
        const val ALIAS_PREFIX = "alias:"
        const val GROUP_PREFIX = "group:"
        const val SCENE_PREFIX = "scene:"
    }
}
