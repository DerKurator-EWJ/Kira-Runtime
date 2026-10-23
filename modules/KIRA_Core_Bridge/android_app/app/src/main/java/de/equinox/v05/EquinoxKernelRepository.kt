package de.equinox.v05

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.max

private const val FORGE_KERNEL_FORMAT = "equinox-unified-numeric-execution-kernel-v1"
private const val FORGE_KERNEL_VERSION = "0.55"

data class KernelFactAndroid(val subject:Long,val predicate:Long,val objectId:Long,val score:Double,val confidence:Double,val sourceCount:Int,val evidenceCount:Int){val quality:Double get()=(score*confidence).coerceIn(0.0,1.0)}
data class KernelSurfaceAndroid(val surface:String,val modality:String,val locale:String,val conceptId:Long)
data class KernelShortcutAndroid(val conceptId:Long,val predicateId:Long,val macroId:Long,val objectId:Long,val originSubjectId:Long,val depth:Int,val path:List<Long>,val score:Double,val confidence:Double,val sourceCount:Int,val evidenceCount:Int){val quality:Double get()=(score*confidence).coerceIn(0.0,1.0)}
data class KernelRouteAndroid(val queryRelationId:Long,val kind:String,val concrete:List<Long>)
data class KernelValueAndroid(val relationId:Long,val objectId:Long,val routeKind:String,val macroId:Long?,val originSubjectId:Long?,val depth:Int?,val score:Double,val confidence:Double)
data class KernelResolutionAndroid(val status:String,val values:List<KernelValueAndroid>)

data class ForgeKernelPackAndroid(
    val inheritance:Set<Long>, val facts:List<KernelFactAndroid>, val surfaces:List<KernelSurfaceAndroid>,
    val shortcuts:List<KernelShortcutAndroid>, val routes:List<KernelRouteAndroid>, val minQuality:Double,
    val ambiguityMargin:Double,val maxParentDepth:Int,val digest:String,val metadata:JSONObject
)

object CanonicalJsonAndroid {
    fun encode(value:Any?):String = when(value){
        null, JSONObject.NULL -> "null"
        is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(prefix="{",postfix="}",separator=","){ k -> JSONObject.quote(k)+":"+encode(value.get(k)) }
        is JSONArray -> (0 until value.length()).joinToString(prefix="[",postfix="]",separator=","){encode(value.get(it))}
        is String -> JSONObject.quote(value)
        is Boolean -> if(value)"true" else "false"
        is Byte,is Short,is Int,is Long -> value.toString()
        is Float -> if(value.isFinite()) value.toString() else error("non-finite JSON number")
        is Double -> if(value.isFinite()) value.toString() else error("non-finite JSON number")
        is Number -> value.toString()
        else -> JSONObject.quote(value.toString())
    }
    fun sha256(value:Any?):String = MessageDigest.getInstance("SHA-256").digest(encode(value).toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it)}
}

