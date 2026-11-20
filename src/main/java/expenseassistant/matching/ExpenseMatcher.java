package expenseassistant.matching;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import expenseassistant.intake.TypeInference.CaseType;
import expenseassistant.matching.ExpenseAmounts.Amount;
import expenseassistant.matching.ExpenseAmounts.Component;
import expenseassistant.recognition.PageResult;
import expenseassistant.recognition.PageResult.Kind;

public final class ExpenseMatcher {
    private static final Pattern NATURAL_TOKENS = Pattern.compile("[0-9]+|[^0-9]+");
    private static final Pattern MILEAGE_FILENAME = Pattern.compile(
            "^(.+?)[_\\-—－ ]+(20[0-9]{2})(?:([0-9]{2})([0-9]{2})|[.\\-/年]([0-9]{1,2})[.\\-/月]([0-9]{1,2})日?)$");
    private static final Pattern MILEAGE_DIRECTION = Pattern.compile("^(.+?)[_\\-—－ ]+(单程|往返)$");
    private static final Pattern FILE_ORDER = Pattern.compile("订单\\s*([A-Z0-9]{5,32})(?![A-Z0-9])",
            Pattern.CASE_INSENSITIVE);
    public static final Comparator<String> NATURAL_PATH_ORDER = ExpenseMatcher::naturalCompare;

    public record Evidence(String unitId, String docId, String sha256, String relativePath, List<PageResult> pages) {
        public Evidence {
            pages = List.copyOf(pages);
        }

        public PageResult page() { return pages.getFirst(); }
        public String subtype() { return Objects.requireNonNullElse(page().value("subtype"), "UNCLASSIFIED"); }
        public String orderKey() {
            String content = page().value("orderKey");
            var filename = FILE_ORDER.matcher(basename(relativePath));
            return content != null ? content : filename.find() ? filename.group(1).toUpperCase(Locale.ROOT) : null;
        }
        public String invoiceKey() { return invoiceIdentityOf(page()); }
        public Long amount() { return page().faceMinor(); }
        public String platform() { return page().value("platform"); }
        public String receiptKey() { return ExpenseMatcher.receiptKey(page()); }

        public Long usdMinor() {
            return "USD".equals(page().value("currency")) && !amountUncertain()
                    ? ExpenseAmounts.usdToCnyMinor(page().value("gross")) : null;
        }

        public Long chargeMinor() {
            return amountUncertain() ? null : "USD".equals(page().value("currency")) ? usdMinor() : amount();
        }

        public String usdBasis() {
            return ExpenseAmounts.usdBasis(page().value("gross"));
        }

        private boolean amountUncertain() {
            return amountConflict() || page().warnings().contains("CURRENCY_NEEDS_REVIEW");
        }

        private boolean amountConflict() {
            return pages.stream().anyMatch(ExpenseMatcher::hasAmountConflict)
                    || pages.stream().map(ExpenseMatcher::face).filter(Objects::nonNull).distinct().limit(2).count() > 1;
        }
    }

    public record Item(String id, String subtype, LocalDate startDate, LocalDate endDate, String description,
                       long stableOrder, Map<String, String> fields, Map<Component, Amount> amounts,
                       List<String> reviewReasons) {
        public Item {
            fields = Map.copyOf(fields);
            amounts = Map.copyOf(amounts);
            reviewReasons = List.copyOf(reviewReasons);
        }
    }

    public enum Role { CHARGE, SUPPORT, REPLACEMENT, PENDING }

    public record Link(String itemId, String unitId, Role role, String basis) {}
    public record Claim(String chargeKey, String itemId, Component component, Long minor) {}
    public record MatchResult(List<Item> items, List<Link> links, List<Claim> claims, Set<String> duplicates) {
        public MatchResult {
            items = List.copyOf(items);
            links = List.copyOf(links);
            claims = List.copyOf(claims);
            duplicates = Set.copyOf(duplicates);
        }
    }

