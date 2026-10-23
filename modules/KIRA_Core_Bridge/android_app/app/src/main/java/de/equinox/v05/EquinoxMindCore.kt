package de.equinox.v05

import java.security.MessageDigest
import java.util.ArrayDeque
import kotlin.math.max

/** Equinox Language v0.3 Android port: Tree/Mirror + Forest/Loom + universal semantic fabric. */
object EquinoxLanguageContract {
    const val VERSION = "0.3-android"
    val ISA = setOf("RELATE","SPLIT","MIRROR","UNIFY","DISSONATE","HOLD","WAKE","SEAL","EMIT")
    val CONTINUATIONS = setOf("NEXT","HOLD_INFINITY","SEAL")
}

data class MindTriple(val subject: Long, val relation: Long, val objectId: Long)
data class SymbolicTriple(val subject: String, val relation: String, val objectValue: String) : Comparable<SymbolicTriple> {
    override fun compareTo(other: SymbolicTriple): Int = compareValuesBy(this, other, SymbolicTriple::subject, SymbolicTriple::relation, SymbolicTriple::objectValue)
    val frame: Pair<String,String> get() = subject to relation
}

enum class MindSide { TRUNK, THESIS, ANTITHESIS, SYNTHESIS, DISSONANCE }
enum class MindNodeState { ACTIVE, HOLD_INFINITY, SEALED, EXTINCT }

data class MindTreeNode(
    val nodeId: String,
    val role: MindSide,
    val triples: MutableSet<SymbolicTriple> = linkedSetOf(),
    var parentId: String? = null,
    var state: MindNodeState = MindNodeState.ACTIVE,
    val thesisChildren: MutableList<String> = mutableListOf(),
    val antithesisChildren: MutableList<String> = mutableListOf(),
    var synthesisId: String? = null,
    var dissonanceId: String? = null
)

data class MirrorOutcome(val synthesisId: String, val dissonanceId: String, val sharedFrames: Int, val dissonantPairs: Int)

class MindTree(val name: String) {
    val nodes = linkedMapOf<String, MindTreeNode>()
    val trunkId = "root"
    var state = MindNodeState.ACTIVE
    var version = 0L
    var macroId: Long? = null
    init { nodes[trunkId] = MindTreeNode(trunkId, MindSide.TRUNK) }

    fun branch(parentId: String, side: MindSide, triples: Collection<SymbolicTriple>, name: String): String {
        require(side == MindSide.THESIS || side == MindSide.ANTITHESIS)
        val parent = nodes[parentId] ?: error("unknown parent '$parentId'")
        val raw = buildString {
            append(parentId).append('|').append(side.name).append('|').append(name)
            triples.sorted().forEach { append('|').append(it.subject).append('/').append(it.relation).append('/').append(it.objectValue) }
        }
        var id = "${if (side == MindSide.THESIS) "t" else "a"}_${EquinoxId64.personalized63("EQXNODE1", raw).toString(16)}"
        var suffix = 1
        while (nodes.containsKey(id)) id = "${id}_$suffix".also { suffix++ }
        nodes[id] = MindTreeNode(id, side, triples.toMutableSet(), parentId)
        if (side == MindSide.THESIS) parent.thesisChildren += id else parent.antithesisChildren += id
        mutated()
        return id
    }

