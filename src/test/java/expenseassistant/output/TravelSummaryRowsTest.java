package expenseassistant.output;

import expenseassistant.casework.CaseException;
import expenseassistant.casework.CaseService.CaseInfo;
import expenseassistant.draft.CaseView;
import expenseassistant.draft.CaseView.OverrideView;
import expenseassistant.intake.TypeInference.CaseType;
import expenseassistant.matching.ExpenseAmounts;
import expenseassistant.matching.ExpenseMatcher;
import expenseassistant.matching.ExpenseMatcher.Evidence;
import expenseassistant.output.ExportSnapshot.ExpenseRow;
import expenseassistant.recognition.PageParser;
import expenseassistant.recognition.PageResult;
import expenseassistant.recognition.PageResult.FieldCandidate;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static expenseassistant.matching.ExpenseAmounts.Component.*;
import static org.junit.jupiter.api.Assertions.*;

class TravelSummaryRowsTest {
    @Test
    void datedRoundTripSeparatesMatchedTicketsAndDeduplicatesTransport() {
        var flight = ticket("go", "FLIGHT", "宁波", "深圳", "2026-11-12", "796.00", "10000000000000000001");
        var duplicate = new Evidence("copy", "copy", flight.sha256(), "来程副本.pdf", flight.pages());
        var rail = ticket("back", "RAIL", "深圳北站", "宁波站", "2026-11-13", "200.00", "10000000000000000002");
        var matched = new ExpenseMatcher().propose("case", CaseType.TRAVEL, List.of(flight, rail, duplicate), Map.of());
        var base = fixture("无可用目录地名", List.of());
        var view = new CaseView(base.info(), matched.items(), List.of(), matched.links(), matched.claims(),
                matched.duplicates(), List.of(), List.of(), List.of(), List.of());

        var snapshot = ExportSnapshot.fromDraft(view);
        assertEquals(2, snapshot.rows().size());
        assertLeg(snapshot.rows().getFirst(), day(12), "宁波-深圳", "飞机", 79_600L);
        assertLeg(snapshot.rows().getLast(), day(13), "深圳北站-宁波站", "火车", 20_000L);
        assertEquals(matched.claims(), snapshot.audit().claims());
        assertEquals(2, snapshot.audit().amounts().size());
        assertEquals(Set.of(duplicate.unitId()), view.duplicateUnitIds());
    }

    @Test
    void onlyOneOppositePairCanUseDirectoryDatesAndWholeTripCharges() {
        var go = row("outbound", "FLIGHT", null, null, Map.of("origin", "宁波", "destination", "深圳", "vehicle", "飞机"),
                Map.of("TRANSPORT", 79_600L));
        var back = row("return", "FLIGHT", null, null, Map.of("origin", "深圳", "destination", "宁波", "vehicle", "飞机"),
                Map.of("TRANSPORT", 110_500L));
        var taxi = row("taxi", "RIDE", day(12), day(13), Map.of("route", "车站 → 酒店"), Map.of("TAXI", 7_130L));
        var hotel = row("hotel", "HOTEL", null, null, Map.of("nights", "1"), Map.of("HOTEL", 50_000L));
        var other = row("other", "RIDE", day(13), day(13), Map.of(), Map.of("TAXI", 0L));
        var view = fixture("深圳出差-2026.11.12~13", List.of());

        var rows = TravelSummaryRows.summarize(view, List.of(back, go, taxi, hotel, other));
        assertEquals(2, rows.size());
        assertLeg(rows.getFirst(), day(12), "宁波-深圳", "飞机", 79_600L);
        assertEquals(Map.of("TRANSPORT", 79_600L, "HOTEL", 50_000L, "TAXI", 7_130L), rows.getFirst().amounts());
        assertEquals("1", rows.getFirst().fields().get("nights"));
        assertLeg(rows.getLast(), day(13), "深圳-宁波", "飞机", 110_500L);
        assertEquals(Map.of("TRANSPORT", 110_500L, "TAXI", 0L), rows.getLast().amounts());

        var loneBack = TravelSummaryRows.summarize(view, List.of(back)).getFirst();
        assertLeg(loneBack, null, "深圳-宁波", "飞机", 110_500L);
        var late = row("late", "FLIGHT", day(14), day(14), go.fields(), go.amounts());
        var conflict = TravelSummaryRows.summarize(view, List.of(back, late));
        assertLeg(conflict.getFirst(), day(14), "宁波-深圳", "飞机", 79_600L);
        assertLeg(conflict.getLast(), null, "深圳-宁波", "飞机", 110_500L);
    }

