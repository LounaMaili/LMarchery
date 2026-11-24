package com.lmarchery.data

import com.lmarchery.core.database.ConfiguredSessionReadResult
import com.lmarchery.core.database.ConfiguredWriteResult
import com.lmarchery.core.scoring.configured.ConfiguredSession

/** No homogeneous archive or lossy Session intermediary. */
interface ConfiguredSessionRepository {
    suspend fun readConfiguredSession(uuid: String): ConfiguredSessionReadResult
    suspend fun createConfiguredSession(session: ConfiguredSession): ConfiguredWriteResult
    suspend fun createAtPlace(request: com.lmarchery.core.database.ConfiguredWriteRequest.Create): ConfiguredWriteResult {
        require(request.place == null) { "Le stockage des instantanés est indisponible." }
        return createConfiguredSession(request.proposed)
    }
    suspend fun updateConfiguredSession(expected: ConfiguredSession, proposed: ConfiguredSession): ConfiguredWriteResult
}