object ForgeKernelParserAndroid {
    fun parse(text:String):ForgeKernelPackAndroid {
        val root=JSONObject(text)
        require(root.optString("format")==FORGE_KERNEL_FORMAT){"unsupported Forge kernel format"}
        require(root.optString("version")==FORGE_KERNEL_VERSION){"unsupported Forge kernel version"}
        val expected=root.optString("digest")
        require(expected.isNotBlank()){"kernel digest missing"}
        val body=JSONObject(root.toString());body.remove("digest")
        val actual=CanonicalJsonAndroid.sha256(body)
        require(actual==expected){"kernel digest mismatch"}
        val inh=root.getJSONArray("inheritance_predicate_ids").longs().toSet()
        val facts=root.getJSONArray("facts").objects { row -> require(row.length()==7);KernelFactAndroid(row.getLong(0),row.getLong(1),row.getLong(2),row.getDouble(3),row.getDouble(4),row.getInt(5),row.getInt(6)) }
        val surfaces=root.getJSONArray("surfaces").objects { row -> require(row.length()==4);KernelSurfaceAndroid(row.getString(0),row.getString(1),row.getString(2),row.getLong(3)) }
        val shortcuts=root.getJSONArray("concept_shortcuts").objects { row -> require(row.length()==11);KernelShortcutAndroid(row.getLong(0),row.getLong(1),row.getLong(2),row.getLong(3),row.getLong(4),row.getInt(5),row.getJSONArray(6).longs(),row.getDouble(7),row.getDouble(8),row.getInt(9),row.getInt(10)) }
        val routes=root.getJSONArray("routes").objects { row -> require(row.length()==3);val kind=row.getString(1);require(kind=="scalar"||kind=="relation_set");KernelRouteAndroid(row.getLong(0),kind,row.getJSONArray(2).longs()) }
        val policy=root.optJSONObject("policy")?:JSONObject()
        return ForgeKernelPackAndroid(inh,facts,surfaces,shortcuts,routes,policy.optDouble("min_quality",.5),policy.optDouble("ambiguity_margin",.05),policy.optInt("max_parent_depth",8),expected,root.optJSONObject("metadata")?:JSONObject())
    }
    private fun JSONArray.longs(): List<Long> =(0 until length()).map{getLong(it)}
    private fun <T> JSONArray.objects(block:(JSONArray)->T): List<T> =(0 until length()).map{block(getJSONArray(it))}
}

class ForgeKernelRuntimeAndroid(private val pack:ForgeKernelPackAndroid){
    private val bySP=pack.facts.groupBy{it.subject to it.predicate}.mapValues{(_,v)->v.sortedWith(compareByDescending<KernelFactAndroid>{it.quality}.thenByDescending{it.confidence}.thenBy{it.objectId})}
    private val parents=pack.facts.filter{it.predicate in pack.inheritance && it.quality+1e-15>=pack.minQuality}.groupBy{it.subject}.mapValues{(_,v)->v.map{it.objectId}.distinct().sorted()}
    private val shortcuts=pack.shortcuts.groupBy{it.conceptId to it.predicateId}.mapValues{(_,v)->v.sortedWith(compareByDescending<KernelShortcutAndroid>{it.quality}.thenBy{it.macroId}.thenBy{it.objectId})}
    private val routes=pack.routes.associateBy{it.queryRelationId}
    private val surfaces=pack.surfaces.groupBy{Triple(it.surface,it.modality,it.locale)}.mapValues{(_,v)->v.map{it.conceptId}.distinct().sorted()}