    @Test
    void usesOnlyLinkedMatchingTicketFilenameAndValidatesKnownEndpoint() {
        var flight = row("flight", "FLIGHT", day(12), day(12), Map.of("origin", "宁波", "vehicle", "飞机"),
                Map.of("TRANSPORT", 100L));
        var other = row("other", "FLIGHT", day(13), day(13), Map.of("origin", "杭州", "vehicle", "飞机"),
                Map.of("TRANSPORT", 200L));
        var rail = row("rail", "RAIL", null, null, Map.of("origin", "杭州", "vehicle", "火车"),
                Map.of("TRANSPORT", 0L));
        var taxi = row("taxi", "RIDE", day(12), day(12), Map.of("origin", "武汉", "destination", "苏州"),
                Map.of("TAXI", 500L));
        var link = "华东/【飞猪】宁波-深圳  订单10395170653090-机票款凭证 报销凭证.pdf";
        var view = withEvidence(fixture("无目录日期", List.of()), Map.of(
                flight, evidence("go", "FLIGHT", link),
                other, evidence("bad", "FLIGHT", link),
                rail, evidence("train", "RAIL", "杭州东站-南京站 火车票款凭证.pdf"),
                taxi, evidence("taxi", "RIDE", "武汉-苏州 火车票款凭证.pdf")));
        var rows = TravelSummaryRows.summarize(view, List.of(other, flight, rail, taxi));
        assertEquals(3, rows.size());
        assertLeg(rows.get(0), day(12), "宁波-深圳", "飞机", 100L);
        assertEquals(500L, rows.get(0).amounts().get("TAXI"));
        assertLeg(rows.get(1), day(13), null, "飞机", 200L);
        assertLeg(rows.get(2), null, "杭州-南京站", "火车", 0L);

        var badName = withEvidence(fixture("无目录日期", List.of()), Map.of(flight,
                evidence("datedName", "FLIGHT", "20261112 宁波-深圳 机票款凭证.pdf")));
        assertLeg(TravelSummaryRows.summarize(badName, List.of(flight)).getFirst(), day(12), null, "飞机", 100L);
        var wrongKind = withEvidence(fixture("深圳出差-2026.11.12~13", List.of()), Map.of(rail,
                evidence("wrong", "RAIL", "杭州-深圳 机票款凭证.pdf")));
        assertLeg(TravelSummaryRows.summarize(wrongKind, List.of(rail)).getFirst(), null, null, "火车", 0L);
    }

    @Test
    void recognizedConflictsAllowAnIndependentDateOrDirectionAndKeepAmounts() {
        var dateOnly = row("date", "FLIGHT", day(12), day(12), Map.of("vehicle", "飞机"), Map.of("TRANSPORT", 100L));
        var directionOnly = row("place", "RAIL", null, null, Map.of("vehicle", "火车"), Map.of("TRANSPORT", 200L));
        var view = withEvidence(fixture("深圳出差-2026.11.12~13", List.of()), Map.of(
                dateOnly, evidence("conflictedPlace", "FLIGHT", "宁波-深圳 机票款凭证.pdf",
                        candidate("origin", "宁波"), candidate("origin", "杭州"), candidate("destination", "深圳"),
                        candidate("endDate", "2026-11-12"), candidate("endDate", "2026-11-13")),
                directionOnly, evidence("conflictedDay", "RAIL", "深圳-杭州 火车票款凭证.pdf",
                        candidate("origin", "深圳北站"), candidate("destination", "宁波站"),
                        candidate("startDate", "2026-11-12"), candidate("startDate", "2026-11-13"))));
        var rows = TravelSummaryRows.summarize(view, List.of(directionOnly, dateOnly));
        assertLeg(rows.getFirst(), day(12), null, "飞机", 100L);
        assertNull(rows.getFirst().endDate());
        assertLeg(rows.getLast(), null, "深圳北站-宁波站", "火车", 200L);
    }

