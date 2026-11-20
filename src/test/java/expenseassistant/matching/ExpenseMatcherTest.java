package expenseassistant.matching;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import expenseassistant.intake.TypeInference.CaseType;
import expenseassistant.matching.ExpenseAmounts.Component;
import expenseassistant.matching.ExpenseMatcher.Evidence;
import expenseassistant.matching.ExpenseMatcher.Role;
import expenseassistant.recognition.PageResult;
import expenseassistant.recognition.PageResult.FieldCandidate;
import expenseassistant.recognition.PageResult.Kind;
import expenseassistant.recognition.PageResult.Method;
import expenseassistant.recognition.PageResult.SummaryReference;

import static org.junit.jupiter.api.Assertions.*;

class ExpenseMatcherTest {
    private final ExpenseMatcher matcher = new ExpenseMatcher();

    @Test
    void trailsWithDifferentOrdersStayIndependentEvenWithTheSameAmount() {
        var first = source("i1", "同名/滴滴电子发票-01.pdf", Kind.INVOICE, Map.of(
                "gross", "131.00", "currency", "CNY", "invoiceNumber", "10000000000000000001",
                "subtype", "DRIVER", "platform", "DIDI", "orderKey", "ORDER101"));
        var second = source("i2", "重名/滴滴电子发票-01.pdf", Kind.INVOICE, Map.of(
                "gross", "131.00", "currency", "CNY", "invoiceNumber", "10000000000000000002",
                "subtype", "DRIVER", "platform", "DIDI", "orderKey", "ORDER102"));
        var sameOrder = source("trip", "行程报销单-01.pdf", Kind.TRIP_STATEMENT, Map.of(
                "gross", "131.00", "currency", "CNY", "startDate", "2026-10-28", "endDate", "2026-10-29",
                "subtype", "DRIVER", "platform", "DIDI", "orderKey", "ORDER101"));
        var matched = matcher.propose("case", CaseType.TAXI, List.of(first, second, sameOrder), Map.of());
        assertEquals(26200L, ExpenseAmounts.confirmedTotal(CaseType.TAXI, matched.items()));
        assertEquals(2, matched.claims().size());
        assertEquals(1, matched.links().stream().filter(link -> link.role() == Role.SUPPORT).count());
        assertEquals(LocalDate.parse("2026-10-28"), matched.items().getFirst().startDate());
        assertNull(matched.items().getLast().startDate());
    }

    @Test
    void intercityTicketsKeepBusinessFieldsAndClaimEachInvoiceOnce() {
        var flightFields = Map.of("subtype", "FLIGHT", "origin", "宁波", "destination", "深圳",
                "startDate", "2026-11-12", "endDate", "2026-11-12", "invoiceDate", "2026-11-15",
                "invoiceNumber", "10000000000000000001", "gross", "796.00", "currency", "CNY");
        var flight = source("flight", "1机票.pdf", Kind.INVOICE, flightFields);
        var copy = source("copy", "2机票副本.pdf", Kind.INVOICE, flightFields);
        var rail = source("rail", "3车票.pdf", Kind.INVOICE,
                Map.of("subtype", "RAIL", "origin", "深圳", "destination", "宁波",
                        "startDate", "2026-11-13", "endDate", "2026-11-13", "invoiceDate", "2026-11-15",
                        "invoiceNumber", "10000000000000000002", "gross", "200.00", "currency", "CNY"));

        var matched = matcher.propose("case", CaseType.TRAVEL, List.of(flight, copy, rail), Map.of());
        assertEquals(2, matched.items().size());
        var outbound = matched.items().getFirst();
        var inbound = matched.items().getLast();
        assertEquals(LocalDate.parse("2026-11-12"), outbound.startDate());
        assertEquals(LocalDate.parse("2026-11-13"), inbound.endDate());
        assertEquals(Map.of("origin", "宁波", "destination", "深圳", "vehicle", "飞机"), outbound.fields());
        assertEquals(Map.of("origin", "深圳", "destination", "宁波", "vehicle", "火车"), inbound.fields());
        assertEquals(Long.valueOf(99600), ExpenseAmounts.confirmedTotal(CaseType.TRAVEL, matched.items()));
        assertEquals(List.of(Component.TRANSPORT, Component.TRANSPORT), matched.claims().stream()
                .map(ExpenseMatcher.Claim::component).toList());
        assertEquals(java.util.Set.of(copy.unitId()), matched.duplicates());
    }

