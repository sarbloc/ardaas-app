import SwiftData
import XCTest
@testable import Ardaas

/// The slot marker as the bundled variants spell it: one ellipsis character
/// followed by two full stops (U+2026 ".."), not five dots.
private let marker = "\u{2026}.."

/// A paath and an occasion together in the "….." slot (#158): the joiner,
/// the legacy normaliser, every combination rendered into both bundled
/// variants, persistence and the edit round trip.
@MainActor
final class SlotChoiceTests: XCTestCase {
    private func bundledCatalog() throws -> OccasionCatalog {
        try OccasionCatalog.loadBundled()
    }

    private func content(id: String) throws -> ArdaasContent {
        try XCTUnwrap(ArdaasLibrary.loadBundled().variant(id: id)).content
    }

    private func makeContainer() throws -> ModelContainer {
        try ModelContainer(
            for: SavedArdaas.self,
            configurations: ModelConfiguration(isStoredInMemoryOnly: true)
        )
    }

    private func record(paathId: String = "", occasion: OccasionChoice = .unset) -> SavedArdaas {
        SavedArdaas(label: "Morning", bentiText: "bless the family", occasion: occasion, paathId: paathId)
    }

    /// The slot segment as the Reader would draw it for `saved`.
    private func slotSegment(
        of saved: SavedArdaas,
        in content: ArdaasContent,
        catalog: OccasionCatalog?,
        file: StaticString = #filePath,
        line: UInt = #line
    ) throws -> ArdaasSegment {
        let slotId = try XCTUnwrap(content.occasionSlot, file: file, line: line).inSegmentId
        let match = saved.renderItems(in: content, catalog: catalog)
            .compactMap { item -> ArdaasSegment? in
                guard case let .canonical(segment) = item, segment.id == slotId else { return nil }
                return segment
            }
        return try XCTUnwrap(match.first, file: file, line: line)
    }

    private let sukhmani = OccasionLayers(
        gurmukhi: "ਸੁਖਮਨੀ ਸਾਹਿਬ ਦੇ ਪਾਠ",
        transliteration: "Sukhmani Saahib De Paath",
        english: "the recitation of Sukhmani Sahib"
    )
    private let birthday = OccasionLayers(
        gurmukhi: "ਜਨਮ ਦਿਹਾੜੇ",
        transliteration: "Janam Dihaarhe",
        english: "a birthday"
    )
    private let joiner = SlotJoiner(gurmukhi: "ਅਤੇ", transliteration: "Ate", english: "and")

    // MARK: - The bundled joiner

    func testTheBundledJoinerIsTheApprovedWord() throws {
        XCTAssertEqual(try bundledCatalog().joiner, joiner)
    }

    /// Like every other Roman string in the catalog, the joiner's is what
    /// the engine writes for its Gurmukhi — so a combined line romanizes in
    /// one voice.
    func testTheBundledJoinerTransliterationMatchesTheEngine() throws {
        let bundled = try XCTUnwrap(try bundledCatalog().joiner)
        XCTAssertEqual(bundled.transliteration, GurmukhiTransliterator.transliterate(bundled.gurmukhi))
    }

    /// The decoder is lenient about the key: a catalog without it loads, with
    /// no joiner.
    func testACatalogWithoutAJoinerDecodes() throws {
        let json = """
        {"version": 1, "sources": ["test"], "occasions": [
          {"id": "a", "category": "paath", "gurmukhi": "ਗ", "transliteration": "g", "english": "e"}
        ]}
        """
        let catalog = try JSONDecoder().decode(OccasionCatalog.self, from: Data(json.utf8))
        XCTAssertNil(catalog.joiner)
        XCTAssertNoThrow(try catalog.validate())
    }

    // MARK: - Joining

    func testNeitherChosenIsNothing() {
        XCTAssertNil(OccasionLayers.joining(paath: nil, occasion: nil, joiner: joiner))
        XCTAssertNil(OccasionLayers.joining(paath: OccasionLayers(), occasion: OccasionLayers(), joiner: joiner))
    }