    @Test
    void manualRouteAndDateTakePrecedenceAndExplicitClearStaysBlank() {
        var go = row("go", "FLIGHT", day(12), day(13),
                Map.of("route", "宁波-深圳", "origin", "北京", "destination", "武汉", "vehicle", "飞机"),
                Map.of("TRANSPORT", 200L));
        var back = row("back", "FLIGHT", null, null, Map.of("origin", "深圳", "destination", "宁波", "vehicle", "飞机"),
                Map.of("TRANSPORT", 100L));
        var view = withEvidence(fixture("深圳出差-2026.11.12~13", List.of(
                        manual(go, "route"), manual(go, "startDate"), clear(back, "startDate"), clear(back, "route"))),
                Map.of(go, evidence("go", "FLIGHT", "北京-武汉 机票款凭证.pdf", candidate("startDate", "2026-11-13")),
                        back, evidence("back", "FLIGHT", "深圳-宁波 机票款凭证.pdf", candidate("startDate", "2026-11-13"))));
        var rows = TravelSummaryRows.summarize(view, List.of(back, go));
        assertLeg(rows.getFirst(), day(12), "宁波-深圳", "飞机", 200L);
        assertEquals(day(13), rows.getFirst().endDate());
        assertLeg(rows.getLast(), null, null, "飞机", 100L);
    }

    @Test
    void billsAndArrivalDaysCannotSupplyDepartureAndRepeatedToolsAreDeduplicated() {
        var row = row("partial", "RAIL", null, day(13),
                Map.of("origin", "宁波站", "destination", "深圳北站", "vehicle", "飞机，火车 、 私车 / 飞机"),
                Map.of("TRANSPORT", 0L));
        var view = withEvidence(fixture("深圳出差-2026.11.12~13", List.of()), Map.of(row,
                evidence("arrivalOnly", "RAIL", "宁波-深圳 火车票款凭证.pdf", candidate("invoiceDate", "2026-11-12"),
                        candidate("applicationDate", "2026-11-12"), candidate("endDate", "2026-11-13"))));
        var line = TravelSummaryRows.summarize(view, List.of(row)).getFirst();
        assertLeg(line, null, "宁波站-深圳北站", "火车、飞机、私车", 0L);
        assertEquals(day(13), line.endDate());
        assertEquals(List.of(), TravelSummaryRows.summarize(view, List.of()));
    }

    @Test
    void multipleDestinationsUseDirectEvidenceThenUniqueDatesAndConserveEachColumn() {
        var shenzhen = row("flight", "FLIGHT", day(12), day(14), Map.of("origin", "宁波", "destination", "深圳"),
                Map.of("TRANSPORT", 100L));
        var hangzhou = row("rail", "RAIL", day(16), day(18), Map.of("origin", "宁波站", "destination", "杭州东站"),
                Map.of("TRANSPORT", 200L));
        var hotel = row("hotel", "HOTEL", day(13), day(14), Map.of("nights", "1"), Map.of("HOTEL", 500L));
        var taxi = row("taxi", "RIDE", day(17), day(17), Map.of("route", "市区 → 车站"), Map.of("TAXI", 70L));
        var unknown = row("unknown", "HOTEL", day(14), day(17), Map.of(), Map.of("HOTEL", 999L));
        var subsidy = row("subsidy", "MANUAL", day(12), day(12), Map.of("destination", "深圳"),
                Map.of("MEAL_SUBSIDY", 0L));
        var partial = row("partial", "RAIL", null, null, Map.of("destination", "杭州"), Map.of("TRANSPORT", 9L));
        var view = fixture("深圳出差-2026.11.12~13", List.of(manual(subsidy, "destination")));
        var entries = List.of(unknown, taxi, hotel, hangzhou, subsidy, shenzhen, partial);
        var rows = TravelSummaryRows.summarize(view, entries);
        assertEquals(List.of("宁波-深圳", "", "宁波站-杭州东站", ""),
                rows.stream().map(row -> row.fields().getOrDefault("route", "")).toList());
        assertEquals(Map.of("TRANSPORT", 100L, "HOTEL", 500L, "MEAL_SUBSIDY", 0L), rows.get(0).amounts());
        assertEquals(Map.of("HOTEL", 999L), rows.get(1).amounts());
        assertEquals(Map.of("TRANSPORT", 200L, "TAXI", 70L), rows.get(2).amounts());
        assertEquals(Map.of("TRANSPORT", 9L), rows.get(3).amounts());
        assertEquals(sumByComponent(entries), sumByComponent(rows));
    }

