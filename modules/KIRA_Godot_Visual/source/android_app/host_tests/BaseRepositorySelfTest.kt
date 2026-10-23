import de.equinox.v05.*
import android.content.Context
import java.io.File

fun main() {
    val root = createTempDir(prefix = "kira-base-repo-")
    try {
        val context = Context(root)
        fun pack(version: Int, suffix: String): CompiledBasePack {
            val s1 = BaseSurfaceRecord(KnowledgeIdSpace.BASE_MACRO_MIN + 10, "a:$suffix", "text", "atom:concept", "Vogel$suffix")
            val s2 = BaseSurfaceRecord(KnowledgeIdSpace.BASE_MACRO_MIN + 11, "b:$suffix", "text", "atom:feature", "fliegen$suffix")
            val m = IndexedLongTermStore.StoredMacro(
                id = KnowledgeIdSpace.BASE_MACRO_MIN + 100,
                level = 1,
                modality = "text",
                tokens = listOf(s1.id, s2.id),
                weight = 10,
                hz = 0.0,
                firstObs = 0,
                lastObs = 0,
                signature = "test-$version",
            )
            return CompiledBasePack(
                packId = "demo",
                version = version,
                sourceId = "teacher",
                sourceVersion = "$version",
                macros = listOf(m),
                relations = emptyList(),
                namespace = "demo.namespace",
                packageDigest = "digest-$version",
                highestTier = "F",
                surfaces = listOf(s1, s2),
                semanticRelations = listOf(BaseSemanticRelationRecord(s1.id, s2.id, "association", .8, .9)),
                traits = listOf(BaseTraitRecord(s1.id, "animate", numberValue = 1.0, confidence = .9)),
            )
        }

        val repo = BaseKnowledgeRepository(context)
        check(!repo.manifest().installed)
        val one = repo.install(pack(1, ""))
        check(one.success)
        check(repo.manifest().version == 1)
        check(repo.surfaceMatches("Vogel").single().canonical == "Vogel")
        check(!repo.canRollback())

        val two = repo.install(pack(2, "2"))
        check(two.success)
        check(repo.manifest().version == 2)
        check(repo.canRollback())
        check(repo.surfaceMatches("Vogel2").isNotEmpty())

        val rollback = repo.rollback()
        check(rollback.success)
        check(repo.manifest().version == 1)
        check(repo.surfaceMatches("Vogel").isNotEmpty())

        val removed = repo.removeActive()
        check(removed.success)
        check(!repo.manifest().installed)
        check(repo.canRollback())
        check(repo.rollback().success)
        check(repo.manifest().installed)
        println("BaseRepositorySelfTest OK")
    } finally {
        root.deleteRecursively()
    }
}