    func testOneChosenIsExactlyThatOne() {
        XCTAssertEqual(OccasionLayers.joining(paath: sukhmani, occasion: nil, joiner: joiner), sukhmani)
        XCTAssertEqual(OccasionLayers.joining(paath: nil, occasion: birthday, joiner: joiner), birthday)
    }

    func testBothChosenAreJoinedInEveryLayer() {
        XCTAssertEqual(
            OccasionLayers.joining(paath: sukhmani, occasion: birthday, joiner: joiner),
            OccasionLayers(
                gurmukhi: "ਸੁਖਮਨੀ ਸਾਹਿਬ ਦੇ ਪਾਠ ਅਤੇ ਜਨਮ ਦਿਹਾੜੇ",
                transliteration: "Sukhmani Saahib De Paath Ate Janam Dihaarhe",
                english: "the recitation of Sukhmani Sahib and a birthday"
            )
        )
    }

    /// Free text fills one script, so the layers it cannot speak to read the
    /// paath alone.
    func testAOneScriptOccasionJoinsOnlyWhereItHasWords() {
        let english = OccasionLayers(english: "a new job")
        XCTAssertEqual(
            OccasionLayers.joining(paath: sukhmani, occasion: english, joiner: joiner),
            OccasionLayers(
                gurmukhi: sukhmani.gurmukhi,
                transliteration: sukhmani.transliteration,
                english: "the recitation of Sukhmani Sahib and a new job"
            )
        )
    }

    func testAMissingJoinerFallsBackToThePaathAlone() {
        XCTAssertEqual(OccasionLayers.joining(paath: sukhmani, occasion: birthday, joiner: nil), sukhmani)
    }

    func testAJoinerWithAnEmptyLayerFallsBackToThePaathInThatLayer() {
        let partial = SlotJoiner(gurmukhi: "ਅਤੇ", transliteration: " ", english: "and")
        let joined = OccasionLayers.joining(paath: sukhmani, occasion: birthday, joiner: partial)
        XCTAssertEqual(joined?.gurmukhi, "ਸੁਖਮਨੀ ਸਾਹਿਬ ਦੇ ਪਾਠ ਅਤੇ ਜਨਮ ਦਿਹਾੜੇ")
        XCTAssertEqual(joined?.transliteration, sukhmani.transliteration)
        XCTAssertEqual(joined?.english, "the recitation of Sukhmani Sahib and a birthday")
    }

    /// A bundle without the joiner renders the paath alone, never a failure.
    func testABundleWithoutAJoinerRendersThePaathAlone() throws {
        var catalog = try bundledCatalog()
        catalog.joiner = nil
        let slot = SlotChoice(paathId: "sukhmani-sahib", occasion: .catalog(id: "birthday"))
        XCTAssertEqual(slot.layers(in: catalog), sukhmani)
    }

    // MARK: - The legacy normaliser

    func testALegacyPaathInTheOccasionFieldIsReadAsThePaath() throws {
        let catalog = try bundledCatalog()
        for paath in catalog.occasions(in: .paath) {
            let legacy = SlotChoice(paathId: "", occasion: .catalog(id: paath.id))
            XCTAssertEqual(legacy.normalised(in: catalog), SlotChoice(paathId: paath.id, occasion: .unset), paath.id)
        }
    }

    func testAnOccasionInTheOccasionFieldStaysAnOccasion() throws {
        let catalog = try bundledCatalog()
        for occasion in catalog.occasions(in: .occasion) {
            let slot = SlotChoice(paathId: "", occasion: .catalog(id: occasion.id))
            XCTAssertEqual(slot.normalised(in: catalog), slot, occasion.id)
        }
    }

    func testAPaathIdThatIsNotAPaathIsIgnored() throws {
        let catalog = try bundledCatalog()
        for id in ["birthday", "retired-paath", OccasionChoice.customId] {
            let slot = SlotChoice(paathId: id, occasion: .custom(text: "a new job"))
            XCTAssertEqual(
                slot.normalised(in: catalog),
                SlotChoice(paathId: "", occasion: .custom(text: "a new job")),
                id
            )
        }
    }