    public static List<Evidence> groupPages(String docId, String hash, String path, List<PageResult> pages) {
        List<Evidence> groups = new ArrayList<>();
        List<PageResult> run = new ArrayList<>();
        for (PageResult page : pages) {
            if (!run.isEmpty() && !sameIdentity(run.getFirst(), page)) {
                groups.add(new Evidence(stableId(docId, Integer.toString(run.getFirst().pageNo())), docId, hash, path, run));
                run = new ArrayList<>();
            }
            run.add(page);
        }
        if (!run.isEmpty()) {
            groups.add(new Evidence(stableId(docId, Integer.toString(run.getFirst().pageNo())), docId, hash, path, run));
        }
        return groups;
    }

    public MatchResult propose(String caseId, CaseType type, Collection<Evidence> sources,
                               Map<String, Long> oldOrder) {
        return propose(caseId, type, sources, oldOrder, Map.of());
    }

    /** Prior pairings are keyed by content-derived unit IDs and survive path-only renames. */
    public MatchResult propose(String caseId, CaseType type, Collection<Evidence> sources,
                               Map<String, Long> oldOrder, Map<String, Set<String>> priorTripPairs) {
        List<Evidence> sorted = sources.stream().sorted(Comparator.comparing(Evidence::relativePath, NATURAL_PATH_ORDER)
                .thenComparingInt(value -> value.page().pageNo())).toList();
        State state = new State(caseId, type);
        Map<String, List<Evidence>> invoiceCopies = groups(sorted, evidence -> evidence.invoiceKey());
        var conflictingInvoices = rankInvoices(state, sorted, invoiceCopies, priorTripPairs);
        matchTolls(state, sorted.stream().filter(ExpenseMatcher::isTollSummary).toList(), invoiceCopies, conflictingInvoices);
        holdConflictingInvoices(state, invoiceCopies, conflictingInvoices);
        matchTaxi(state, sorted, priorTripPairs);
        matchReceipts(state, sorted);
        for (Evidence source : sorted) {
            if (state.used.contains(source.unitId()) || state.duplicates.contains(source.unitId())) continue;
            if (source.page().kind() == Kind.INVOICE) standalone(state, source);
            if (source.page().kind() == Kind.MILEAGE || unknownMileage(state.type, source)) mileage(state, source);
        }
        return new MatchResult(orderedItems(state.items, oldOrder), state.links, state.claims, state.duplicates);
    }

    private static Set<String> rankInvoices(State state, List<Evidence> sorted, Map<String, List<Evidence>> invoiceCopies,
                                            Map<String, Set<String>> priorTripPairs) {
        var availableTrips = sorted.stream().filter(evidence -> evidence.page().kind() == Kind.TRIP_STATEMENT).toList();
        Set<String> conflictingInvoices = new LinkedHashSet<>();
        for (var entry : invoiceCopies.entrySet()) {
            var copies = entry.getValue();
            if (conflictingFaces(copies)) {
                conflictingInvoices.add(entry.getKey());
            } else if (copies.size() > 1) {
                var ranked = copies.stream().sorted(Comparator.<Evidence>comparingInt(copy -> {
                    if (priorTripPairs.containsKey(copy.unitId())) {
                        return 0;
                    }
                    if (availableTrips.stream().anyMatch(trip -> taxiHeuristics(copy, trip))) {
                        return 1;
                    }
                    return 2;
                }).thenComparing(Evidence::relativePath, NATURAL_PATH_ORDER)).toList();
                entry.setValue(ranked);
                ranked.stream().skip(1).forEach(copy -> state.duplicates.add(copy.unitId()));
            }
        }
        return conflictingInvoices;
    }

    private static void holdConflictingInvoices(State state, Map<String, List<Evidence>> invoiceCopies,
                                                Set<String> conflictingInvoices) {
        for (String conflict : conflictingInvoices) {
            var copies = invoiceCopies.get(conflict);
            if (state.used.contains(copies.getFirst().unitId())) {
                continue;
            }
            var first = copies.getFirst();
            String item = state.add("invoice:" + conflict, first.subtype(), first.page().date("startDate"),
                    first.page().date("endDate"), description(first.subtype()), Map.of(),
                    amount(state, first.subtype(), null, null, null, "发票币种或票面金额冲突"), List.of("INVOICE_AMOUNT_CONFLICT"));
            for (Evidence copy : copies) {
                state.link(item, copy, Role.PENDING, "同号发票币种或金额待核对");
            }
        }
    }