    fun mirror(parentId: String): MirrorOutcome {
        val parent = nodes[parentId] ?: error("unknown parent '$parentId'")
        val thesis = parent.thesisChildren.flatMap { nodes[it]?.triples.orEmpty() }.toSet()
        val anti = parent.antithesisChildren.flatMap { nodes[it]?.triples.orEmpty() }.toSet()
        require(thesis.isNotEmpty() && anti.isNotEmpty()) { "mirror requires thesis and antithesis" }
        val exact = thesis intersect anti
        val tFrames = thesis.groupBy { it.frame }
        val aFrames = anti.groupBy { it.frame }
        val sharedFrames = (tFrames.keys intersect aFrames.keys).sortedWith(compareBy<Pair<String,String>> { it.first }.thenBy { it.second })
        val pairs = mutableListOf<Pair<SymbolicTriple,SymbolicTriple>>()
        for (f in sharedFrames) for (l in tFrames[f].orEmpty()) for (r in aFrames[f].orEmpty()) if (l != r) pairs += l to r
        val synthRaw = "S|$parentId|${sharedFrames.joinToString()}|${exact.sorted().joinToString()}"
        val dissRaw = "D|$parentId|${pairs.joinToString { "${it.first}>${it.second}" }}"
        val sId = "s_${EquinoxId64.personalized63("EQXNODE1", synthRaw).toString(16)}"
        val dId = "d_${EquinoxId64.personalized63("EQXNODE1", dissRaw).toString(16)}"
        val synthTriples = exact.toMutableSet()
        for ((sub, rel) in sharedFrames) synthTriples += SymbolicTriple(sub, rel, "*")
        val dissTriples = ((thesis - anti) + (anti - thesis)).toMutableSet()
        nodes[sId] = MindTreeNode(sId, MindSide.SYNTHESIS, synthTriples, parentId)
        nodes[dId] = MindTreeNode(dId, MindSide.DISSONANCE, dissTriples, parentId)
        parent.synthesisId = sId
        parent.dissonanceId = dId
        mutated()
        return MirrorOutcome(sId,dId,sharedFrames.size,pairs.size)
    }

    fun wake(nodeId: String) {
        val n = nodes[nodeId] ?: error("unknown node '$nodeId'")
        require(n.state != MindNodeState.EXTINCT)
        n.state = MindNodeState.ACTIVE
        state = MindNodeState.ACTIVE
    }

    fun refine(nodeId: String, side: MindSide, triple: SymbolicTriple, name: String): String {
        wake(nodeId)
        return branch(nodeId, side, listOf(triple), name)
    }

    fun seal(): Long {
        for (n in nodes.values) if (n.state == MindNodeState.ACTIVE) {
            val structured = n.thesisChildren.isNotEmpty() || n.antithesisChildren.isNotEmpty() || n.synthesisId != null || n.dissonanceId != null
            n.state = if (structured) MindNodeState.SEALED else MindNodeState.HOLD_INFINITY
        }
        state = MindNodeState.SEALED
        val raw = structuralString()
        macroId = EquinoxId64.personalized63("EQXTREE1", raw)
        return macroId!!
    }

    fun extinct() {
        state = MindNodeState.EXTINCT
        nodes.values.forEach { it.state = MindNodeState.EXTINCT }
    }

    fun structuralString(): String = buildString {
        append(name).append('|').append(trunkId)
        nodes.values.sortedBy { it.nodeId }.forEach { n ->
            append('|').append(n.nodeId).append(':').append(n.role.name).append(':').append(n.parentId ?: "-")
            n.triples.sorted().forEach { append(':').append(it.subject).append('/').append(it.relation).append('/').append(it.objectValue) }
            append(':').append(n.thesisChildren.sorted().joinToString(","))
            append(':').append(n.antithesisChildren.sorted().joinToString(","))
            append(':').append(n.synthesisId ?: "-").append(':').append(n.dissonanceId ?: "-")
        }
    }

    private fun mutated() { version++; macroId = null }
}

data class ForestLink(val id: Long, val leftTree: String, val leftNode: String, val relation: String, val rightTree: String, val rightNode: String, val bidirectional: Boolean)
data class ForestWake(val sourceTree: String, val targetTree: String, val targetNode: String, val relation: String, val reason: String)

class MindForest(val name: String) {
    val trees = linkedMapOf<String,MindTree>()
    val links = mutableListOf<ForestLink>()
    private val wakeQueue = ArrayDeque<ForestWake>()
    val wakeHistory = mutableListOf<ForestWake>()
    var macroId: Long? = null