    func testAPaathInTheOccasionFieldBesideAPaathIsIgnored() throws {
        let catalog = try bundledCatalog()
        let slot = SlotChoice(paathId: "japji-sahib", occasion: .catalog(id: "sukhmani-sahib"))
        XCTAssertEqual(slot.normalised(in: catalog), SlotChoice(paathId: "japji-sahib", occasion: .unset))
    }

    func testARetiredOccasionIsLeftForTheDraftToDrop() throws {
        let catalog = try bundledCatalog()
        let slot = SlotChoice(paathId: "sukhmani-sahib", occasion: .catalog(id: "retired-occasion"))
        XCTAssertEqual(slot.normalised(in: catalog), slot)
        XCTAssertEqual(slot.layers(in: catalog), sukhmani)
    }

    func testNormalisingTwiceChangesNothingMore() throws {
        let catalog = try bundledCatalog()
        let ids = ["", "sukhmani-sahib", "birthday", "retired", OccasionChoice.customId]
        for paathId in ids {
            for occasionId in ids {
                let slot = SlotChoice(
                    paathId: paathId,
                    occasion: OccasionChoice(occasionId: occasionId, occasionCustom: "words")
                )
                let once = slot.normalised(in: catalog)
                XCTAssertEqual(once.normalised(in: catalog), once, "\(paathId) / \(occasionId)")
            }
        }
    }

    // MARK: - Shared parity fixture

    private struct ParityFixture: Decodable {
        struct Stored: Decodable {
            let paathId: String
            let occasionId: String
            let occasionCustom: String
        }
        struct Layers: Decodable {
            let gurmukhi: String
            let transliteration: String
            let english: String
        }
        struct Case: Decodable {
            let name: String
            let stored: Stored
            let normalised: Stored
            let slot: Layers?
        }
        let cases: [Case]
    }

    /// The Android port asserts the same file (`Fixtures/slot-parity.json`),
    /// so the normaliser and the joiner cannot drift between the platforms.
    /// Never edit an expected value to make one side pass.
    func testSharedParityFixture() throws {
        let url = try XCTUnwrap(
            Bundle(for: Self.self).url(forResource: "slot-parity", withExtension: "json"),
            "slot-parity.json missing from test bundle"
        )
        let fixture = try JSONDecoder().decode(ParityFixture.self, from: Data(contentsOf: url))
        XCTAssertGreaterThanOrEqual(fixture.cases.count, 15)
        let catalog = try bundledCatalog()
        for testCase in fixture.cases {
            let saved = record()
            saved.paathId = testCase.stored.paathId
            saved.occasionId = testCase.stored.occasionId
            saved.occasionCustom = testCase.stored.occasionCustom

            // What the next save writes: the normalised slot, through the
            // record's own setter.
            saved.slotChoice = saved.slotChoice.normalised(in: catalog)
            XCTAssertEqual(saved.paathId, testCase.normalised.paathId, testCase.name)
            XCTAssertEqual(saved.occasionId, testCase.normalised.occasionId, testCase.name)
            XCTAssertEqual(saved.occasionCustom, testCase.normalised.occasionCustom, testCase.name)

            let expected = testCase.slot.map {
                OccasionLayers(gurmukhi: $0.gurmukhi, transliteration: $0.transliteration, english: $0.english)
            }
            let stored = SlotChoice(
                paathId: testCase.stored.paathId,
                occasion: OccasionChoice(
                    occasionId: testCase.stored.occasionId,
                    occasionCustom: testCase.stored.occasionCustom
                )
            )
            let layers = stored.layers(in: catalog)
            XCTAssertEqual(layers, expected, testCase.name)
            // Scalar for scalar as well: Swift compares Strings by canonical
            // equivalence, Kotlin by code unit, and the fixture pins the text.
            for layer in LayerKind.allCases {
                XCTAssertTrue(
                    (layers?[layer] ?? "").unicodeScalars
                        .elementsEqual((expected?[layer] ?? "").unicodeScalars),
                    "\(testCase.name) \(layer)"
                )
            }
        }
    }

    // MARK: - Every combination, in both bundled variants