    fun surfaceIds(surface:String,modality:String="text",locale:String=""):List<Long> = surfaces[Triple(surface,modality,locale)].orEmpty()
    private fun levels(subject:Long):List<List<Pair<Long,List<Long>>>>{
        val levels=mutableListOf(listOf(subject to listOf(subject)));val seen=mutableSetOf(subject);var current=levels.first()
        repeat(pack.maxParentDepth){val next=mutableListOf<Pair<Long,List<Long>>>();for((n,path) in current)for(p in parents[n].orEmpty())if(seen.add(p))next+=p to (path+p);if(next.isEmpty())return levels;current=next.sortedBy{it.first};levels+=current}
        return levels
    }
    private fun direct(subject:Long,predicate:Long):Any?{
        for((depth,level) in levels(subject).withIndex()){
            val bestByObj=linkedMapOf<Long,Pair<KernelFactAndroid,List<Long>>>()
            for((origin,path) in level)for(f in bySP[origin to predicate].orEmpty())if(f.quality+1e-15>=pack.minQuality){val old=bestByObj[f.objectId]?.first;if(old==null||f.quality>old.quality||(f.quality==old.quality&&f.confidence>old.confidence))bestByObj[f.objectId]=f to path}
            if(bestByObj.isEmpty())continue
            val ranked=bestByObj.values.sortedWith(compareByDescending<Pair<KernelFactAndroid,List<Long>>>{it.first.quality}.thenByDescending{it.first.confidence}.thenBy{it.first.objectId})
            if(ranked.size>1&&ranked[0].first.objectId!=ranked[1].first.objectId&&(ranked[0].first.quality-ranked[1].first.quality)<pack.ambiguityMargin)return "ambiguous"
            val (f,_)=ranked[0];return Triple(f,depth,f.subject)
        };return null
    }
    private fun scalar(subject:Long,predicate:Long):Any?{
        val local=bySP[subject to predicate].orEmpty().filter{it.quality+1e-15>=pack.minQuality}
        if(local.isNotEmpty())return direct(subject,predicate)
        val ss=shortcuts[subject to predicate].orEmpty();if(ss.isNotEmpty()&&ss.map{it.objectId}.toSet().size==1)return ss.first()
        return direct(subject,predicate)
    }
    fun resolve(subject:Long,queryRelation:Long):KernelResolutionAndroid{
        val route=routes[queryRelation]?:return KernelResolutionAndroid("unknown_relation",emptyList());val values=mutableListOf<KernelValueAndroid>();var amb=false
        for(pid in route.concrete){when(val r=scalar(subject,pid)){
            "ambiguous"->amb=true
            is KernelShortcutAndroid->values+=KernelValueAndroid(pid,r.objectId,"macro",r.macroId,r.originSubjectId,r.depth,r.score,r.confidence)
            is Triple<*,*,*>->{@Suppress("UNCHECKED_CAST") val t=r as Triple<KernelFactAndroid,Int,Long>;val f=t.first;values+=KernelValueAndroid(pid,f.objectId,if(f.subject==subject)"local" else "fallback",null,f.subject,t.second,f.score,f.confidence)}
        }}
        values.sortWith(compareBy<KernelValueAndroid>{it.depth?:Int.MAX_VALUE}.thenBy{it.relationId}.thenBy{it.objectId})
        return KernelResolutionAndroid(if(values.isNotEmpty())"resolved" else if(amb&&route.kind=="scalar")"ambiguous" else "not_found",values)
    }
}