    @Test
    void ticketlessHotelAnchorsTripAndConsumesItsAmountOnce() {
        var hotel = row("hotel", "HOTEL", day(12), null,
                Map.of("route", "宁波-杭州", "vehicle", "随车", "nights", "1"), Map.of("HOTEL", 36_465L));
        var taxi = row("taxi", "RIDE", day(12), day(12), Map.of("route", "车站 → 旅馆"), Map.of("TAXI", 1_810L));
        var view = fixture("杭州出差-2026.11.18",
                List.of(manual(hotel, "route"), manual(hotel, "vehicle"), manual(hotel, "startDate")));
        var rows = TravelSummaryRows.summarize(view, List.of(taxi, hotel));
        assertEquals(1, rows.size());
        assertLeg(rows.getFirst(), day(12), "宁波-杭州", "随车", null);
        assertEquals(Map.of("HOTEL", 36_465L, "TAXI", 1_810L), rows.getFirst().amounts());
        assertEquals("1", rows.getFirst().fields().get("nights"));
        assertEquals(hotel.itemId(), rows.getFirst().itemId());

        var both = fixture("杭州出差-2026.11.18", List.of(manual(hotel, "route"), manual(hotel, "vehicle"),
                manual(hotel, "startDate"), manual(taxi, "startDate")));
        assertEquals(rows, TravelSummaryRows.summarize(both, List.of(hotel, taxi)));
    }

    @Test
    void checkInAndKnownDestinationAssignStayEvenWhenCheckoutIsLaterThanTheLeg() {
        var flight = row("go", "FLIGHT", day(12), day(13), Map.of("origin", "宁波", "destination", "深圳"),
                Map.of("TRANSPORT", 200L));
        var train = row("train", "RAIL", day(13), day(13), Map.of("origin", "上海", "destination", "杭州东站"),
                Map.of("TRANSPORT", 100L));
        var hotel = row("hotel", "HOTEL", day(13), day(17), Map.of("destination", "深圳市", "nights", "4"),
                Map.of("HOTEL", 800L));
        var view = fixture("未指定地点", List.of());
        var rows = TravelSummaryRows.summarize(view, List.of(flight, train, hotel));
        assertEquals(2, rows.size());
        assertEquals(Map.of("TRANSPORT", 200L, "HOTEL", 800L), rows.getFirst().amounts());
        assertEquals("4", rows.getFirst().fields().get("nights"));
        assertEquals(Map.of("TRANSPORT", 100L), rows.getLast().amounts());
        assertEquals(day(13), rows.getFirst().endDate());
    }

    @Test
    void ticketlessCheckoutAndConflictingDirectoryCannotProvideDeparture() {
        var hotel = row("hotel", "HOTEL", day(15), day(16), Map.of("vehicle", "随车", "nights", "1"),
                Map.of("HOTEL", 500L));
        var view = fixture("杭州出差-2026.11.12~13", List.of(manual(hotel, "vehicle")));
        var line = TravelSummaryRows.summarize(view, List.of(hotel)).getFirst();
        assertLeg(line, null, "杭州", "随车", null);
        assertEquals(Map.of("HOTEL", 500L), line.amounts());
    }

