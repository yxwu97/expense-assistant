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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static expenseassistant.matching.ExpenseAmounts.Component.*;
import static org.junit.jupiter.api.Assertions.*;

class TravelSummaryRowsTest {
    @Test
    void datedRoundTripCombinesMatchedTicketsAndDeduplicatesTransport() {
        var flight = ticket("go", "FLIGHT", "宁波", "深圳", "2026-11-12", "796.00", "10000000000000000001");
        var duplicate = new Evidence("copy", "copy", flight.sha256(), "来程副本.pdf", flight.pages());
        var rail = ticket("back", "RAIL", "深圳北站", "宁波站", "2026-11-13", "200.00", "10000000000000000002");
        var matched = new ExpenseMatcher().propose("case", CaseType.TRAVEL, List.of(flight, rail, duplicate), Map.of());
        var base = fixture("无可用目录地名", List.of());
        var view = new CaseView(base.info(), matched.items(), List.of(), matched.links(), matched.claims(),
                matched.duplicates(), List.of(), List.of(), List.of(), List.of());

        var snapshot = ExportSnapshot.fromDraft(view);
        assertEquals(1, snapshot.rows().size());
        var row = snapshot.rows().getFirst();
        assertEquals(Map.of("route", "宁波->深圳", "vehicle", "火车、飞机"), row.fields());
        assertEquals(day(12), row.startDate());
        assertEquals(day(13), row.endDate());
        assertEquals(Map.of("TRANSPORT", 99600L), row.amounts());
        assertEquals(matched.claims(), snapshot.audit().claims());
        assertEquals(2, snapshot.audit().amounts().size());
        assertEquals(Set.of(duplicate.unitId()), view.duplicateUnitIds());
    }

    @Test
    void directoryOrientsUndatedReturnAndFillsMissingBusinessDates() {
        var back = row("return", "FLIGHT", null, null, Map.of("origin", "深圳", "destination", "宁波", "vehicle", "飞机"),
                Map.of("TRANSPORT", 79600L));
        var taxi = row("taxi", "RIDE", day(13), day(13), Map.of("route", "车站 → 酒店", "vehicle", "的士"),
                Map.of("TAXI", 0L));
        var group = TravelSummaryRows.summarize(fixture("深圳出差-2026.11.12~13", List.of()), List.of(back, taxi)).getFirst();
        assertEquals(Map.of("route", "宁波->深圳", "vehicle", "飞机"), group.fields());
        assertEquals(day(12), group.startDate());
        assertEquals(day(13), group.endDate());
        assertEquals(Map.of("TRANSPORT", 79600L, "TAXI", 0L), group.amounts());
    }

    @Test
    void confirmedIntercityDestinationIsRetainedAndTaxiEndpointsAreIgnored() {
        var leg = row("onward", "RAIL", day(14), day(14),
                Map.of("origin", "深圳", "destination", "广州"), Map.of("TRANSPORT", 3200L));
        var taxi = row("taxi", "RIDE", day(14), day(14),
                Map.of("origin", "广州站", "destination", "珠江新城", "vehicle", "的士"), Map.of("TAXI", 500L));
        var view = fixture("深圳出差-2026.11.12~14",
                List.of(manual(leg, "destination"), manual(taxi, "destination")));
        var rows = TravelSummaryRows.summarize(view, List.of(leg, taxi));
        assertEquals(1, rows.size());
        assertEquals(Map.of("route", "深圳->广州"), rows.getFirst().fields());
        assertEquals(Map.of("TRANSPORT", 3200L, "TAXI", 500L), rows.getFirst().amounts());
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
        var manual = row("subsidy", "MANUAL", day(12), day(12), Map.of("destination", "深圳"),
                Map.of("MEAL_SUBSIDY", 0L));
        var partial = row("partial", "RAIL", null, null, Map.of("destination", "杭州"),
                Map.of("TRANSPORT", 9L));
        var view = fixture("深圳出差-2026.11.12~13", List.of(manual(manual, "destination")));
        var entries = List.of(unknown, taxi, hotel, hangzhou, manual, shenzhen, partial);
        var rows = TravelSummaryRows.summarize(view, entries);
        assertEquals(List.of("深圳", "", "宁波站->杭州东站"),
                rows.stream().map(row -> row.fields().getOrDefault("route", "")).toList());
        assertEquals(Map.of("TRANSPORT", 100L, "HOTEL", 500L, "MEAL_SUBSIDY", 0L), rows.get(0).amounts());
        assertEquals(Map.of("HOTEL", 999L), rows.get(1).amounts());
        assertEquals(Map.of("TRANSPORT", 209L, "TAXI", 70L), rows.get(2).amounts());
        assertEquals(sumByComponent(entries), sumByComponent(rows));
    }

    @Test
    void incompleteRoutesUseOnlyValidDirectoryFallbackAndToolsAreDeduplicated() {
        var row = row("partial", "RAIL", null, null,
                Map.of("origin", "宁波站", "vehicle", "飞机，火车 、 私车 / 飞机"), Map.of("TRANSPORT", 0L));
        var fallback = TravelSummaryRows.summarize(fixture("深圳出差-2026.11.12~13", List.of()), List.of(row)).getFirst();
        assertEquals("深圳", fallback.fields().get("route"));
        assertEquals("火车、飞机、私车", fallback.fields().get("vehicle"));
        assertEquals(day(12), fallback.startDate());
        assertEquals(day(13), fallback.endDate());
        var invalid = fixture("深圳出差-2026.11.31", List.of());
        var blank = TravelSummaryRows.summarize(invalid, List.of(row)).getFirst();
        assertFalse(blank.fields().containsKey("route"));
        assertNull(blank.startDate());
        assertNull(blank.endDate());
        assertEquals(Map.of("TRANSPORT", 0L), blank.amounts());
        assertEquals(List.of(), TravelSummaryRows.summarize(invalid, List.of()));
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
        assertEquals(day(15), verified.endDate());
        assertFalse(TravelSummaryRows.summarize(view, List.of(first, repeated)).getFirst().fields().containsKey("nights"));
        assertFalse(TravelSummaryRows.summarize(view, List.of(first, undated)).getFirst().fields().containsKey("nights"));
        var unknown = TravelSummaryRows.summarize(view, List.of(unconfirmed)).getFirst();
        assertFalse(unknown.fields().containsKey("nights"));
        assertEquals(Map.of(), unknown.amounts());
        assertEquals(day(14), unknown.startDate());
        assertEquals(day(14), unknown.endDate());
    }

    @Test
    void ticketsWithSameDayReverseRoutesNeedAPlaceAnchor() {
        var outbound = row("outbound", "RAIL", day(12), day(12), Map.of("origin", "宁波", "destination", "深圳"),
                Map.of("TRANSPORT", 100L));
        var inbound = row("inbound", "FLIGHT", day(12), day(12), Map.of("origin", "深圳", "destination", "宁波"),
                Map.of("TRANSPORT", 200L));
        var group = TravelSummaryRows.summarize(fixture("未指定地点", List.of()), List.of(outbound, inbound)).getFirst();
        assertFalse(group.fields().containsKey("route"));
        assertEquals(Map.of("TRANSPORT", 300L), group.amounts());
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
        return new OverrideView(row.itemId(), key, "SET", row.fields().get(key), List.of(), 1);
    }
}