    @Test
    void incompleteTicketDoesNotUseTheFilenameOrInvoiceDateAsTravelEvidence() {
        var flight = source("flight", "宁波-深圳-2026.11.15.pdf", Kind.INVOICE, Map.of("subtype", "FLIGHT", "origin", "宁波",
                "invoiceDate", "2026-11-15", "invoiceNumber", "10000000000000000001", "gross", "796.00", "currency", "CNY"));
        var matched = matcher.propose("case", CaseType.TRAVEL, List.of(flight), Map.of());
        var item = matched.items().getFirst();
        assertEquals(Map.of("origin", "宁波", "vehicle", "飞机"), item.fields());
        assertNull(item.startDate());
        assertNull(item.endDate());
        assertTrue(item.reviewReasons().contains("BUSINESS_DATE_MISSING"));
        assertEquals(Long.valueOf(79600), item.amounts().get(Component.TRANSPORT).confirmedMinor());
    }

    @Test
    void invoiceAtTwoTripSegmentsContributesItsFaceOnlyOnce() {
        String ticket = "10000000000000000003";
        var invoice = source("i", "1.pdf", Kind.INVOICE,
                Map.of("gross", "113.90", "currency", "CNY", "invoiceNumber", ticket, "subtype", "TOLL"));
        var fields = Map.of("summaryKey", "100000232424", "gross", "113.90", "currency", "CNY",
                "startDate", "2026-11-03", "endDate", "2026-11-03", "subtype", "TOLL");
        var one = source("summary-1", "汇总单票据.pdf", Kind.TOLL_SUMMARY_INVOICES, fields,
                List.of(new SummaryReference(ticket, 11390L, null)));
        var two = source("summary-2", "汇总单行程.pdf", Kind.TOLL_SUMMARY_TRIPS, fields,
                List.of(new SummaryReference(ticket, 11390L, 5636L), new SummaryReference(ticket, null, 5754L)));
        var matched = matcher.propose("case", CaseType.TRAVEL, List.of(invoice, one, two), Map.of());
        assertEquals(11390L, ExpenseAmounts.confirmedTotal(CaseType.TRAVEL, matched.items()));
        assertEquals(1, matched.claims().size());
        assertEquals(3, matched.links().size());
        var missing = matcher.propose("case", CaseType.TRAVEL, List.of(one, two), Map.of());
        assertNull(ExpenseAmounts.confirmedTotal(CaseType.TRAVEL, missing.items()));
        assertTrue(missing.items().getFirst().reviewReasons().contains("TOLL_RECONCILIATION_REQUIRED"));
    }

    @Test
    void conflictingVersionsOfOneInvoiceRemainUnconfirmed() {
        var keys = Map.of("currency", "CNY", "invoiceNumber", "10000000000000000001", "subtype", "MEAL");
        var aKeys = new java.util.HashMap<>(keys);
        aKeys.put("gross", "12.35");
        var bKeys = new java.util.HashMap<>(keys);
        bKeys.put("gross", "12.36");
        var a = source("a", "a.pdf", Kind.INVOICE, aKeys);
        var b = source("b", "b.pdf", Kind.INVOICE, bKeys);
        var matched = matcher.propose("case", CaseType.ENTERTAINMENT, List.of(a, b), Map.of());
        assertNull(ExpenseAmounts.confirmedTotal(CaseType.ENTERTAINMENT, matched.items()));
        assertEquals(1, matched.items().size());
        assertTrue(matched.items().getFirst().reviewReasons().contains("INVOICE_AMOUNT_CONFLICT"));
        assertEquals(2, matched.links().stream().filter(link -> link.role() == Role.PENDING).count());
    }