    fun addTree(tree: MindTree) { require(tree.state == MindNodeState.SEALED); trees[tree.name] = tree }
    fun relate(leftTree:String,leftNode:String,relation:String,rightTree:String,rightNode:String,bidirectional:Boolean=true) {
        require(trees.containsKey(leftTree) && trees.containsKey(rightTree))
        val raw="$leftTree|$leftNode|$relation|$rightTree|$rightNode|$bidirectional"
        links += ForestLink(EquinoxId64.personalized63("EQXLINK02",raw),leftTree,leftNode,relation,rightTree,rightNode,bidirectional)
        macroId=null
    }
    fun externalDelta(tree:String,node:String,reason:String="external") { propagate(tree,node,reason) }
    fun structuralDelta(tree:String,node:String,reason:String="refine") { propagate(tree,node,reason) }
    private fun propagate(sourceTree:String, sourceNode:String, reason:String) {
        for (l in links) {
            if (l.leftTree==sourceTree && (l.leftNode==sourceNode || l.leftNode=="*" || sourceNode=="*")) wakeQueue += ForestWake(sourceTree,l.rightTree,l.rightNode,l.relation,reason)
            if (l.bidirectional && l.rightTree==sourceTree && (l.rightNode==sourceNode || l.rightNode=="*" || sourceNode=="*")) wakeQueue += ForestWake(sourceTree,l.leftTree,l.leftNode,l.relation,reason)
        }
    }
    /** Wake never propagates. A downstream tree may emit a new delta only after an explicit structural change. */
    fun run(): List<ForestWake> {
        val out=mutableListOf<ForestWake>()
        while(wakeQueue.isNotEmpty()) {
            val w=wakeQueue.removeFirst(); val tree=trees[w.targetTree] ?: continue
            if (w.targetNode=="*") tree.wake(tree.trunkId) else if(tree.nodes.containsKey(w.targetNode)) tree.wake(w.targetNode) else tree.wake(tree.trunkId)
            wakeHistory += w; out += w
        }
        return out
    }
    fun settle(treeName:String) { trees[treeName]?.seal() }
    fun settleAll() { trees.values.forEach { it.seal() } }
    fun sealForest():Long {
        require(trees.values.all { it.state==MindNodeState.SEALED })
        val raw=buildString {
            append(name)
            trees.values.sortedBy { it.name }.forEach { append('|').append(it.name).append('=').append(it.macroId ?: it.seal()) }
            links.sortedBy { it.id }.forEach { append('|').append(it.id) }
        }
        macroId=EquinoxId64.personalized63("EQXFORE2",raw); return macroId!!
    }
}

data class SymbolicWork(val name:String,val subject:String,val relation:String,val counterpart:String,val operation:String,val context:String,val continuation:String="NEXT",val payload:List<String> = emptyList())
data class SemanticPacket(val packetId:Long,val subjectId:Long,val relationId:Long,val counterpartId:Long,val operationId:Long,val contextId:Long,val continuationId:Long,val payloadIds:List<Long>)
data class CellResult(val packetId:Long,val cellId:Int,val operationId:Long,val contextId:Long,val continuationId:Long,val outputs:List<MindTriple>)

class SemanticCompiler {
    val identities=linkedMapOf<Pair<String,String>,Long>()
    fun ident(ns:String,symbol:String):Long=identities.getOrPut(ns to symbol){EquinoxId64.stableId(ns,symbol)}
    fun compile(w:SymbolicWork):SemanticPacket {
        val op=w.operation.uppercase(); val cont=w.continuation.uppercase()
        require(op in EquinoxLanguageContract.ISA){"unknown operation ${w.operation}"}
        require(cont in EquinoxLanguageContract.CONTINUATIONS){"unknown continuation ${w.continuation}"}
        val s=ident("entity",w.subject); val r=ident("relation",w.relation); val c=ident("entity",w.counterpart)
        val o=ident("operation",op); val ctx=ident("context",w.context); val k=ident("continuation",cont); val p=w.payload.map{ident("payload",it)}
        val raw="{\"context\":$ctx,\"continuation\":$k,\"counterpart\":$c,\"operation\":$o,\"payload\":[${p.joinToString(",")}],\"relation\":$r,\"subject\":$s}"
        val pid=EquinoxId64.personalized63("EQXPACK3",raw)
        identities["packet" to w.name]=pid
        return SemanticPacket(pid,s,r,c,o,ctx,k,p)
    }
}