class EquinoxKernelRepository(
    private val context: Context,
    private val memory: MemoryModel,
) {
    private val dir = File(context.filesDir, "equinox_unified").apply { mkdirs() }
    // v0.9.2: these files are migration sources only. New local knowledge lives in MemoryModel's
    // indexed profile store together with macros/candidates/text memory.
    private val legacyLocalFile = File(dir, "local_language_triples.json")
    private val continuityDir = File(context.filesDir, "kira_continuity").apply { mkdirs() }
    private val legacyLocalBackupFile = File(continuityDir, "local_language_triples.json")
    private val forgeFile = File(dir, "forge_base.eqkernel.json")

    var forgePack: ForgeKernelPackAndroid? = null
        private set
    var forgeRuntime: ForgeKernelRuntimeAndroid? = null
        private set

    private var lastExternalActive: List<MindTriple> = emptyList()

    init {
        migrateLegacyLocalMind()
        loadForge()
    }

    @Synchronized
    fun commitLanguage(run: LanguageRunResult) {
        memory.commitMindTriples(run.triples)
    }

    @Synchronized
    fun clearLocal() {
        memory.clearMindTriples()
    }

    @Synchronized
    fun mountForge(text: String): ForgeKernelPackAndroid {
        val pack = ForgeKernelParserAndroid.parse(text)
        forgeFile.writeText(text, Charsets.UTF_8)
        forgePack = pack
        forgeRuntime = ForgeKernelRuntimeAndroid(pack)
        lastExternalActive = pack.facts.take(96).map { MindTriple(it.subject, it.predicate, it.objectId) }
        return pack
    }

    @Synchronized
    fun unmountForge() {
        forgeFile.delete()
        forgePack = null
        forgeRuntime = null
        lastExternalActive = emptyList()
    }

    fun localCount(): Int = memory.mindTripleCount()
    fun externalCount(): Int = forgePack?.facts?.size ?: 0
    fun worldCount(): Int = localCount() + externalCount()

    @Synchronized
    fun activeIdSnapshot(maxIds: Int = 96): List<Long> {
        val local = memory.mindActiveIdSnapshot(maxIds)
        if (local.isNotEmpty()) return local
        return lastExternalActive.asSequence()
            .flatMap { sequenceOf(it.subject, it.relation, it.objectId) }
            .filter { it != 0L }
            .distinct()
            .take(maxIds)
            .toList()
    }

    fun status(): String = "LOCAL ${localCount()} triples · FORGE ${externalCount()} facts" +
        (forgePack?.let { " · ${it.digest.take(12)}…" } ?: " · offline")

    @Synchronized
    fun commitChatMessage(messageId: Long, role: ChatRole, text: String, previousMessageId: Long? = null) {
        memory.commitMindTriples(ChatTextLogic.compileMessageTriples(messageId, role, text, previousMessageId))
    }

    /**
     * Links one text turn to active sensor IDs in the same persistent memory profile.
     * MIND is now only a relation view over MemoryModel; it no longer owns a second JSON DB.
     */
    @Synchronized
    fun commitChatContext(messageId: Long, signals: List<ChatContextSignal>) {
        if (signals.isEmpty()) return
        val contextRel = EquinoxId64.stableId("relation", "chat_context")
        val sourceRel = EquinoxId64.stableId("relation", "context_source")
        val triples = linkedSetOf<MindTriple>()
        signals.sortedWith(compareBy<ChatContextSignal> { it.sourceModality }.thenBy { it.sourceId })
            .take(48)
            .forEach { signal ->
                val contextId = EquinoxId64.stableId("sensor_context", "${signal.sourceModality}:${signal.sourceId}")
                triples += MindTriple(messageId, contextRel, contextId)
                triples += MindTriple(contextId, sourceRel, signal.sourceId and Long.MAX_VALUE)
            }
        memory.commitMindTriples(triples)
    }

    fun workingSnapshot(maxTriples: Int = 36): WorkingSetSnapshot = memory.mindWorkingSnapshot(maxTriples)

    fun queryMemory(query: KiraMemoryQuery): KiraMemoryQueryResult = memory.queryMemory(query)

    fun importLegacyTextTurns(texts: Collection<String>) = memory.importLegacyTextTurns(texts)
    fun textMemorySamples(limit: Int = 400): List<ChatMemorySample> = memory.textMemorySamples(limit)
    fun recallTextMemories(input: String, limit: Int = 5): List<String> = memory.recallTextMemories(input, limit)
    fun textMemoryOccurrence(text: String): Int = memory.textMemoryOccurrence(text)
    fun baseSurfaceMatches(text: String, limit: Int = 8): List<BaseKnowledgeRepository.BaseSurfaceHit> = memory.baseSurfaceMatches(text, limit)

    private fun migrateLegacyLocalMind() {
        val sources = listOf(legacyLocalFile, legacyLocalBackupFile).filter { it.exists() }
        if (sources.isEmpty()) return
        val merged = linkedSetOf<MindTriple>()
        for (source in sources) {
            val restored = runCatching {
                val root = JSONObject(source.readText())
                require(root.optString("format") == "equinox-android-local-mind-v1")
                val rows = root.getJSONArray("triples")
                buildList {
                    for (i in 0 until rows.length()) {
                        val r = rows.getJSONArray(i)
                        add(MindTriple(r.getLong(0), r.getLong(1), r.getLong(2)))
                    }
                }
            }.getOrNull() ?: continue
            merged += restored
        }
        if (merged.isNotEmpty()) memory.commitMindTriples(merged)
        sources.forEach { source ->
            runCatching {
                val migrated = File(source.parentFile, source.name + ".migrated_v092")
                if (migrated.exists()) migrated.delete()
                source.renameTo(migrated)
            }
        }
    }

    private fun loadForge() {
        if (!forgeFile.exists()) return
        runCatching {
            val pack = ForgeKernelParserAndroid.parse(forgeFile.readText())
            forgePack = pack
            forgeRuntime = ForgeKernelRuntimeAndroid(pack)
        }
    }
}