    /// The combined line in situ, quoted for the proof-read: both variants,
    /// all three layers, seams single-spaced and punctuation untouched.
    func testPaathAndOccasionReadInTheSgpcSentence() throws {
        let segment = try slotSegment(
            of: record(paathId: "sukhmani-sahib", occasion: .catalog(id: "birthday")),
            in: try content(id: "sgpc"),
            catalog: try bundledCatalog()
        )
        XCTAssertTrue(
            segment.gurmukhi.hasSuffix("ਆਪ ਦੇ ਹਜ਼ੂਰ ਸੁਖਮਨੀ ਸਾਹਿਬ ਦੇ ਪਾਠ ਅਤੇ ਜਨਮ ਦਿਹਾੜੇ ਦੀ ਅਰਦਾਸ ਹੈ ਜੀ॥"),
            segment.gurmukhi
        )
        XCTAssertEqual(
            segment.transliteration?.hasSuffix(
                "Aap De Hazur Sukhmani Saahib De Paath Ate Janam Dihaarhe Di Ardaas Hai Ji ||"
            ),
            true,
            segment.transliteration ?? "nil"
        )
        XCTAssertEqual(
            segment.english?.hasSuffix(
                "we humbly make prayer in your presence the recitation of Sukhmani Sahib and a birthday."
            ),
            true,
            segment.english ?? "nil"
        )
    }

    func testPaathAndOccasionReadInTheBuddhaDalSentence() throws {
        let segment = try slotSegment(
            of: record(paathId: "sukhmani-sahib", occasion: .catalog(id: "birthday")),
            in: try content(id: "buddha-dal"),
            catalog: try bundledCatalog()
        )
        XCTAssertTrue(
            segment.gurmukhi.hasSuffix("ਆਪ ਜੀ ਦੇ ਹਜ਼ੂਰ ਸੁਖਮਨੀ ਸਾਹਿਬ ਦੇ ਪਾਠ ਅਤੇ ਜਨਮ ਦਿਹਾੜੇ ਦੀ ਅਰਦਾਸ ਆਪ ਜੀ ਦੇ ਚਰਨ ਕਮਲਾਂ ਪਾਸ,"),
            segment.gurmukhi
        )
        XCTAssertEqual(
            segment.transliteration?.hasSuffix(
                "Aap Ji De Hazur Sukhmani Saahib De Paath Ate Janam Dihaarhe Di Ardaas Aap Ji De Charan Kamlaa(n) Paas,"
            ),
            true,
            segment.transliteration ?? "nil"
        )
        XCTAssertEqual(
            segment.english?.hasSuffix(
                "In your presence the prayer of the recitation of Sukhmani Sahib and a birthday is laid at your lotus feet,"
            ),
            true,
            segment.english ?? "nil"
        )
    }

    /// Every combination of the two pickers, in every bundled variant, renders
    /// exactly the joined phrase — and with one choice, exactly what that
    /// choice rendered before #158.
    func testEveryCombinationRendersInEveryVariant() throws {
        let catalog = try bundledCatalog()
        let english = "my niece's first birthday"
        let gurmukhi = "ਦਸਤਾਰਬੰਦੀ"
        let combinations: [(name: String, paathId: String, occasion: OccasionChoice)] = [
            ("none", "", .unset),
            ("paath only", "sukhmani-sahib", .unset),
            ("occasion only", "", .catalog(id: "birthday")),
            ("English custom only", "", .custom(text: english)),
            ("Gurmukhi custom only", "", .custom(text: gurmukhi)),
            ("paath + occasion", "sukhmani-sahib", .catalog(id: "birthday")),
            ("paath + English custom", "sukhmani-sahib", .custom(text: english)),
            ("paath + Gurmukhi custom", "sukhmani-sahib", .custom(text: gurmukhi)),
        ]
        for variantId in ["sgpc", "buddha-dal"] {
            let text = try content(id: variantId)
            let authored = try XCTUnwrap(ArdaasComposer.occasionSlotSegment(of: text, occasion: nil))
            for combination in combinations {
                let label = "\(variantId): \(combination.name)"
                let saved = record(paathId: combination.paathId, occasion: combination.occasion)
                let segment = try slotSegment(of: saved, in: text, catalog: catalog)

                // The whole render is the composer fed the joined phrase.
                let paath = combination.paathId.isEmpty ? nil : sukhmani
                let joined = OccasionLayers.joining(
                    paath: paath,
                    occasion: combination.occasion.layers(in: catalog),
                    joiner: joiner
                )
                XCTAssertEqual(
                    segment,
                    ArdaasComposer.occasionSlotSegment(of: text, occasion: joined),
                    label
                )

                // And, layer by layer, the phrase is in the sentence where it
                // has words, and the dots are where it has none.
                for layer in LayerKind.allCases {
                    let rendered = try XCTUnwrap(segment.text(for: layer), label)
                    let phrase = joined?[layer] ?? ""
                    if phrase.isEmpty {
                        XCTAssertEqual(rendered, authored.text(for: layer), "\(label) \(layer)")
                    } else {
                        XCTAssertTrue(rendered.contains(phrase), "\(label) \(layer): \(rendered)")
                        XCTAssertFalse(rendered.contains(marker), "\(label) \(layer): \(rendered)")
                        XCTAssertFalse(rendered.contains("  "), "\(label) \(layer): \(rendered)")
                    }
                }
            }
        }
    }

