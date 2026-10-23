package com.lmarchery.feature.configured

import com.lmarchery.backup.V3TestData as f
import com.lmarchery.core.database.*
import com.lmarchery.core.scoring.configured.*
import com.lmarchery.core.model.NormalizedPoint
import com.lmarchery.data.ConfiguredSessionRepository
import kotlinx.coroutines.*
import java.time.*

/** Controller double, NOT SQLite or a physical gesture test. */
internal class EditorHarness(val initial: ConfiguredSession? = null, acquired: Boolean = true, onSaved: () -> Unit = {}) : AutoCloseable {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    var now = f.now.plusSeconds(30)
    val clock = object : Clock() { override fun getZone() = ZoneOffset.UTC; override fun withZone(zone: ZoneId) = this
        override fun instant() = now.also { now = now.plusSeconds(1) } }
    var next = 2000
    var uuids = 0
    var gate = !acquired
    var releases = 0; var refreshes = 0; var reads = 0
    var hold: CompletableDeferred<Unit>? = null
    var readOverride: ConfiguredSessionReadResult? = null
    var write: ((ConfiguredWriteRequest) -> ConfiguredWriteResult)? = null
    var stored = initial
    val requests = mutableListOf<ConfiguredWriteRequest>()
    val repository = object : ConfiguredSessionRepository {
        override suspend fun readConfiguredSession(uuid: String): ConfiguredSessionReadResult {
            reads++; return readOverride ?: stored?.let { ConfiguredSessionReadResult.Found(it) } ?: ConfiguredSessionReadResult.NotFound
        }
        override suspend fun createConfiguredSession(session: ConfiguredSession) = execute(ConfiguredWriteRequest.Create(session))
        override suspend fun updateConfiguredSession(expected: ConfiguredSession, proposed: ConfiguredSession) = execute(ConfiguredWriteRequest.Update(expected, proposed))
        private suspend fun execute(request: ConfiguredWriteRequest): ConfiguredWriteResult {
            requests += request; hold?.await()
            write?.let { return it(request) }
            stored = request.proposed
            return when (request) { is ConfiguredWriteRequest.Create -> ConfiguredWriteResult.Created(request)
                is ConfiguredWriteRequest.Update -> ConfiguredWriteResult.Updated(request) }
        }
    }
    val c = ConfiguredSessionController(repository, scope, { if (gate) false else { gate = true; true } },
        { gate = false; releases++ }, { refreshes++ }, clock = clock, zone = { ZoneId.of("Europe/Paris") },
        newUuid = { uuids++; f.id(next++) }, onSaved = onSaved)
    val ui get() = c.state.value
    val token get() = ui.form!!.token
    val ctx get() = ui.targetContext!!
    fun open() = c.open(initial!!.uuid)
    fun draft() { c.create(); c.title(token," Synthétique 🏹 "); c.confirmForm(token) }
    fun editing() { draft(); c.start(); nextEnd() }
    fun nextEnd() { c.nextEnd(); c.confirmForm(token) }
    fun tap(r: Float) = c.tapped(ctx, NormalizedPoint(r, 0f))
    fun selectFirst() = c.selectImpact(ctx, ui.selectedEnd!!.end.impacts.first().uuid)
    fun closeSelection() = c.closeSelection(ctx)
    override fun close() = scope.cancel()
}
