#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TMPD="$(mktemp -d)"
trap 'rm -rf "$TMPD"' EXIT
cat > "$TMPD/AndroidStub.kt" <<'KT'
package android.content
import java.io.File
open class Context(open val filesDir: File)
KT
cat > "$TMPD/BaseTypeStubs.kt" <<'KT'
package de.equinox.v05
enum class ForgeRelationPolarity { SUPPORT, OPPOSE, NEUTRAL }
enum class KiraModalityFamily { TEXT, AUDIO, VISION, CROSSMODAL, FORGE, OTHER }
object KiraModalityClassifier { fun familyOf(s:String)= if (s.startsWith("crossmodal") || s.startsWith("joint")) KiraModalityFamily.CROSSMODAL else KiraModalityFamily.TEXT }
object KiraBasePackageSpec { const val MAX_INDEX_ROWS=1_000_000; const val MAX_RELATIONS=2_000_000; const val MAX_TRAITS=2_000_000 }
object KiraBasePackageCodec { fun normalizeSurface(text:String)=text.trim().lowercase().replace(Regex("\\s+"), " ") }
data class BaseSurfaceRecord(val id:Int,val externalId:String,val modality:String,val kind:String,val canonical:String,val confidence:Double=1.0,val support:Long=1)
data class BaseSemanticRelationRecord(val fromId:Int,val toId:Int,val kind:String,val strength:Double,val confidence:Double,val polarity:ForgeRelationPolarity=ForgeRelationPolarity.NEUTRAL,val evidenceType:String="explicit")
data class BaseTraitRecord(val ownerId:Int,val key:String,val numberValue:Double?=null,val textValue:String?=null,val confidence:Double=1.0)
data class CompiledBasePack(val packId:String,val version:Int,val sourceId:String,val sourceVersion:String,val macros:List<IndexedLongTermStore.StoredMacro>,val relations:List<IndexedLongTermStore.StoredRelation>,val namespace:String="",val packageDigest:String="",val highestTier:String="—",val surfaces:List<BaseSurfaceRecord> = emptyList(),val semanticRelations:List<BaseSemanticRelationRecord> = emptyList(),val traits:List<BaseTraitRecord> = emptyList())
class KiraBasePackagePayload { fun toCompiledBasePack():CompiledBasePack = error("not used") }
KT
kotlinc \
  "$TMPD/AndroidStub.kt" "$TMPD/BaseTypeStubs.kt" \
  "$ROOT/app/src/main/java/de/equinox/v05/KnowledgeOrigin.kt" \
  "$ROOT/app/src/main/java/de/equinox/v05/IndexedLongTermStore.kt" \
  "$ROOT/app/src/main/java/de/equinox/v05/BaseKnowledgeRepository.kt" \
  "$ROOT/host_tests/BaseRepositorySelfTest.kt" \
  -include-runtime -d "$TMPD/test.jar"
java -jar "$TMPD/test.jar"