    /// One choice alone reads exactly as it did before #158.
    func testASingleChoiceRendersExactlyAsBefore() throws {
        let catalog = try bundledCatalog()
        for variantId in ["sgpc", "buddha-dal"] {
            let text = try content(id: variantId)
            for entry in catalog.occasions {
                let layers = OccasionLayers(
                    gurmukhi: entry.gurmukhi, transliteration: entry.transliteration, english: entry.english
                )
                let before = ArdaasComposer.occasionSlotSegment(of: text, occasion: layers)
                let saved = entry.category == .paath
                    ? record(paathId: entry.id)
                    : record(occasion: .catalog(id: entry.id))
                XCTAssertEqual(try slotSegment(of: saved, in: text, catalog: catalog), before, entry.id)
            }
        }
    }

    func testALegacyRecordRendersItsPaath() throws {
        let catalog = try bundledCatalog()
        let text = try content(id: "sgpc")
        XCTAssertEqual(
            try slotSegment(of: record(occasion: .catalog(id: "sukhmani-sahib")), in: text, catalog: catalog),
            try slotSegment(of: record(paathId: "sukhmani-sahib"), in: text, catalog: catalog)
        )
    }

    func testUnknownIdsRenderTheDots() throws {
        let catalog = try bundledCatalog()
        let text = try content(id: "sgpc")
        let authored = try XCTUnwrap(ArdaasComposer.occasionSlotSegment(of: text, occasion: nil))
        for saved in [
            record(paathId: "retired-paath"),
            record(paathId: "birthday"),
            record(occasion: .catalog(id: "retired-occasion")),
            record(paathId: "retired-paath", occasion: .catalog(id: "retired-occasion")),
        ] {
            XCTAssertEqual(try slotSegment(of: saved, in: text, catalog: catalog), authored, saved.paathId)
        }
    }

    /// Without a catalog only free text can render — the paath is an id.
    func testAMissingCatalogKeepsTheFreeTextOnly() throws {
        let text = try content(id: "sgpc")
        let saved = record(paathId: "sukhmani-sahib", occasion: .custom(text: "a new job"))
        let segment = try slotSegment(of: saved, in: text, catalog: nil)
        XCTAssertEqual(segment.english?.contains("presence a new job."), true, segment.english ?? "nil")
        XCTAssertTrue(segment.gurmukhi.contains(marker), segment.gurmukhi)
    }

    // MARK: - The pickers' draft