class UniversalKernel {
    private val op=EquinoxLanguageContract.ISA.associateWith { EquinoxId64.stableId("operation",it) }
    private val rel=mapOf("synthesis" to EquinoxId64.stableId("kernel_relation","synthesis"),"dissonance" to EquinoxId64.stableId("kernel_relation","dissonance"),"thesis" to EquinoxId64.stableId("kernel_relation","thesis"),"antithesis" to EquinoxId64.stableId("kernel_relation","antithesis"),"state" to EquinoxId64.stableId("kernel_relation","state"),"emits" to EquinoxId64.stableId("kernel_relation","emits"))
    private val states=mapOf("active" to EquinoxId64.stableId("kernel_state","active"),"sealed" to EquinoxId64.stableId("kernel_state","sealed"),"hold_infinity" to EquinoxId64.stableId("kernel_state","hold_infinity"))
    private fun derived(p:SemanticPacket,channel:String):Long {
        val raw="[${p.subjectId},${p.relationId},${p.counterpartId},${p.contextId},\"$channel\"]"
        return EquinoxId64.personalized63("EQXOUT03",raw)
    }
    fun execute(p:SemanticPacket):List<MindTriple> = when(p.operationId) {
        op["RELATE"],op["EMIT"] -> listOf(MindTriple(p.subjectId,p.relationId,p.counterpartId))
        op["SPLIT"] -> listOf(MindTriple(p.subjectId,rel.getValue("thesis"),derived(p,"thesis")), MindTriple(p.subjectId,rel.getValue("antithesis"),derived(p,"antithesis")))
        op["MIRROR"] -> listOf(MindTriple(p.contextId,rel.getValue("synthesis"),derived(p,"synthesis")), MindTriple(p.contextId,rel.getValue("dissonance"),derived(p,"dissonance")))
        op["UNIFY"] -> listOf(MindTriple(p.contextId,rel.getValue("synthesis"),derived(p,"synthesis")))
        op["DISSONATE"] -> listOf(MindTriple(p.contextId,rel.getValue("dissonance"),derived(p,"dissonance")))
        op["WAKE"] -> listOf(MindTriple(p.subjectId,rel.getValue("state"),states.getValue("active")))
        op["SEAL"] -> listOf(MindTriple(p.subjectId,rel.getValue("state"),states.getValue("sealed")))
        op["HOLD"] -> listOf(MindTriple(p.subjectId,rel.getValue("state"),states.getValue("hold_infinity")))
        else -> error("unknown operation id ${p.operationId}")
    }
}

class UniversalComputeFabric(val cellCount:Int=1) {
    init { require(cellCount>=1) }
    private val kernel=UniversalKernel(); private val queue=ArrayDeque<SemanticPacket>()
    val results=mutableListOf<CellResult>(); val executionCounts=IntArray(cellCount); var ticks=0; private set
    fun submit(p:SemanticPacket){queue += p}
    fun runUntilIdle():List<CellResult>{
        val start=results.size
        while(queue.isNotEmpty()) {
            var cell=0
            while(cell<cellCount && queue.isNotEmpty()) {
                val p=queue.removeFirst(); val out=kernel.execute(p); executionCounts[cell]++
                results += CellResult(p.packetId,cell,p.operationId,p.contextId,p.continuationId,out); cell++
            }
            ticks++
        }
        return results.drop(start)
    }
}

data class LanguageRunResult(val mode:String,val triples:List<MindTriple>,val summary:String,val identities:Map<Pair<String,String>,Long> = emptyMap(),val trees:List<MindTree> = emptyList(),val forest:MindForest?=null)

class EquinoxLanguageEngine {
    fun run(source:String):LanguageRunResult {
        val first=source.lineSequence().map{it.substringBefore('#').trim()}.firstOrNull{it.isNotBlank()}?.substringBefore(' ')?.uppercase() ?: error("empty program")
        return when(first){"FABRIC"->runFabric(source);"FOREST"->runForest(source);"TREE"->runTree(source);else->error("program must start with TREE, FOREST or FABRIC")}
    }

