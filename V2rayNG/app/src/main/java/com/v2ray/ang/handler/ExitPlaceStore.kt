package com.v2ray.ang.handler

import com.tencent.mmkv.MMKV
import com.v2ray.ang.util.JsonUtil

/**
 * The last checked exit of each config, by GUID. Multi-process because the check runs in the
 * core's process and the list is drawn in the UI's.
 */
object ExitPlaceStore {
    private const val ID = "EXIT_PLACE"
    private val storage by lazy { MMKV.mmkvWithID(ID, MMKV.MULTI_PROCESS_MODE) }

    fun get(guid: String?): StoredPlace? {
        if (guid.isNullOrEmpty()) return null
        val json = storage.decodeString(guid) ?: return null
        return JsonUtil.fromJsonSafe(json, StoredPlace::class.java)
    }

    fun record(guid: String?, place: ExitPlace) {
        if (guid.isNullOrEmpty()) return
        val next = StoredPlace.next(get(guid), place, System.currentTimeMillis())
        storage.encode(guid, JsonUtil.toJson(next))
    }

    fun remove(guid: String?) {
        if (!guid.isNullOrEmpty()) storage.removeValueForKey(guid)
    }
}
