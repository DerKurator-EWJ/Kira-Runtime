import de.equinox.v05.*

fun main() {
    check(EquinoxId64.stableId("operation", "MIRROR") == 2417519555463692908L)
    check(EquinoxId64.stableId("entity", "Vogel") == 5570551419175242379L)

    val compiler = SemanticCompiler()
    val packet = compiler.compile(SymbolicWork("vogel", "Vogel", "faehigkeit", "fliegen", "MIRROR", "tree:Vogel", "HOLD_INFINITY"))
    check(packet.packetId == 8449144334707028938L)
    val mirror = UniversalKernel().execute(packet)
    check(mirror == listOf(
        MindTriple(7288286911771715807L, 6935198849984321058L, 1906716674818674539L),
        MindTriple(7288286911771715807L, 8639023986085632572L, 7376506345855014652L),
    ))

    val language = EquinoxLanguageEngine()
    val tree = language.run("""
        TREE Vogel
        THESIS flug root Vogel faehigkeit fliegen
        ANTITHESIS boden root Vogel faehigkeit nicht_fliegen
        MIRROR root
        SEAL
    """.trimIndent())
    check(tree.trees.single().nodes.values.any { it.role == MindSide.SYNTHESIS })
    check(tree.trees.single().nodes.values.any { it.role == MindSide.DISSONANCE })

    val forest = language.run("""
        FOREST Welt
        BEGIN_TREE A
        THESIS t root A r plus
        ANTITHESIS a root A r minus
        MIRROR root
        SEAL
        END_TREE
        BEGIN_TREE B
        THESIS t root B r plus
        ANTITHESIS a root B r minus
        MIRROR root
        SEAL
        END_TREE
        BEGIN_TREE C
        THESIS t root C r plus
        ANTITHESIS a root C r minus
        MIRROR root
        SEAL
        END_TREE
        RELATE A * touches B * ONEWAY
        RELATE B * touches C * ONEWAY
        EXTERNAL A root delta
        RUN
    """.trimIndent())
    check(forest.forest!!.wakeHistory.size == 1) // WAKE(B) alone must not propagate to C.
    check(forest.forest!!.trees["C"]!!.state == MindNodeState.SEALED)

    check(EquinoxTerminalIdentity.CURATOR_PREFIX == "Kurator")
    check(EquinoxTerminalIdentity.KIRA_PREFIX == "Kira")
    check(EquinoxTerminalIdentity.COMPOSER_PREFIX == "Kurator")

    val kiraIdentity = ChatTextLogic.planReply("Wer bist du?", 1, emptyList())
    check(kiraIdentity.kind == ChatReplyKind.IDENTITY)
    check(ChatTextLogic.structuralReply("Wer bist du?", kiraIdentity) == "Ich bin Kira.")
    val curatorIdentity = ChatTextLogic.planReply("Wer bin ich?", 1, emptyList())
    check(curatorIdentity.kind == ChatReplyKind.IDENTITY)
    check(ChatTextLogic.structuralReply("Wer bin ich?", curatorIdentity) == "Du bist der Kurator.")
    val renameAttempt = ChatTextLogic.planReply("Nenn dich ab jetzt Anna", 1, emptyList())
    check(renameAttempt.kind == ChatReplyKind.IDENTITY)
    check(ChatTextLogic.structuralReply("Nenn dich ab jetzt Anna", renameAttempt) == "Ich bin Kira.")

    val firstChat = ChatTextLogic.planReply("Vogel kann fliegen", 1, emptyList())
    check(firstChat.kind == ChatReplyKind.UNSTABLE)
    check("keine stabile Antwort" in firstChat.text)
    val relatedChat = ChatTextLogic.planReply(
        "Vogel fliegt",
        1,
        listOf(ChatMemorySample("Vogel kann fliegen"), ChatMemorySample("Wasser ist nass"))
    )
    check(relatedChat.kind == ChatReplyKind.ASSOCIATIVE)
    check(relatedChat.relatedText == "Vogel kann fliegen")
    val repeatedChat = ChatTextLogic.planReply("Omega", 3, emptyList())
    check(repeatedChat.kind == ChatReplyKind.REPEATED)
    val baseChat = ChatTextLogic.planReply("Eule", 1, emptyList(), listOf("Eule"))
    check(baseChat.kind == ChatReplyKind.BASE)
    check("BASE-Treffer" in baseChat.text)
    val mirrorReply = ChatTextLogic.structuralReply("Vogel kann fliegen", firstChat)
    check(mirrorReply == "Verstanden.")
    val mirrorTrace = ChatTextLogic.relationTrace("Vogel kann fliegen", firstChat)
    check("vogel → kann → fliegen ⇄ fliegen ← kann ← vogel" in mirrorTrace)
    check(mirrorTrace.none { it.isDigit() })
    val openQuestionPlan = ChatTextLogic.planReply("Was ist Aeron?", 1, emptyList())
    val openQuestionSurface = ChatTextLogic.resolveReply("Was ist Aeron?", openQuestionPlan)
    check(openQuestionSurface.kind == ChatReplyKind.QUESTION)
    check(openQuestionSurface.text.endsWith("?"))
    check("offen" in openQuestionSurface.text.lowercase())
    val repeatedOpenPlan = ChatTextLogic.planReply("Aeron ist blau", 3, emptyList())
    val repeatedOpenSurface = ChatTextLogic.resolveReply("Aeron ist blau", repeatedOpenPlan)
    check(repeatedOpenSurface.kind == ChatReplyKind.NATIVE)
    check(repeatedOpenSurface.text == "Das ist bereits bekannt.")
    val crossModalOpen = ChatTextLogic.resolveReply(
        "Aeron ist blau",
        ChatTextLogic.planReply("Aeron ist blau", 1, emptyList()),
        contextSignals = listOf(
            ChatContextSignal("audio_live", 11L, .82),
            ChatContextSignal("vision_live", 22L, .88),
        ),
    )
    check(crossModalOpen.kind == ChatReplyKind.QUESTION)
    check("Audio" in crossModalOpen.text && "Kamera" in crossModalOpen.text)

    val questionPlan = ChatTextLogic.planReply(
        "Kann ein Vogel fliegen?",
        1,
        listOf(ChatMemorySample("Vogel kann fliegen"))
    )
    val questionReply = ChatTextLogic.structuralReply("Kann ein Vogel fliegen?", questionPlan)
    check(questionReply == "Ja. Vogel kann fliegen.")
    check(questionReply.none { it.isDigit() })
    val baseMirror = ChatTextLogic.structuralReply("Eule 123", baseChat)
    check(baseMirror == "Verstanden. Dazu ist bereits BASE-Wissen vorhanden.")

    val toastPlan = ChatTextLogic.planReply(
        "Was ist Toastbrot?",
        2,
        listOf(
            ChatMemorySample("Was ist Toastbrot?", ordinal = 0),
            ChatMemorySample("Toastbrot ist ein weiches geschnittenes Brot", ordinal = 1),
            ChatMemorySample("Toastbrot wird oft getoastet", ordinal = 2),
        )
    )
    val toastReply = ChatTextLogic.structuralReply("Was ist Toastbrot?", toastPlan)
    check("was ist toastbrot" !in toastReply.lowercase())
    check(toastReply.startsWith("Toastbrot ist ein weiches geschnittenes Brot."))

    val conflictPlan = ChatTextLogic.planReply(
        "Ist Toastbrot weich?",
        1,
        listOf(
            ChatMemorySample("Toastbrot ist weich", ordinal = 0),
            ChatMemorySample("Toastbrot ist nicht weich", ordinal = 1),
        )
    )
    val thesisFavoured = DialecticDecisionState(thesisStability = .8, antithesisStability = .2, winner = DialecticBranch.THESIS)
    val conflictReply = ChatTextLogic.structuralReply("Ist Toastbrot weich?", conflictPlan, dialectic = thesisFavoured)
    check(conflictReply == "Ja. Toastbrot ist weich.")
    val antiFavoured = DialecticDecisionState(thesisStability = .2, antithesisStability = .9, winner = DialecticBranch.ANTITHESIS)
    val conflictAnti = ChatTextLogic.structuralReply("Ist Toastbrot weich?", conflictPlan, dialectic = antiFavoured)
    check(conflictAnti == "Nein. Toastbrot ist nicht weich.")
    val tiedDialectic = DialecticDecisionState(thesisStability = .51, antithesisStability = .49, winner = DialecticBranch.THESIS)
    val conflictQuestion = ChatTextLogic.resolveReply("Ist Toastbrot weich?", conflictPlan, dialectic = tiedDialectic)
    check(conflictQuestion.kind == ChatReplyKind.QUESTION)
    check("höher gewichten" in conflictQuestion.text)

    val dialectic = DialecticDecisionEngine.evaluate(listOf(
        DialecticEvidence(1, 3, .9, .2, .4, .8, .7, .6, .5, .8, .2, .7, .6),
        DialecticEvidence(2, 5, .2, .9, -.8, .7, .6, .5, .5, .7, .8, .6, .5),
    ))
    check(dialectic.thesisDepth >= 3)
    check(dialectic.antithesisDepth >= 5)
    check(dialectic.tierLabels(DialecticBranch.THESIS).first() == "F")

    // v0.9.4 — one central decision core above the universal F→Ω+n memory projection.
    val stableDecision = KiraDecisionCore.evaluate(listOf(
        KiraDecisionEvidence(
            id = 101, depth = 6, modality = "text", activation = .96, expectation = .18,
            exchangeBias = .85, relevance = .92, maturity = .88, recurrence = .76,
            reuse = .72, relationSupport = .84, contextEntropy = .18, persistence = .81,
            generality = .65,
        ),
        KiraDecisionEvidence(
            id = 102, depth = 4, modality = "vision_adaptive", activation = .82, expectation = .28,
            exchangeBias = .42, relevance = .80, maturity = .71, recurrence = .62,
            reuse = .55, relationSupport = .73, contextEntropy = .24, persistence = .70,
            generality = .52,
        ),
        KiraDecisionEvidence(
            id = 103, depth = 5, modality = "audio_adaptive", activation = .20, expectation = .86,
            exchangeBias = -.75, relevance = .58, maturity = .60, recurrence = .51,
            reuse = .45, relationSupport = .54, contextEntropy = .72, persistence = .56,
            generality = .43,
        ),
    ))
    check(stableDecision.status == KiraDecisionStatus.STABLE)
    check(stableDecision.winner == KiraDecisionBranch.THESIS)
    check(stableDecision.selectedIds().contains(101))
    check(stableDecision.tierLabels(KiraDecisionBranch.THESIS).last() == AbstractionTier.labelForMacroDepth(stableDecision.thesisDepth))

    val openDecision = KiraDecisionCore.evaluate(listOf(
        KiraDecisionEvidence(201, 3, "text", .78, .30, .55, .78, .72, .68, .61, .75, .30, .70, .55),
        KiraDecisionEvidence(202, 3, "text", .30, .78, -.55, .78, .72, .68, .61, .75, .70, .70, .55),
    ))
    check(openDecision.status == KiraDecisionStatus.OPEN)
    check(openDecision.winner == null)
    check(openDecision.openness > 0.5)

    // v0.9.5 — native surface generation: readable answers are a projection of memory/decision,
    // not the internal mirror loop and not a separate learned memory.
    val colorPlan = ChatTextLogic.planReply(
        "Welche Farbe hat Aeron?",
        1,
        listOf(ChatMemorySample("Aeron ist blau")),
    )
    val colorSurface = ChatTextLogic.resolveReply("Welche Farbe hat Aeron?", colorPlan)
    check(colorSurface.kind == ChatReplyKind.NATIVE)
    check(colorSurface.text == "Aeron ist blau.")
    val newFactSurface = ChatTextLogic.resolveReply(
        "Aeron lebt unter Wasser",
        ChatTextLogic.planReply("Aeron lebt unter Wasser", 1, emptyList()),
    )
    check(newFactSurface.kind == ChatReplyKind.NATIVE)
    check(newFactSurface.text == "Verstanden.")


    // v0.9.6 — every one of the nine MASTER cells is a real runtime route.
    val routeSamples = listOf(
        KiraCrossModalSample(1001L, KiraModalityFamily.TEXT, activation = .80, expectation = .55, relevance = .72, depth = 3),
        KiraCrossModalSample(2002L, KiraModalityFamily.AUDIO, activation = .76, expectation = .42, relevance = .61, exchangeBias = .18, depth = 2),
        KiraCrossModalSample(3003L, KiraModalityFamily.VISION, activation = .84, expectation = .36, relevance = .67, exchangeBias = .22, depth = 4),
    )
    val allAvailable = KiraInputAvailability(text = true, audio = true, camera = true)
    for (input in KiraInputLane.entries) {
        for (output in KiraOutputLane.entries) {
            var matrix = KiraRouteMatrix(BooleanArray(9) { false })
            matrix = matrix.withRoute(input, output, true)
            val projection = KiraCrossModalRuntime.project(
                output = output,
                routes = matrix,
                availability = allAvailable,
                samples = routeSamples,
                liveText = if (input == KiraInputLane.TEXT) "ein testimpuls" else "",
            )
            val selected = when (input) {
                KiraInputLane.TEXT -> projection.textDrive
                KiraInputLane.AUDIO -> projection.audioDrive
                KiraInputLane.CAMERA -> projection.cameraDrive
            }
            check(selected > 0.10) { "route $input -> $output produced no drive" }
            if (input != KiraInputLane.TEXT) check(projection.textDrive < 0.001)
            if (input != KiraInputLane.AUDIO) check(projection.audioDrive < 0.001)
            if (input != KiraInputLane.CAMERA) check(projection.cameraDrive < 0.001)
            check(projection.activeIds.isNotEmpty())
        }
    }
    val noAudioToText = KiraCrossModalRuntime.project(
        output = KiraOutputLane.TEXT,
        routes = KiraRouteMatrix(BooleanArray(9) { false }),
        availability = allAvailable,
        samples = routeSamples,
    )
    check(!noAudioToText.anyLiveDrive)

    val perceptionSurface = ChatTextLogic.resolveReply(
        "Was hörst du?",
        ChatTextLogic.planReply("Was hörst du?", 1, emptyList()),
        contextSignals = listOf(ChatContextSignal("audio_adaptive", 2002L, .81)),
    )
    check(perceptionSurface.kind == ChatReplyKind.NATIVE)
    check("Audio-Reiz" in perceptionSurface.text)
    check("sprachliche Bedeutung" in perceptionSurface.text)

    val modulePlan = ChatReplyPlan(ChatReplyKind.MODULE, "∑ 2 + 2 = 4")
    check(ChatTextLogic.structuralReply("2+2", modulePlan) == "∑ 2 + 2 = 4")

    val modules = KiraOperationalRegistry()
    val mathGeneration = modules.generateText(
        setOf(OperationalModuleId.MATH),
        KiraModuleContext("Was ist 2 + 3 * 4?"),
        KiraModuleTuning(math = MathModuleTuning(precision = 6, functionsEnabled = true)),
    )
    check(mathGeneration?.module == OperationalModuleId.MATH)
    check(mathGeneration?.text == "∑ 2 + 3 * 4 = 14")

    val codingGeneration = modules.generateText(
        setOf(OperationalModuleId.CODING),
        KiraModuleContext(
            text = "Schreib eine Kotlin Funktion addiere",
            memoryHints = listOf("Addieren verbindet zwei Zahlen"),
        ),
        KiraModuleTuning(coding = CodingModuleTuning(CodingLanguage.KOTLIN, strict = true, useMemoryContext = true)),
    )
    check(codingGeneration?.module == OperationalModuleId.CODING)
    check(codingGeneration?.text?.contains("fun addiere(a: Double, b: Double): Double = a + b") == true)
    check(codingGeneration?.text?.contains("relation context") == true)

    val filmDirective = modules.filmDirective(
        setOf(OperationalModuleId.FILM),
        KiraModuleContext(
            text = "",
            activeIds = listOf(11L, 22L, 33L),
            state = KiraStateVector(activity = .8, relationEnergy = .7, novelty = .6, prediction = .5, crossmodal = .4, memoryEnergy = .7),
        ),
        KiraModuleTuning(film = FilmModuleTuning(intensity = .55, stateInfluence = .7)),
    )
    check(filmDirective != null && filmDirective.enabled)
    check(filmDirective.intensity > 0.4f)

    val orchestraDirective = modules.orchestraDirective(
        setOf(OperationalModuleId.ORCHESTRA),
        KiraModuleTuning(orchestra = OrchestraModuleTuning(memoryBlend = .72, maxVoices = 14, tempoScale = 1.2)),
    )
    check(orchestraDirective?.maxVoices == 14)
    check(kotlin.math.abs((orchestraDirective?.tempoScale ?: 0f) - 1.2f) < 0.001f)

    val dream = DreamRuntime(seed = 42L)
    val dream0 = dream.snapshot()
    check(dream0.nodes.isNotEmpty())
    val dream1 = dream.step(
        context = KiraModuleContext(
            text = "eine rote stadt im regen",
            activeIds = listOf(11L, 22L, 33L),
            state = KiraStateVector(activity = .72, relationEnergy = .61, novelty = .74, prediction = .44, crossmodal = .53, memoryEnergy = .38),
        ),
        external = DreamInfluence(text = .8, audio = .2, camera = .6, energy = .7, tension = .3, novelty = .8, coherence = .5, motion = .7, hueBias = .1, activeIds = listOf(11L, 22L, 33L)),
        mode = DreamControlMode.INSTRUMENT,
        influenceStrength = .9,
        speed = 1.0,
    )
    check(dream1.tick == dream0.tick + 1)
    check(dream1.nodes.isNotEmpty())
    check(dream1.links.isNotEmpty())
    check(dream1.controlMode == DreamControlMode.INSTRUMENT)
    check(dream1.energy in 0.0..1.0)
    val encodedDream = DreamWorldCodec.encode(dream1)
    val restoredDream = DreamWorldCodec.decode(encodedDream)
    check(restoredDream != null)
    check(restoredDream!!.tick == dream1.tick)
    check(restoredDream.nodes.size == dream1.nodes.size)
    var rollingDream = dream1
    repeat(300) { step ->
        rollingDream = dream.step(
            context = KiraModuleContext(
                text = if (step % 11 == 0) "impuls $step" else "",
                activeIds = listOf((1000 + step).toLong()),
                state = KiraStateVector(
                    activity = (step % 10) / 10.0,
                    relationEnergy = .45,
                    novelty = ((step * 7) % 10) / 10.0,
                    prediction = .40,
                    crossmodal = .55,
                    memoryEnergy = .35,
                ),
            ),
            external = DreamInfluence(audio = .35, camera = .42, energy = .54, novelty = .47, motion = .51, activeIds = listOf((1000 + step).toLong())),
            mode = DreamControlMode.FUSION,
            influenceStrength = .72,
            speed = 1.15,
        )
    }
    check(rollingDream.nodes.size in 24..78)
    check(rollingDream.links.size <= 160)
    check(rollingDream.nodes.all { it.x.isFinite() && it.y.isFinite() && it.z.isFinite() && it.energy in 0.0..1.0 })
    check(rollingDream.energy.isFinite() && rollingDream.novelty.isFinite() && rollingDream.stability.isFinite() && rollingDream.tension.isFinite())

    val modeMask = CoreStimulusMask(text = true, audio = false, camera = true)
    check(modeMask.acceptsModality("text"))
    check(modeMask.acceptsModality("mind"))
    check(!modeMask.acceptsModality("audio_adaptive"))
    check(modeMask.acceptsModality("vision_adaptive"))
    check(!modeMask.acceptsModality("crossmodal")) // audio is disabled in this mask
    check(TextMacroIdentity.tokenId("Toastbrot") == TextMacroIdentity.tokenId("toastbrot"))
    check(TextMacroIdentity.tokenId("Toastbrot") != TextMacroIdentity.tokenId("Vogel"))

    // v0.9.3 — one compiler contract and one F→Ω+n tier map for every modality.
    check(KiraModalityClassifier.familyOf("text_mind") == KiraModalityFamily.TEXT)
    check(KiraModalityClassifier.familyOf("audio_adaptive") == KiraModalityFamily.AUDIO)
    check(KiraModalityClassifier.familyOf("vision_adaptive") == KiraModalityFamily.VISION)
    check(KiraModalityClassifier.familyOf("crossmodal") == KiraModalityFamily.CROSSMODAL)
    val universalPromotion = UniversalMacroCompiler.evaluatePromotion(
        patternLength = 4,
        hits = 12,
        sourceLevel = 0,
        coherence = .9,
        recurrence = .8,
    )
    check(universalPromotion.eligible)
    check(universalPromotion.support > 0.0)
    check(universalPromotion.stability > 0.0)
    check(universalPromotion.compressionGain > 0.0)
    check(AbstractionTier.labelForMacroDepth(1) == "F")
    check(AbstractionTier.labelForMacroDepth(10) == "Ω")
    check(AbstractionTier.labelForMacroDepth(13) == "Ω+3")

    // v0.9.7 — Forge Intermediate v1 is teacher-neutral, deterministic and does not let
    // embedding similarity masquerade as logical antithesis.
    val forgeDoc = ForgeIntermediateDocument(
        packageInfo = ForgePackageDescriptor("de-basic", 1, "kira.de.basic"),
        source = ForgeSourceDescriptor(
            id = "teacher-fixture",
            version = "1",
            adapterId = "fixture-adapter",
            adapterVersion = "1",
            modality = "text",
            modelFamily = "fixture",
        ),
        atoms = listOf(
            ForgeAtom("a:vogel", "text", ForgeAtomKind.CONCEPT, "Vogel", support = 12),
            ForgeAtom("a:tier", "text", ForgeAtomKind.CONCEPT, "Tier", support = 15),
            ForgeAtom("a:fliegen", "text", ForgeAtomKind.FEATURE, "fliegen", support = 9),
        ),
        relations = listOf(
            ForgeRelation("a:vogel", "a:tier", "is_a", .94, .95, ForgeRelationPolarity.SUPPORT),
            ForgeRelation("a:vogel", "a:fliegen", "association", .82, .88, ForgeRelationPolarity.NEUTRAL, evidenceType = "embedding_similarity"),
        ),
        sequences = listOf(
            ForgeSequence("s:vogel-fliegen", "text", listOf("a:vogel", "a:fliegen"), support = 9),
        ),
        macroCandidates = listOf(
            ForgeMacroCandidate("m:f-vogel", "text", listOf("a:vogel", "a:fliegen"), support = 12, coherence = .91, recurrence = .84),
            ForgeMacroCandidate("m:f-taxonomy", "text", listOf("a:vogel", "a:tier"), support = 11, coherence = .90, recurrence = .80),
            ForgeMacroCandidate("m:e-vogel", "text", listOf("m:f-vogel", "m:f-taxonomy"), support = 10, coherence = .88, recurrence = .78),
        ),
        traits = listOf(ForgeTrait("a:vogel", "animate", numberValue = 1.0, confidence = .9)),
    )
    val forgePlan = ForgeIntermediateCompiler.validateAndPlan(forgeDoc)
    check(forgePlan.valid) { forgePlan.issues.joinToString { it.message } }
    check(forgePlan.highestDepth == 2)
    check(forgePlan.highestTier == "E")
    check(forgePlan.nodes.any { it.externalId == "m:f-vogel" && it.tier == "F" })
    check(forgePlan.nodes.any { it.externalId == "m:e-vogel" && it.tier == "E" })
    check(forgePlan.stableIds["a:vogel"] == ForgeIntermediateCompiler.validateAndPlan(forgeDoc.copy(packageInfo = forgeDoc.packageInfo.copy(version = 2))).stableIds["a:vogel"])
    check(forgePlan.stableIds["a:vogel"] != ForgeIntermediateCompiler.validateAndPlan(forgeDoc.copy(packageInfo = forgeDoc.packageInfo.copy(namespace = "other.namespace"))).stableIds["a:vogel"])

    val tierGapForge = forgeDoc.copy(
        macroCandidates = forgeDoc.macroCandidates.map { macro ->
            if (macro.id == "m:e-vogel") macro.copy(members = listOf("m:f-vogel", "a:tier")) else macro
        }
    )
    val tierGapPlan = ForgeIntermediateCompiler.validateAndPlan(tierGapForge)
    check(!tierGapPlan.valid)
    check(tierGapPlan.errors.any { it.code == "TIER_GAP" })

    val badEmbeddingAntithesis = forgeDoc.copy(
        relations = listOf(
            ForgeRelation(
                "a:vogel", "a:tier", "association", .8, .9,
                polarity = ForgeRelationPolarity.OPPOSE,
                evidenceType = "embedding_similarity",
            )
        )
    )
    val badForgePlan = ForgeIntermediateCompiler.validateAndPlan(badEmbeddingAntithesis)
    check(!badForgePlan.valid)
    check(badForgePlan.errors.any { it.code == "EMBEDDING_ANTITHESIS" })

    val chatTriples = ChatTextLogic.compileMessageTriples(1001L, ChatRole.USER, "Vogel kann fliegen", 1000L)
    check(chatTriples.any { it.subject == 1001L && it.relation == EquinoxId64.stableId("relation", "chat_mentions") })
    check(chatTriples.any { it.subject == 1000L && it.objectId == 1001L && it.relation == EquinoxId64.stableId("relation", "chat_next") })
    println("MindCoreSelfTest OK")
}