    private fun tokens(line:String):List<String> {
        val out=mutableListOf<String>(); val sb=StringBuilder(); var quote:Char?=null; var escape=false
        for(ch in line){
            if(escape){sb.append(ch);escape=false;continue}
            if(ch=='\\'){escape=true;continue}
            if(quote!=null){if(ch==quote){quote=null}else sb.append(ch);continue}
            if(ch=='\''||ch=='\"'){quote=ch;continue}
            if(ch.isWhitespace()){if(sb.isNotEmpty()){out+=sb.toString();sb.clear()}} else sb.append(ch)
        }
        if(sb.isNotEmpty()) out+=sb.toString(); return out
    }

    private fun runTree(source:String):LanguageRunResult {
        var tree:MindTree?=null; var mirrors=0
        for((idx,raw) in source.lines().withIndex()){
            val line=raw.substringBefore('#').trim(); if(line.isEmpty()) continue; val p=tokens(line); val op=p[0].uppercase()
            try { when(op){
                "TREE"->{require(tree==null&&p.size==2);tree=MindTree(p[1])}
                "THESIS","ANTITHESIS"->{val t=tree?:error("TREE required");require(p.size==6);t.branch(p[2],if(op=="THESIS")MindSide.THESIS else MindSide.ANTITHESIS,listOf(SymbolicTriple(p[3],p[4],p[5])),p[1])}
                "MIRROR"->{val t=tree?:error("TREE required");require(p.size==2);t.mirror(p[1]);mirrors++}
                "SEAL"->{require(p.size==1);(tree?:error("TREE required")).seal()}
                "WAKE"->{require(p.size==2);(tree?:error("TREE required")).wake(p[1])}
                "EXTINCT"->{require(p.size==1);(tree?:error("TREE required")).extinct()}
                else->error("unknown TREE op $op")
            }}catch(e:Exception){error("line ${idx+1}: ${e.message}")}
        }
        val t=tree?:error("no TREE"); val triples=compileTree(t)
        return LanguageRunResult("TREE",triples,"TREE ${t.name} · nodes ${t.nodes.size} · mirror $mirrors · state ${t.state} · macro ${t.macroId ?: "open"}",trees=listOf(t))
    }

    private fun compileTree(t:MindTree):List<MindTriple>{
        val triples=linkedSetOf<MindTriple>(); val treeId=EquinoxId64.stableId("tree","${t.name}:${t.macroId ?: 0}")
        val has=EquinoxId64.stableId("relation","has_node");val parent=EquinoxId64.stableId("relation","parent");val role=EquinoxId64.stableId("relation","role");val state=EquinoxId64.stableId("relation","state");val asserts=EquinoxId64.stableId("relation","asserts");val sub=EquinoxId64.stableId("relation","subject");val rel=EquinoxId64.stableId("relation","relation");val obj=EquinoxId64.stableId("relation","object")
        for(n in t.nodes.values.sortedBy{it.nodeId}){
            val nid=EquinoxId64.stableId("node","${t.name}:${n.nodeId}"); triples += MindTriple(treeId,has,nid);triples+=MindTriple(nid,role,EquinoxId64.stableId("role",n.role.name.lowercase()));triples+=MindTriple(nid,state,EquinoxId64.stableId("state",n.state.name.lowercase()))
            n.parentId?.let{triples+=MindTriple(nid,parent,EquinoxId64.stableId("node","${t.name}:$it"))}
            n.triples.sorted().forEachIndexed{i,f->val fid=EquinoxId64.stableId("fact","${t.name}:${n.nodeId}:$i:${f.subject}:${f.relation}:${f.objectValue}");triples+=MindTriple(nid,asserts,fid);triples+=MindTriple(fid,sub,EquinoxId64.stableId("concept",f.subject));triples+=MindTriple(fid,rel,EquinoxId64.stableId("relation",f.relation));triples+=MindTriple(fid,obj,EquinoxId64.stableId("concept",f.objectValue))}
        }
        return triples.toList().sortedWith(compareBy<MindTriple>{it.subject}.thenBy{it.relation}.thenBy{it.objectId})
    }