    @Test
    void usdRoundsEachReceiptToCentsAndChecksExcelPrecision() {
        assertEquals(34L, ExpenseAmounts.usdToCnyMinor("0.05"));
        assertEquals(0L, ExpenseAmounts.usdToCnyMinor("0.00"));
        assertEquals(67000L, ExpenseAmounts.usdToCnyMinor("100.00"));
        assertNull(ExpenseAmounts.usdToCnyMinor(null));
        for (String invalid : List.of("", "bad", "-1", "USD 1", "0.001", "1,000.00",
                "12345678901234.56", "10000000000000000000")) {
            assertNull(ExpenseAmounts.usdToCnyMinor(invalid), invalid);
        }
    }

    @Test
    void receiptsWithTheSameIssuerAndOrderCountOnceButDifferentKeysRemainIndependent() {
        var first = receipt("a", "Example Shop", "ORDER100", "USD", "100", "R-01");
        var duplicate = receipt("b", "example  shop", "ORDER100", "USD", "100.00", "R-02");
        var separate = receipt("c", "Example Shop", "ORDER200", "USD", "100.00", "R-01");
        var result = matcher.propose("case", CaseType.OTHER, List.of(first, duplicate, separate), Map.of());
        assertEquals(134000L, ExpenseAmounts.confirmedTotal(CaseType.OTHER, result.items()));
        assertTrue(result.claims().isEmpty());
        assertEquals(java.util.Set.of(duplicate.unitId()), result.duplicates());
        for (var item : result.items()) {
            assertTrue(item.reviewReasons().isEmpty());
            assertEquals("USD 100.00 × 6.7", item.amounts().get(Component.OTHER).basis());
        }
        assertTrue(result.links().stream().allMatch(link -> "USD 100.00 × 6.7".equals(link.basis())));
    }

    @Test
    void receiptsWithoutBusinessKeyAndSameKeyContradictionsRemainUnconfirmed() {
        var first = receipt("a", "Shop", null, "USD", "100.00", "R-01");
        for (var other : List.of(receipt("b", "Shop", null, "CNY", "100.00", "R-01"),
                receipt("b", "Shop", null, "USD", "101.00", "R-01"),
                receipt("b", "Shop", null, "USD", null, "R-01"))) {
            var conflict = matcher.propose("case", CaseType.OTHER, List.of(first, other), Map.of());
            assertEquals(1, conflict.items().size());
            assertNull(conflict.items().getFirst().amounts().get(Component.OTHER).confirmedMinor());
            assertEquals(List.of("RECEIPT_CONFLICT"), conflict.items().getFirst().reviewReasons());
            assertEquals(List.of(Role.PENDING, Role.PENDING), conflict.links().stream().map(ExpenseMatcher.Link::role).toList());
        }
        var unstable = receipt("x", null, "ORDER100", "USD", "100.00", null);
        var pending = matcher.propose("case", CaseType.OTHER, List.of(unstable), Map.of());
        assertEquals(ExpenseMatcher.stableId("case", "receipt:" + unstable.unitId()), pending.items().getFirst().id());
        assertEquals(List.of("CNY_CONFIRMATION_REQUIRED"), pending.items().getFirst().reviewReasons());
        assertNull(ExpenseAmounts.confirmedTotal(CaseType.OTHER, pending.items()));
    }

    @Test
    void usdInvoiceClaimsTheConvertedCentsAndTheSameNumberCannotChargeAcrossCurrencies() {
        var usd = foreignInvoice("a", "USD", "100");
        var copy = foreignInvoice("b", "USD", "100.00");
        var result = matcher.propose("case", CaseType.OTHER, List.of(usd, copy), Map.of());
        assertEquals(67000L, ExpenseAmounts.confirmedTotal(CaseType.OTHER, result.items()));
        assertNull(usd.amount());
        assertEquals(67000L, usd.usdMinor());
        assertEquals(67000L, result.claims().getFirst().minor());
        assertEquals("USD 100.00 × 6.7", result.links().getFirst().basis());
        assertEquals(java.util.Set.of(copy.unitId()), result.duplicates());
        var cny = foreignInvoice("c", "CNY", "100.00");
        var conflict = matcher.propose("case", CaseType.OTHER, List.of(usd, cny), Map.of());
        assertNull(ExpenseAmounts.confirmedTotal(CaseType.OTHER, conflict.items()));
        assertTrue(conflict.claims().isEmpty());
        assertTrue(conflict.items().getFirst().reviewReasons().contains("INVOICE_AMOUNT_CONFLICT"));
        assertEquals(List.of(Role.PENDING, Role.PENDING), conflict.links().stream().map(ExpenseMatcher.Link::role).toList());
    }