    func testTheDraftPreviewShowsTheJoinedSentence() throws {
        let catalog = try bundledCatalog()
        let draft = OccasionDraft(paathId: "sukhmani-sahib", selection: .catalog(id: "birthday"))
        let preview = try XCTUnwrap(draft.preview(in: try content(id: "sgpc"), catalog: catalog))
        XCTAssertTrue(preview.isFilled)
        XCTAssertEqual(preview.layer, .gurmukhi)
        XCTAssertTrue(preview.text.contains("ਸੁਖਮਨੀ ਸਾਹਿਬ ਦੇ ਪਾਠ ਅਤੇ ਜਨਮ ਦਿਹਾੜੇ ਦੀ ਅਰਦਾਸ"), preview.text)
    }

    func testThePickersAreIndependent() {
        var draft = OccasionDraft()
        draft.selection = .custom
        draft.customText = "a new job"
        draft.paathId = "sukhmani-sahib"
        XCTAssertEqual(draft.slot, SlotChoice(paathId: "sukhmani-sahib", occasion: .custom(text: "a new job")))
        draft.paathId = ""
        XCTAssertEqual(draft.slot, SlotChoice(paathId: "", occasion: .custom(text: "a new job")))
        draft.selection = .catalog(id: "birthday")
        draft.paathId = "rehras-sahib"
        XCTAssertEqual(draft.slot, SlotChoice(paathId: "rehras-sahib", occasion: .catalog(id: "birthday")))
    }

    func testTheDraftOpensALegacyRecordOnThePaathPicker() throws {
        let catalog = try bundledCatalog()
        let legacy = SlotChoice(paathId: "", occasion: .catalog(id: "sukhmani-sahib"))
        let draft = OccasionDraft(slot: legacy, catalog: catalog)
        XCTAssertEqual(draft.paathId, "sukhmani-sahib")
        XCTAssertEqual(draft.selection, .unset)
        // Without a catalog nothing can be resolved, so nothing is moved.
        XCTAssertEqual(OccasionDraft(slot: legacy).slot, legacy)
    }

    /// The #72 warning is about the occasion's own words: beside a paath the
    /// sentence changes, but English free text on a variant without English
    /// still has nowhere to go.
    func testTheNoEnglishWarningStillFiresBesideAPaath() throws {
        let noEnglish = ArdaasContent.fixture(
            segments: [ArdaasSegment(
                id: "slot",
                gurmukhi: "ਆਪ ਜੀ ਦੇ ਹਜ਼ੂਰ\(marker)ਦੀ ਅਰਦਾਸ",
                transliteration: "Aap Ji De Hazur\(marker)Di Ardaas",
                english: nil
            )],
            slotAfter: "slot",
            occasionSlot: .fixture(inSegmentId: "slot", transliteration: marker)
        )
        let catalog = try bundledCatalog()
        let draft = OccasionDraft(paathId: "sukhmani-sahib", selection: .custom, customText: "a new job")
        XCTAssertTrue(draft.willNotAppear(in: noEnglish, catalog: catalog))
        XCTAssertEqual(draft.preview(in: noEnglish, catalog: catalog)?.isFilled, true)
        // A paath on its own is never flagged.
        XCTAssertFalse(OccasionDraft(paathId: "sukhmani-sahib").willNotAppear(in: noEnglish, catalog: catalog))
    }

    // MARK: - The Reader's editor writes

    /// The editor writes the draft's whole slot on every change, so the first
    /// change on a legacy record also moves its paath into `paathId`.
    func testTheFirstEditorChangeOnALegacyRecordWritesTheNormalisedSlot() throws {
        let container = try makeContainer()
        let catalog = try bundledCatalog()
        let saved = record(occasion: .catalog(id: "sukhmani-sahib"))
        container.mainContext.insert(saved)

        var draft = OccasionDraft(slot: saved.slotChoice, catalog: catalog)
        draft.selection = .catalog(id: "birthday")
        saved.slotChoice = draft.slot
        try container.mainContext.save()

        let stored = try XCTUnwrap(try container.mainContext.fetch(FetchDescriptor<SavedArdaas>()).first)
        XCTAssertEqual(stored.paathId, "sukhmani-sahib")
        XCTAssertEqual(stored.occasionId, "birthday")
        XCTAssertEqual(stored.occasionCustom, "")
    }

    // MARK: - Persistence and the edit round trip (#154)