    private static boolean unknownMileage(CaseType type, Evidence source) {
        return source.page().kind() == Kind.UNKNOWN && type == CaseType.PRIVATE_CAR
                && !source.relativePath().toLowerCase(Locale.ROOT).endsWith(".pdf")
                && MILEAGE_FILENAME.matcher(Normalizer.normalize(basename(source.relativePath()), Normalizer.Form.NFKC)).matches();
    }

    private static List<Item> orderedItems(List<Item> items, Map<String, Long> oldOrder) {
        long next = oldOrder.values().stream().mapToLong(Long::longValue).max().orElse(0);
        var order = Comparator.comparing(Item::startDate, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparingInt(item -> switch (item.subtype()) {
                    case "TOLL" -> 1;
                    case "PARKING" -> 2;
                    default -> 0;
                });
        List<Item> ordered = new ArrayList<>();
        for (var item : items.stream().sorted(order).toList()) {
            Long previous = oldOrder.get(item.id());
            long chosen = previous == null ? ++next : previous;
            ordered.add(new Item(item.id(), item.subtype(), item.startDate(), item.endDate(), item.description(),
                    chosen, item.fields(), item.amounts(), item.reviewReasons()));
        }
        ordered.sort(Comparator.comparing(Item::startDate, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparingLong(Item::stableOrder));
        return ordered;
    }

    public static boolean invoiceAmountConflict(String key, Collection<Evidence> sources) {
        return key != null && conflictingFaces(sources.stream().filter(source -> key.equals(source.invoiceKey())).toList());
    }

    private static boolean conflictingFaces(List<Evidence> copies) {
        if (copies.stream().anyMatch(Evidence::amountConflict)) return true;
        if (copies.size() < 2) return false;
        var faces = copies.stream().map(source -> source.amountUncertain() ? null : face(source.page())).toList();
        return faces.contains(null) || faces.stream().distinct().count() > 1;
    }

    private static boolean hasAmountConflict(PageResult page) {
        return page.warnings().contains("AMOUNT_CONFLICT") || page.warnings().contains("CURRENCY_CONFLICT");
    }

    private record Face(String currency, BigDecimal amount) {}

    private static Face face(PageResult page) {
        String currency = page.value("currency");
        String gross = page.value("gross");
        if (currency == null || gross == null) return null;
        try {
            var decimal = new BigDecimal(gross);
            return decimal.signum() < 0 ? null : new Face(currency, decimal.stripTrailingZeros());
        } catch (NumberFormatException malformed) {
            return null;
        }
    }

    private static void matchTolls(State state, List<Evidence> summaries, Map<String, List<Evidence>> invoiceCopies,
                                   Set<String> conflictingInvoices) {
        var keyed = groups(summaries, source -> {
            String key = source.page().value("summaryKey");
            return key == null ? "unverified:" + source.unitId() : key;
        });
        Map<String, Integer> referencesPerGroup = new HashMap<>();
        for (var documents : keyed.values()) {
            documents.stream().flatMap(source -> source.pages().stream())
                    .flatMap(page -> page.summaryReferences().stream()).map(PageResult.SummaryReference::invoiceKey)
                    .distinct().forEach(key -> referencesPerGroup.merge(key, 1, Integer::sum));
        }
        for (var entry : keyed.entrySet()) {
            var documents = entry.getValue();
            var first = documents.getFirst();
            Map<String, Long> faces = referenceFaces(first);
            Set<String> referenced = new LinkedHashSet<>(faces.keySet());
            for (Evidence source : documents) {
                referenced.addAll(referenceFaces(source).keySet());
            }
            Long total = first.amount();
            boolean confirmed = first.page().value("summaryKey") != null && !faces.isEmpty() && total != null;
            for (Evidence document : documents) {
                confirmed &= faces.equals(referenceFaces(document)) && Objects.equals(total, document.amount())
                        && !document.page().warnings().contains("SUMMARY_AMOUNT_CONFLICT");
            }
            long matched = 0;
            List<Evidence> selected = new ArrayList<>();
            List<Evidence> rejectedCopies = new ArrayList<>();
            for (String key : referenced) {
                var copies = invoiceCopies.get(key);
                Long expected = faces.get(key);
                if (copies == null) {
                    confirmed = false;
                    continue;
                }
                var original = copies.getFirst();
                selected.add(original);
                if (conflictingInvoices.contains(key)) {
                    rejectedCopies.addAll(copies.subList(1, copies.size()));
                }
                if (conflictingInvoices.contains(key) || referencesPerGroup.getOrDefault(key, 0) != 1
                        || original.amount() == null || expected == null || !expected.equals(original.amount())
                        || !"TOLL".equals(original.subtype())) {
                    confirmed = false;
                } else {
                    matched = Math.addExact(matched, expected);
                }
            }
            confirmed &= total != null && matched == total;
            var component = ExpenseAmounts.inferredComponent(state.type, "TOLL");
            confirmed &= component != null;
            var reasons = new ArrayList<String>();
            if (!confirmed) {
                reasons.add("TOLL_RECONCILIATION_REQUIRED");
            }
            if (referenced.stream().anyMatch(conflictingInvoices::contains)) {
                reasons.add("INVOICE_AMOUNT_CONFLICT");
            }
            var day = first.page().date("startDate");
            if (day == null) {
                reasons.add("BUSINESS_DATE_MISSING");
            }
            String item = state.add("summary:" + entry.getKey(), "TOLL", day, first.page().date("endDate"), "通行费",
                    Map.of("vehicle", "私车"), amount(state, "TOLL", decimal(total), "CNY",
                            confirmed ? total : null, "通行费汇总与明细逐票对账"), reasons);
            for (Evidence original : selected) {
                state.link(item, original, confirmed ? Role.CHARGE : Role.PENDING,
                        confirmed ? "汇总单与通行发票逐分核对" : "通行发票待对账");
                if (confirmed) {
                    state.claim(original, item, component, faces.get(original.invoiceKey()));
                }
            }
            for (Evidence duplicate : rejectedCopies) {
                state.link(item, duplicate, Role.PENDING, "通行费同号币种或金额待核对");
            }
            for (Evidence summary : documents) {
                state.link(item, summary, Role.SUPPORT, "通行费汇总单");
            }
        }
    }

    private static Map<String, Long> referenceFaces(Evidence summary) {
        Map<String, Long> values = new LinkedHashMap<>();
        for (PageResult page : summary.pages()) {
            for (var reference : page.summaryReferences()) {
                var key = reference.invoiceKey();
                if (!values.containsKey(key) || values.get(key) == null) {
                    values.put(key, reference.faceMinor());
                } else if (reference.faceMinor() != null && !reference.faceMinor().equals(values.get(key))) {
                    values.put(key, Long.MIN_VALUE);
                }
            }
        }
        return values;
    }

    private static void matchTaxi(State state, List<Evidence> all, Map<String, Set<String>> priorTripPairs) {
        var invoices = all.stream().filter(source -> source.page().kind() == Kind.INVOICE
                && Set.of("DRIVER", "RIDE").contains(source.subtype())
                && !state.used.contains(source.unitId()) && !state.duplicates.contains(source.unitId())).toList();
        var trips = all.stream().filter(source -> source.page().kind() == Kind.TRIP_STATEMENT).toList();
        Map<String, List<Evidence>> pairs = new HashMap<>();
        Map<String, Integer> reverse = new HashMap<>();
        for (Evidence invoice : invoices) {
            var commonOrder = trips.stream().filter(trip -> invoice.orderKey() != null
                    && invoice.orderKey().equals(trip.orderKey())).toList();
            Set<String> existing = priorTripPairs.getOrDefault(invoice.unitId(), Set.of());
            var candidates = commonOrder.isEmpty()
                    ? trips.stream().filter(trip -> taxiHeuristics(invoice, trip)
                            || existing.contains(trip.unitId()) && sameTaxiBusiness(invoice, trip)).toList()
                    : commonOrder;
            pairs.put(invoice.unitId(), candidates);
            candidates.forEach(trip -> reverse.merge(trip.unitId(), 1, Integer::sum));
        }
        for (Evidence invoice : invoices) {
            var candidates = pairs.get(invoice.unitId());
            if (candidates.isEmpty()) {
                boolean contradictory = trips.stream().anyMatch(trip -> sameTaxiFile(invoice, trip)
                        && (invoice.orderKey() == null || trip.orderKey() == null || invoice.orderKey().equals(trip.orderKey()))
                        && !Objects.equals(invoice.amount(), trip.amount()));
                if (contradictory) {
                    taxi(state, invoice, List.of(), false, List.of("TRIP_MATCH_CONFLICT"));
                } else {
                    standalone(state, invoice);
                }
            } else {
                var selected = candidates.getFirst();
                boolean uniqueEqual = candidates.size() == 1 && reverse.get(selected.unitId()) == 1
                        && invoice.amount() != null && invoice.amount().equals(selected.amount());
                taxi(state, invoice, candidates, uniqueEqual, uniqueEqual ? List.of() : List.of("TRIP_MATCH_CONFLICT"));
            }
        }
    }

    private static void taxi(State state, Evidence invoice, List<Evidence> trips, boolean matched, List<String> reasons) {
        var result = new ArrayList<>(reasons);
        var confirmedTrip = matched ? trips.getFirst() : null;
        LocalDate start = confirmedTrip == null ? null : confirmedTrip.page().date("startDate");
        LocalDate end = confirmedTrip == null ? null : confirmedTrip.page().date("endDate");
        var fields = new LinkedHashMap<String, String>();
        if (confirmedTrip != null) {
            String route = String.join("\n", confirmedTrip.page().segments().stream()
                    .filter(segment -> segment.origin() != null && segment.destination() != null)
                    .map(segment -> segment.origin() + " → " + segment.destination()).distinct().toList());
            if (!route.isBlank()) {
                fields.put("route", route);
            }
        }
        if (start == null) {
            result.add("BUSINESS_DATE_MISSING");
        }
        var component = ExpenseAmounts.inferredComponent(state.type, invoice.subtype());
        boolean billed = matched && component != null && invoice.invoiceKey() != null;
        String item = state.add(invoiceIdentity(invoice), invoice.subtype(), start, end, description(invoice.subtype()),
                fields, amount(state, invoice.subtype(), invoice.page().value("gross"), invoice.page().value("currency"),
                        billed ? invoice.amount() : null, matched ? "发票及行程单对账" : "行程配对待核对"), result);
        state.link(item, invoice, billed ? Role.CHARGE : Role.PENDING, "滴滴发票");
        if (billed) {
            state.claim(invoice, item, component, invoice.amount());
        }
        for (Evidence trip : trips) {
            state.link(item, trip, matched ? Role.SUPPORT : Role.PENDING,
                    matched ? "唯一匹配行程单" : "行程配对候选");
        }
    }

    private static boolean taxiHeuristics(Evidence invoice, Evidence trip) {
        return sameTaxiFile(invoice, trip) && sameTaxiBusiness(invoice, trip);
    }

    private static boolean sameTaxiBusiness(Evidence invoice, Evidence trip) {
        return invoice.platform() != null && invoice.platform().equals(trip.platform())
                && invoice.subtype().equals(trip.subtype())
                && invoice.amount() != null && invoice.amount().equals(trip.amount())
                && (invoice.orderKey() == null || trip.orderKey() == null || invoice.orderKey().equals(trip.orderKey()));
    }

    private static boolean sameTaxiFile(Evidence invoice, Evidence trip) {
        return invoice.platform() != null && invoice.platform().equals(trip.platform())
                && invoice.subtype().equals(trip.subtype()) && taxiFileKey(invoice).equals(taxiFileKey(trip))
                && !taxiFileKey(invoice).isBlank();
    }

    private static String taxiFileKey(Evidence evidence) {
        return Normalizer.normalize(basename(evidence.relativePath()), Normalizer.Form.NFKC)
                .replaceFirst("^(滴滴电子发票|滴滴发票|行程报销单|滴滴行程单|行程单)", "")
                .replaceAll("[\\s_—－-]", "").toLowerCase(Locale.ROOT);
    }

    private static void standalone(State state, Evidence invoice) {
        String subtype = invoice.subtype();
        var component = ExpenseAmounts.inferredComponent(state.type, subtype);
        var amount = invoice.chargeMinor();
        boolean confirmed = amount != null && invoice.invoiceKey() != null && component != null;
        var start = invoice.page().date("startDate");
        var end = invoice.page().date("endDate");
        List<String> reasons = new ArrayList<>();
        if (start == null) {
            reasons.add("BUSINESS_DATE_MISSING");
        }
        if (!confirmed) {
            reasons.add(invoice.amountConflict() ? "INVOICE_AMOUNT_CONFLICT" : "AMOUNT_NEEDS_REVIEW");
        }
        if (Set.of("DRIVER", "RIDE").contains(subtype)) {
            reasons.add("TRIP_STATEMENT_MISSING");
        }
        var fields = new LinkedHashMap<String, String>();
        put(fields, "nights", invoice.page().value("nights"));
        put(fields, "orderKey", invoice.orderKey());
        switch (subtype) {
            case "FLIGHT", "RAIL" -> {
                fields.put("vehicle", "FLIGHT".equals(subtype) ? "飞机" : "火车");
                put(fields, "origin", invoice.page().value("origin"));
                put(fields, "destination", invoice.page().value("destination"));
            }
            case "TOLL", "PARKING" -> fields.put("vehicle", "私车");
            default -> { }
        }
        String currency = invoice.page().value("currency");
        String faceValue = invoice.page().value("gross");
        boolean converted = confirmed && "USD".equals(currency);
        String basis = converted ? invoice.usdBasis()
                : "CNY".equals(currency) ? "发票小写价税合计" : "原币发票待核定人民币金额";
        if (converted) fields.put("note", "原币 " + currency + " " + faceValue);
        String item = state.add(invoiceIdentity(invoice), subtype, start, end, description(subtype), fields,
                amount(state, subtype, faceValue, currency, confirmed ? amount : null, basis), reasons);
        state.link(item, invoice, confirmed ? Role.CHARGE : Role.PENDING, converted ? basis : "票面金额");
        if (confirmed) {
            state.claim(invoice, item, component, amount);
        }
    }

    private static void matchReceipts(State state, List<Evidence> sorted) {
        var receipts = groups(sorted, source -> switch (source.page().kind()) {
            case APPLE_RECEIPT, RECEIPT -> Objects.requireNonNullElse(source.receiptKey(), source.unitId());
            default -> null;
        });
        receipts.forEach((key, copies) -> receipt(state, key, copies));
    }

    private static String receiptKey(PageResult page) {
        String order = page.value("orderKey");
        if (page.kind() == Kind.APPLE_RECEIPT) {
            return order == null ? null : order + ":" + receiptDescription(page);
        }
        String issuer = page.value("receiptIssuer");
        if (issuer == null || issuer.isBlank()) return null;
        issuer = Normalizer.normalize(issuer, Normalizer.Form.NFKC).strip().replaceAll("(?U)\\s+", " ")
                .toLowerCase(Locale.ROOT);
        if (order != null) return "issuer:" + issuer + ":order:" + order.toUpperCase(Locale.ROOT);
        String receipt = page.value("receiptNumber");
        return receipt == null ? null : "issuer:" + issuer + ":receipt:" + receipt.toUpperCase(Locale.ROOT);
    }

    private static String receiptDescription(PageResult page) {
        return Objects.requireNonNullElse(page.value("item"), "收据");
    }

    private static void receipt(State state, String key, List<Evidence> copies) {
        var source = copies.getFirst();
        var page = source.page();
        boolean conflict = conflictingFaces(copies);
        String currency = conflict ? null : page.value("currency");
        String original = conflict ? null : page.value("gross");
        var fields = new LinkedHashMap<String, String>();
        put(fields, "orderKey", page.value("orderKey"));
        put(fields, "receiptNumber", page.value("receiptNumber"));
        put(fields, "receiptIssuer", page.value("receiptIssuer"));
        if (original != null && currency != null) {
            fields.put("note", "原币 " + currency + " " + original);
        }
        var day = page.date("receiptDate");
        boolean namedItem = page.kind() != Kind.APPLE_RECEIPT || page.value("item") != null;
        Long converted = !conflict && namedItem && source.receiptKey() != null
                && ExpenseAmounts.inferredComponent(state.type, "RECEIPT") != null
                ? source.usdMinor() : null;
        String basis = converted != null ? source.usdBasis()
                : conflict ? "收据币种或票面金额冲突" : "原币收据待核定人民币金额";
        var reasons = new ArrayList<String>();
        if (converted == null) reasons.add(conflict ? "RECEIPT_CONFLICT" : "CNY_CONFIRMATION_REQUIRED");
        if (day == null) reasons.add("BUSINESS_DATE_MISSING");
        String item = state.add("receipt:" + key, "RECEIPT", day, day, receiptDescription(page), fields,
                amount(state, "RECEIPT", original, currency, converted, basis), reasons);
        if (conflict) {
            copies.forEach(copy -> state.link(item, copy, Role.PENDING, "同单收据币种或金额待核对"));
        } else {
            state.link(item, source, Role.SUPPORT, converted == null ? "原始收据" : basis);
            copies.stream().skip(1).forEach(copy -> state.duplicates.add(copy.unitId()));
        }
    }

    private static void mileage(State state, Evidence source) {
        if (state.type != CaseType.PRIVATE_CAR && state.type != null) {
            return;
        }
        String base = Normalizer.normalize(basename(source.relativePath()), Normalizer.Form.NFKC);
        var name = MILEAGE_FILENAME.matcher(base);
        Map<String, String> fields = new LinkedHashMap<>();
        put(fields, "rawDistanceKm", source.page().value("selectedRouteDistanceKm"));
        LocalDate date = null;
        if (name.matches()) {
            try {
                String month = name.group(3) == null ? name.group(5) : name.group(3);
                String day = name.group(4) == null ? name.group(6) : name.group(4);
                date = LocalDate.of(Integer.parseInt(name.group(2)), Integer.parseInt(month), Integer.parseInt(day));
            } catch (java.time.DateTimeException invalidDate) {
                // User can fill the business date.
            }
            String route = name.group(1);
            var direction = MILEAGE_DIRECTION.matcher(route);
            if (direction.matches()) {
                route = direction.group(1);
                fields.put("direction", direction.group(2));
            }
            String[] endpoints = route.split("\\s*(?:→|至|—|_|-)\\s*", -1);
            if (endpoints.length == 2 && !endpoints[0].isBlank() && !endpoints[1].isBlank()) {
                fields.put("origin", endpoints[0]);
                fields.put("destination", endpoints[1]);
            }
        }
        var reasons = new ArrayList<String>();
        if (date == null) {
            reasons.add("BUSINESS_DATE_MISSING");
        }
        if (!fields.containsKey("rawDistanceKm")) {
            reasons.add("MILEAGE_NEEDS_REVIEW");
        }
        String item = state.add("mileage:" + source.unitId(), "MILEAGE", date, date, "私车公用", fields,
                Map.of(), reasons);
        state.link(item, source, Role.SUPPORT, "里程截图与命名约定");
    }

    private static String invoiceIdentity(Evidence evidence) {
        return "invoice:" + Objects.requireNonNullElse(evidence.invoiceKey(), evidence.unitId());
    }

    private static Map<Component, Amount> amount(State state, String subtype, String face, String currency,
                                                  Long confirmed, String basis) {
        var component = ExpenseAmounts.inferredComponent(state.type, subtype);
        return component == null ? Map.of() : Map.of(component, new Amount(face, currency, confirmed, basis));
    }

    private static boolean sameIdentity(PageResult first, PageResult next) {
        if (first.kind() != next.kind()) {
            return false;
        }
        String key = switch (first.kind()) {
            case INVOICE -> "invoiceNumber";
            case TOLL_SUMMARY_INVOICES, TOLL_SUMMARY_TRIPS -> "summaryKey";
            case APPLE_RECEIPT, TRIP_STATEMENT -> "orderKey";
            default -> null;
        };
        if (first.kind() == Kind.INVOICE) {
            return invoiceIdentityOf(first) != null && invoiceIdentityOf(first).equals(invoiceIdentityOf(next));
        }
        return key != null && first.value(key) != null && first.value(key).equals(next.value(key));
    }

    private static String invoiceIdentityOf(PageResult page) {
        if (page.kind() != Kind.INVOICE) return null;
        var number = page.value("invoiceNumber");
        var code = page.value("invoiceCode");
        if (number == null) {
            return null;
        }
        return number.length() == 20 ? number : code == null ? null : "LEGACY:" + code + ":" + number;
    }

    private static boolean isTollSummary(Evidence evidence) {
        return evidence.page().kind() == Kind.TOLL_SUMMARY_INVOICES || evidence.page().kind() == Kind.TOLL_SUMMARY_TRIPS;
    }

    private static Map<String, List<Evidence>> groups(Collection<Evidence> sources,
                                                        java.util.function.Function<Evidence, String> key) {
        Map<String, List<Evidence>> groups = new LinkedHashMap<>();
        for (Evidence source : sources) {
            String value = key.apply(source);
            if (value != null) {
                groups.computeIfAbsent(value, ignored -> new ArrayList<>()).add(source);
            }
        }
        return groups;
    }

    private static String basename(String path) {
        int slash = path.lastIndexOf('/');
        int dot = path.lastIndexOf('.');
        return path.substring(slash + 1, dot > slash ? dot : path.length());
    }

    private static String description(String subtype) {
        return switch (subtype) {
            case "TOLL" -> "通行费";
            case "PARKING" -> "停车费";
            case "DRIVER" -> "代驾";
            case "RIDE" -> "网约车";
            case "HOTEL" -> "住宿";
            case "FLIGHT" -> "机票";
            case "RAIL" -> "铁路";
            case "MEAL" -> "餐饮";
            case "GIFT" -> "礼品";
            default -> "发票";
        };
    }

    public static String stableId(String namespace, String key) {
        return UUID.nameUUIDFromBytes((namespace + "/" + key).getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static void put(Map<String, String> map, String key, String value) {
        if (value != null) {
            map.put(key, value);
        }
    }

    public static BigDecimal mileageDistance(String raw, String direction) {
        if (raw == null) {
            return null;
        }
        var distance = new BigDecimal(raw);
        return "往返".equals(direction) ? distance.multiply(BigDecimal.TWO) : distance;
    }

    private static String decimal(Long amount) {
        return amount == null ? null : ExpenseAmounts.decimal(amount);
    }

    private static int naturalCompare(String left, String right) {
        var a = NATURAL_TOKENS.matcher(left);
        var b = NATURAL_TOKENS.matcher(right);
        while (a.find()) {
            if (!b.find()) {
                return 1;
            }
            String x = a.group();
            String y = b.group();
            int result;
            if (Character.isDigit(x.charAt(0)) && Character.isDigit(y.charAt(0))) {
                result = new java.math.BigInteger(x).compareTo(new java.math.BigInteger(y));
            } else {
                result = x.compareToIgnoreCase(y);
            }
            if (result != 0) {
                return result;
            }
        }
        return b.find() ? -1 : left.compareTo(right);
    }

    private static final class State {
        private final String caseId;
        private final CaseType type;
        private final List<Item> items = new ArrayList<>();
        private final List<Link> links = new ArrayList<>();
        private final List<Claim> claims = new ArrayList<>();
        private final Set<String> duplicates = new LinkedHashSet<>();
        private final Set<String> used = new HashSet<>();

        private State(String caseId, CaseType type) {
            this.caseId = caseId;
            this.type = type;
        }

        private String add(String key, String subtype, LocalDate start, LocalDate end, String description,
                           Map<String, String> fields, Map<Component, Amount> amounts, List<String> reasons) {
            String id = stableId(caseId, key);
            items.add(new Item(id, subtype, start, end, description, 0, fields, amounts, reasons));
            return id;
        }

        private void link(String item, Evidence source, Role role, String basis) {
            links.add(new Link(item, source.unitId(), role, basis));
            used.add(source.unitId());
        }

        private void claim(Evidence original, String item, Component component, Long minor) {
            String key = original.invoiceKey();
            if (key != null) {
                claims.add(new Claim(key, item, component, Objects.requireNonNull(minor)));
            }
        }
    }
}