    @Test
    void onlyVerifiedNonOverlappingHotelQuantitiesCanBeAdded() {
        var view = fixture("深圳出差-2026.11.12~18", List.of());
        var first = row("first", "HOTEL", day(12), day(13), Map.of("nights", "1"), Map.of("HOTEL", 500L));
        var second = row("second", "HOTEL", day(13), day(15), Map.of("nights", "2"), Map.of("HOTEL", 400L));
        var repeated = row("repeat", "HOTEL", day(12), day(13), Map.of("nights", "1"), Map.of("HOTEL", 500L));
        var undated = row("undated", "HOTEL", null, null, Map.of("nights", "1"), Map.of());
        var unconfirmed = row("unconfirmed", "HOTEL", day(14), day(14), Map.of(), Map.of());
        var verified = TravelSummaryRows.summarize(view, List.of(second, first)).getFirst();
        assertEquals("3", verified.fields().get("nights"));
        assertEquals(Map.of("HOTEL", 900L), verified.amounts());
        assertEquals(day(12), verified.startDate());
        assertFalse(TravelSummaryRows.summarize(view, List.of(first, repeated)).getFirst().fields().containsKey("nights"));
        assertFalse(TravelSummaryRows.summarize(view, List.of(first, undated)).getFirst().fields().containsKey("nights"));
        var unknown = TravelSummaryRows.summarize(view, List.of(unconfirmed)).getFirst();
        assertFalse(unknown.fields().containsKey("nights"));
        assertEquals(Map.of(), unknown.amounts());
        assertEquals(day(12), unknown.startDate());
    }

    @Test
    void sameDayTripsKeepStableOrderAndFeesThatMatchBothRemainSeparate() {
        var outbound = row("outbound", "RAIL", day(12), day(12), Map.of("origin", "宁波", "destination", "深圳"),
                Map.of("TRANSPORT", 100L));
        var inbound = row("inbound", "FLIGHT", day(12), day(12), Map.of("origin", "深圳", "destination", "宁波"),
                Map.of("TRANSPORT", 200L));
        var taxi = row("taxi", "RIDE", day(12), day(12), Map.of(), Map.of("TAXI", 70L));
        var local = row("local", "RIDE", day(12), day(12), Map.of("route", "宁波-深圳"), Map.of("TAXI", 30L));
        var view = fixture("未指定地点", List.of(manual(local, "route")));
        var rows = TravelSummaryRows.summarize(view, List.of(inbound, outbound, taxi, local));
        assertEquals(List.of("inbound", "outbound", "taxi"), rows.stream().map(ExpenseRow::itemId).toList());
        assertLeg(rows.get(0), day(12), "深圳-宁波", null, 200L);
        assertLeg(rows.get(1), day(12), "宁波-深圳", null, 100L);
        assertEquals(Map.of("TRANSPORT", 100L, "TAXI", 30L), rows.get(1).amounts());
        assertLeg(rows.get(2), day(12), null, null, null);
        assertEquals(Map.of("TAXI", 70L), rows.get(2).amounts());
    }

    @Test
    void snapshotRejectsAmountsAboveTheIntegerCentRangeAcrossDestinationRows() {
        var first = new ExpenseMatcher.Item("first", "RAIL", day(12), day(12), "", 1,
                Map.of("origin", "宁波", "destination", "深圳"),
                Map.of(TRANSPORT, new ExpenseAmounts.Amount(null, "CNY", Long.MAX_VALUE, "人工确认")), List.of());
        var second = new ExpenseMatcher.Item("second", "RAIL", day(13), day(13), "", 2,
                Map.of("origin", "宁波", "destination", "杭州"),
                Map.of(TRANSPORT, new ExpenseAmounts.Amount(null, "CNY", 1L, "人工确认")), List.of());
        var view = new CaseView(fixture("未指定地点", List.of()).info(), List.of(first, second), List.of(), List.of(), List.of(),
                Set.of(), List.of(), List.of(), List.of(), List.of());
        assertEquals("AMOUNT_OUT_OF_RANGE", assertThrows(CaseException.class, () -> ExportSnapshot.fromDraft(view)).code());
    }