    func testThePaathPersists() throws {
        let container = try makeContainer()
        container.mainContext.insert(record(paathId: "sukhmani-sahib", occasion: .custom(text: "a new job")))
        try container.mainContext.save()
        let stored = try XCTUnwrap(try container.mainContext.fetch(FetchDescriptor<SavedArdaas>()).first)
        XCTAssertEqual(stored.paathId, "sukhmani-sahib")
        XCTAssertEqual(stored.slotChoice, SlotChoice(paathId: "sukhmani-sahib", occasion: .custom(text: "a new job")))
    }

    func testARecordWithoutAPaathDefaultsToNone() {
        let saved = SavedArdaas(label: "Morning", bentiText: "bless the family")
        XCTAssertEqual(saved.paathId, "")
        XCTAssertEqual(saved.slotChoice, SlotChoice())
    }

    func testEverySlotRoundTripsThroughTheForm() {
        let paaths = ["", "sukhmani-sahib"]
        let occasions: [OccasionChoice] = [
            .unset, .catalog(id: "birthday"), .custom(text: "a new job"), .custom(text: "ਦਸਤਾਰਬੰਦੀ"),
        ]
        for paathId in paaths {
            for occasion in occasions {
                let fields = SavedArdaasFields(
                    label: "Family",
                    variantId: "buddha-dal",
                    benti: BentiLayers(english: "Bless the sangat."),
                    occasion: occasion,
                    paathId: paathId
                )
                XCTAssertEqual(ComposeForm(fields: fields).fields, fields, "\(paathId) \(occasion)")
                let saved = SavedArdaas.make(from: fields)
                XCTAssertEqual(saved.fields, fields, "\(paathId) \(occasion)")
                saved.apply(ComposeForm(fields: saved.fields).fields)
                XCTAssertEqual(saved.fields, fields, "\(paathId) \(occasion)")
            }
        }
    }

    func testChangingThePaathIsAChange() {
        let original = ComposeForm(fields: SavedArdaasFields(
            label: "Family", variantId: "sgpc", benti: BentiLayers(english: "b"),
            occasion: .catalog(id: "birthday"), paathId: "sukhmani-sahib"
        ))
        var form = original
        form.occasion.paathId = "rehras-sahib"
        XCTAssertTrue(form.hasChanges(from: original))
        form.occasion.paathId = "sukhmani-sahib"
        XCTAssertFalse(form.hasChanges(from: original))
    }

    /// Opening a legacy record for editing resolves its paath, in the form
    /// and in the baseline alike (as `ComposeView` does), so it is not a
    /// change to discard — and Save writes the normalised fields.
    func testEditingALegacyRecordOpensOnThePaathAndSavesItNormalised() throws {
        let container = try makeContainer()
        let catalog = try bundledCatalog()
        let saved = record(occasion: .catalog(id: "sukhmani-sahib"))
        container.mainContext.insert(saved)

        var original = ComposeForm(fields: saved.fields)
        original.normaliseSlot(in: catalog)
        var form = ComposeForm(fields: saved.fields)
        form.normaliseSlot(in: catalog)
        XCTAssertEqual(form.occasion.paathId, "sukhmani-sahib")
        XCTAssertEqual(form.occasion.selection, .unset)
        XCTAssertFalse(form.hasChanges(from: original))

        saved.apply(form.fields)
        try container.mainContext.save()
        let stored = try XCTUnwrap(try container.mainContext.fetch(FetchDescriptor<SavedArdaas>()).first)
        XCTAssertEqual(stored.paathId, "sukhmani-sahib")
        XCTAssertEqual(stored.occasionId, "")
        XCTAssertEqual(stored.occasionCustom, "")
    }

    func testNormalisingTheFormKeepsOtherBeingTyped() throws {
        let catalog = try bundledCatalog()
        var form = ComposeForm()
        form.occasion.paathId = "retired-paath"
        form.occasion.selection = .custom
        form.normaliseSlot(in: catalog)
        XCTAssertEqual(form.occasion.selection, .custom)
        XCTAssertEqual(form.occasion.customText, "")
        XCTAssertEqual(form.occasion.paathId, "")
    }
}