    private fun runFabric(source:String):LanguageRunResult {
        var fabric:UniversalComputeFabric?=null; val compiler=SemanticCompiler(); var name=""; val packets=mutableListOf<SemanticPacket>(); val results=mutableListOf<CellResult>()
        source.lines().forEachIndexed{idx,raw->val line=raw.substringBefore('#').trim();if(line.isEmpty())return@forEachIndexed;val p=tokens(line);try{when(p[0].uppercase()){
            "FABRIC"->{require(fabric==null&&p.size==4&&p[2].equals("CELLS",true));name=p[1];fabric=UniversalComputeFabric(p[3].toInt())}
            "WORK"->{val f=fabric?:error("FABRIC required");require(p.size>=8);val packet=compiler.compile(SymbolicWork(p[1],p[2],p[3],p[4],p[5],p[6],p[7],p.drop(8)));packets+=packet;f.submit(packet)}
            "RUN"->{require(p.size==1);results += (fabric?:error("FABRIC required")).runUntilIdle()}
            else->error("unknown FABRIC op ${p[0]}")
        }}catch(e:Exception){error("line ${idx+1}: ${e.message}")}}
        val f=fabric?:error("no FABRIC"); val triples=results.flatMap{it.outputs}
        return LanguageRunResult("FABRIC",triples,"FABRIC $name · cells ${f.cellCount} · packets ${packets.size} · ticks ${f.ticks} · outputs ${triples.size}",compiler.identities)
    }

    private fun runForest(source:String):LanguageRunResult {
        val lines=source.lines(); var i=0; var forest:MindForest?=null
        while(i<lines.size){val raw=lines[i++];val line=raw.substringBefore('#').trim();if(line.isEmpty())continue;val p=tokens(line);when(p[0].uppercase()){
            "FOREST"->{require(forest==null&&p.size==2);forest=MindForest(p[1])}
            "BEGIN_TREE"->{val f=forest?:error("FOREST required");require(p.size==2);val body=mutableListOf("TREE ${p[1]}");var end=false;while(i<lines.size){val inner=lines[i++];if(inner.substringBefore('#').trim().equals("END_TREE",true)){end=true;break};body+=inner};require(end);val rr=runTree(body.joinToString("\n"));val t=rr.trees.single();require(t.state==MindNodeState.SEALED);f.addTree(t)}
            "RELATE"->{val f=forest?:error("FOREST required");require(p.size==6||p.size==7);f.relate(p[1],p[2],p[3],p[4],p[5],p.getOrNull(6)?.uppercase()!="ONEWAY")}
            "EXTERNAL"->{val f=forest?:error("FOREST required");require(p.size in 3..4);f.externalDelta(p[1],p[2],p.getOrNull(3)?:"external")}
            "REFINE"->{val f=forest?:error("FOREST required");require(p.size==8);val t=f.trees[p[1]]?:error("unknown tree");val side=if(p[3].equals("THESIS",true))MindSide.THESIS else MindSide.ANTITHESIS;t.refine(p[2],side,SymbolicTriple(p[5],p[6],p[7]),p[4]);f.structuralDelta(p[1],p[2])}
            "RUN"->{(forest?:error("FOREST required")).run()}
            "SETTLE"->{require(p.size==2);(forest?:error("FOREST required")).settle(p[1])}
            "SETTLE_ALL"->{(forest?:error("FOREST required")).settleAll()}
            "SEAL_FOREST"->{(forest?:error("FOREST required")).sealForest()}
            else->error("unknown FOREST op ${p[0]}")
        }}
        val f=forest?:error("no FOREST"); val triples=f.trees.values.flatMap{compileTree(it)}.toMutableList()
        val rel=EquinoxId64.stableId("relation","forest_relation")
        f.links.forEach{l->triples+=MindTriple(EquinoxId64.stableId("tree",l.leftTree),rel,EquinoxId64.stableId("tree",l.rightTree))}
        return LanguageRunResult("FOREST",triples.distinct(),"FOREST ${f.name} · trees ${f.trees.size} · links ${f.links.size} · wakes ${f.wakeHistory.size} · macro ${f.macroId ?: "open"}",trees=f.trees.values.toList(),forest=f)
    }
}

fun sha256Hex(bytes:ByteArray):String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