    private static void assertLeg(ExpenseRow row, LocalDate day, String route, String vehicle, Long amount) {
        assertEquals(day, row.startDate(), row.itemId());
        assertEquals(route, row.fields().get("route"), row.itemId());
        assertEquals(vehicle, row.fields().get("vehicle"), row.itemId());
        assertEquals(amount, row.amounts().get("TRANSPORT"), row.itemId());
    }

    private static Evidence evidence(String id, String subtype, String filename, FieldCandidate... candidates) {
        var fields = new ArrayList<>(List.of(candidates));
        fields.add(candidate("subtype", subtype));
        return new Evidence(id, id, id, filename, List.of(new PageResult(1, PageResult.Kind.INVOICE, true,
                fields, List.of(), List.of(), List.of(), PageParser.VERSION)));
    }

    private static FieldCandidate candidate(String field, String value) {
        return new FieldCandidate(field, value, value, PageResult.Method.PDF_TEXT, null, "票面");
    }

    private static CaseView withEvidence(CaseView view, Map<ExpenseRow, Evidence> associated) {
        var sources = associated.values().stream().map(evidence -> new CaseView.Source(evidence.docId(),
                evidence.sha256(), evidence.relativePath(), List.of(evidence.relativePath()), false, null,
                evidence.pages(), List.of(evidence))).toList();
        var links = associated.entrySet().stream().map(entry -> new ExpenseMatcher.Link(entry.getKey().itemId(),
                entry.getValue().unitId(), ExpenseMatcher.Role.CHARGE, "已关联来源")).toList();
        return new CaseView(view.info(), view.items(), sources, links, view.claims(), view.duplicateUnitIds(),
                view.attachments(), view.unavailable(), view.overrides(), view.attachmentSettings());
    }

    private static Map<String, Long> sumByComponent(List<ExpenseRow> rows) {
        return rows.stream().flatMap(row -> row.amounts().entrySet().stream())
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, Math::addExact));
    }

    private static Evidence ticket(String id, String subtype, String origin, String destination, String day,
                                   String amount, String invoiceNumber) {
        var values = Map.of("subtype", subtype, "origin", origin, "destination", destination, "startDate", day,
                "endDate", day, "invoiceDate", "2026-11-15", "invoiceNumber", invoiceNumber, "gross", amount, "currency", "CNY");
        var fields = values.entrySet().stream()
                .map(entry -> new FieldCandidate(entry.getKey(), entry.getValue(), entry.getValue(), PageResult.Method.PDF_TEXT,
                        null, "票面")).toList();
        var page = new PageResult(1, PageResult.Kind.INVOICE, true, fields, List.of(), List.of(), List.of(), PageParser.VERSION);
        return new Evidence(id, id, id, id + ".pdf", List.of(page));
    }

    private static ExpenseRow row(String id, String subtype, LocalDate start, LocalDate end,
                                  Map<String, String> fields, Map<String, Long> amounts) {
        return new ExpenseRow(id, subtype, start, end, subtype, fields, amounts);
    }

    private static LocalDate day(int day) {
        return LocalDate.of(2026, 11, day);
    }

    private static CaseView fixture(String folder, List<OverrideView> manual) {
        var info = new CaseInfo("case", Path.of("."), Path.of(folder), CaseType.TRAVEL, 1, "template", "rules", 1, null);
        return new CaseView(info, List.of(), List.of(), List.of(), List.of(), Set.of(), List.of(), List.of(), manual, List.of());
    }

    private static OverrideView manual(ExpenseRow row, String key) {
        String value = "startDate".equals(key) ? row.startDate().toString() : row.fields().get(key);
        return new OverrideView(row.itemId(), key, "SET", value, List.of(), 1);
    }

    private static OverrideView clear(ExpenseRow row, String key) {
        return new OverrideView(row.itemId(), key, "CLEAR", null, List.of(), 1);
    }
}