    private static Evidence receipt(String id, String issuer, String order, String currency, String amount, String number) {
        var fields = new java.util.LinkedHashMap<String, String>();
        fields.put("receiptDate", "2026-11-20");
        fields.put("receiptIssuer", issuer);
        fields.put("orderKey", order);
        fields.put("currency", currency);
        fields.put("gross", amount);
        fields.put("receiptNumber", number);
        fields.values().removeIf(java.util.Objects::isNull);
        return source(id, id + ".pdf", Kind.RECEIPT, fields);
    }

    private static Evidence foreignInvoice(String id, String currency, String amount) {
        return source(id, id + ".pdf", Kind.INVOICE, Map.of(
                "invoiceNumber", "10000000000000000001", "currency", currency, "gross", amount, "subtype", "MEAL"));
    }

    @Test
    void privateCarProposesOnlyTheSelectedRouteAndPreservesTheDeclaredDirection() {
        var selected = Map.of("selectedRouteDistanceKm", "12.35", "routeDistanceCandidateKm", "10.20");
        var one = source("one", "余姚-公司-单程-2026.11.18.jpg", Kind.MILEAGE, selected);
        var two = source("two", "杭州-宁波-往返-20261103.jpg", Kind.MILEAGE, selected);
        var uncertain = source("unknown", "杭州-宁波-20261104.jpg", Kind.MILEAGE,
                Map.of("distanceKm", "7", "routeDistanceCandidateKm", "12.35"));
        var matched = matcher.propose("case", CaseType.PRIVATE_CAR, List.of(one, two, uncertain), Map.of());
        assertEquals(List.of(LocalDate.parse("2026-11-03"), LocalDate.parse("2026-11-04"), LocalDate.parse("2026-11-18")),
                matched.items().stream().map(ExpenseMatcher.Item::startDate).toList());
        var roundTrip = matched.items().getFirst().fields();
        var single = matched.items().getLast().fields();
        assertEquals("往返", roundTrip.get("direction"));
        assertEquals("单程", single.get("direction"));
        assertEquals("12.35", roundTrip.get("rawDistanceKm"));
        assertEquals("24.70", ExpenseMatcher.mileageDistance(roundTrip.get("rawDistanceKm"), "往返").toPlainString());
        assertEquals("12.35", ExpenseMatcher.mileageDistance(single.get("rawDistanceKm"), "单程").toPlainString());
        assertEquals("12.35", ExpenseMatcher.mileageDistance(single.get("rawDistanceKm"), null).toPlainString());
        assertNull(matched.items().get(1).fields().get("rawDistanceKm"));
        assertTrue(matched.items().get(1).reviewReasons().contains("MILEAGE_NEEDS_REVIEW"));
        assertTrue(matched.items().stream().noneMatch(item -> item.fields().containsKey("distanceKm")));
        assertTrue(matched.links().stream().allMatch(link -> link.role() == Role.SUPPORT));
        assertTrue(matched.items().stream().allMatch(item -> !item.amounts().containsKey(Component.FUEL)));
        assertNull(ExpenseAmounts.confirmedTotal(CaseType.PRIVATE_CAR, matched.items()));
    }

    private static Evidence source(String id, String path, Kind kind, Map<String, String> fields) {
        return source(id, path, kind, fields, List.of());
    }

    private static Evidence source(String id, String path, Kind kind, Map<String, String> fields,
                                   List<SummaryReference> references) {
        var candidates = fields.entrySet().stream().map(field -> new FieldCandidate(field.getKey(), field.getValue(),
                field.getValue(), Method.PDF_TEXT, null, "受控夹具")).toList();
        var page = new PageResult(1, kind, true, candidates, List.of(), references, List.of(), "1");
        return new Evidence("unit-" + id, "doc-" + id, "hash-" + id, path, List.of(page));
    }
}
