package expenseassistant.draft;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import expenseassistant.casework.CaseService.CaseInfo;
import expenseassistant.draft.CaseView.AttachmentSetting;
import expenseassistant.draft.CaseView.OverrideView;
import expenseassistant.draft.CaseView.Source;
import expenseassistant.intake.ScanService;
import expenseassistant.intake.ScanService.Format;
import expenseassistant.intake.TypeInference.CaseType;
import expenseassistant.matching.ExpenseAmounts;
import expenseassistant.matching.ExpenseAmounts.Amount;
import expenseassistant.matching.ExpenseAmounts.Component;
import expenseassistant.matching.ExpenseMatcher;
import expenseassistant.matching.ExpenseMatcher.Claim;
import expenseassistant.matching.ExpenseMatcher.Evidence;
import expenseassistant.matching.ExpenseMatcher.Item;
import expenseassistant.matching.ExpenseMatcher.Link;
import expenseassistant.matching.ExpenseMatcher.Role;
import expenseassistant.output.ExportSnapshot.AttachmentPage;
import expenseassistant.output.ExportSnapshot.Layout;
import expenseassistant.output.ExportSnapshot.UnavailableSource;
import expenseassistant.recognition.AttachmentImages.CropMode;
import expenseassistant.recognition.PageResult;

final class DraftProjection {
    static final String AMOUNT = "AMOUNT:";
    static final String LINK = "LINK:";
    static final String PATH = "PATH:";
    static final String CREATE = "CREATE";
    static final String SOURCE_VERSION = "SOURCE_VERSION";
    static final String MILEAGE_OUT_OF_RANGE = "MILEAGE_OUT_OF_RANGE";
    static final String MILEAGE_NEEDS_REVIEW = "MILEAGE_NEEDS_REVIEW";
    static final String HOTEL_NIGHTS_NEEDS_REVIEW = "HOTEL_NIGHTS_NEEDS_REVIEW";
    static final Comparator<Item> ITEM_ORDER = Comparator
            .comparing(Item::startDate, Comparator.nullsLast(Comparator.naturalOrder())).thenComparingLong(Item::stableOrder);

    static CaseView render(CaseInfo info, List<Item> items, List<Source> sources, List<Link> links, List<Claim> claims,
                           Set<String> duplicates, List<OverrideView> overrides, List<AttachmentSetting> settings,
                           List<UnavailableSource> pathErrors) {
        List<UnavailableSource> unavailable = new ArrayList<>();
        Set<String> excludedPaths = new HashSet<>();
        overrides.stream().filter(value -> value.fieldKey().startsWith(PATH)).forEach(value ->
                excludedPaths.add(value.fieldKey().substring(PATH.length())));
        pathErrors.stream().filter(issue -> !excludedPaths.contains(issue.relativePath()))
                .filter(issue -> issue.docId() == null || sources.stream().noneMatch(source ->
                        source.docId().equals(issue.docId()) && source.error() == null && !source.missing()))
                .forEach(unavailable::add);
        Map<String, Item> itemById = new LinkedHashMap<>();
        items.forEach(item -> itemById.put(item.id(), item));
        duplicates = receiptDuplicates(duplicates, sources, links, itemById);
        List<AttachmentEntry> order = new ArrayList<>();
        for (Source source : sources) {
            if (source.missing() && sources.stream().anyMatch(current -> !current.missing()
                    && current.allPaths().contains(source.relativePath()))) {
                continue;
            }
            for (Evidence evidence : source.units()) {
                var related = links.stream().filter(link -> evidence.unitId().equals(link.unitId())).toList();
                if (duplicates.contains(evidence.unitId()) && related.isEmpty()) {
                    continue;
                }
                var linkedItems = related.stream().map(link -> itemById.get(link.itemId()))
                        .filter(Objects::nonNull).distinct().sorted(ITEM_ORDER).toList();
                Item first = linkedItems.isEmpty() ? null : linkedItems.getFirst();
                Role role = first == null ? Role.PENDING : related.stream().filter(link -> link.itemId().equals(first.id()))
                        .map(Link::role).min(Comparator.comparingInt(DraftProjection::roleOrder)).orElse(Role.PENDING);
                for (PageResult page : evidence.pages()) {
                    var setting = setting(settings, source.docId(), page.pageNo());
                    if (setting != null && setting.exclusionReason() != null) {
                        continue;
                    }
                    if (source.missing() || !page.renderable() || source.error() != null) {
                        unavailable.add(new UnavailableSource(source.docId(), source.relativePath(), page.pageNo(),
                                source.missing() ? "SOURCE_CHANGED" : source.error() != null ? source.error() : "PAGE_UNRENDERABLE"));
                        continue;
                    }
                    String caption = setting == null ? null : setting.caption();
                    if (caption == null) {
                        caption = first == null ? "凭证待核对" : defaultCaption(first);
                    }
                    Layout layout = setting == null || setting.layout() == null ? Layout.TWO_PER_PAGE : setting.layout();
                    var attachment = new AttachmentPage(source.docId(), source.sha256(), source.relativePath(), page.pageNo(),
                            linkedItems.stream().map(Item::id).toList(), role, caption, layout,
                            cropMode(source.relativePath(), page.kind()));
                    order.add(new AttachmentEntry(attachment, first, setting == null ? null : setting.orderKey()));
                }
            }
        }
        var attachmentOrder = Comparator.comparing(AttachmentEntry::explicitOrder, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(AttachmentEntry::first, Comparator.nullsLast(ITEM_ORDER))
                .thenComparingInt(entry -> roleOrder(entry.page().role()))
                .thenComparing(entry -> entry.page().relativePath(), ExpenseMatcher.NATURAL_PATH_ORDER)
                .thenComparingInt(entry -> entry.page().sourcePage());
        return new CaseView(info, items, sources, links, claims, duplicates,
                order.stream().sorted(attachmentOrder).map(AttachmentEntry::page).toList(),
                unavailable.stream().distinct().toList(), overrides, settings);
    }

    private static CropMode cropMode(String path, PageResult.Kind kind) {
        if (path == null || path.isBlank()) return CropMode.NONE;
        Format format = ScanService.formatOf(Path.of(path));
        if (format != null && kind == PageResult.Kind.TRIP_STATEMENT) return CropMode.TRIP_STATEMENT;
        if ((format == Format.PNG || format == Format.JPEG) && kind == PageResult.Kind.INVOICE) {
            return CropMode.INVOICE_PHOTO;
        }
        return CropMode.NONE;
    }

    static List<Evidence> eligibleEvidence(List<Source> sources, List<AttachmentSetting> settings) {
        return sources.stream().filter(source -> !source.missing() && source.error() == null)
                .flatMap(source -> source.units().stream())
                .filter(unit -> unit.pages().stream().allMatch(PageResult::renderable))
                .filter(unit -> unit.pages().stream().noneMatch(page -> excluded(settings, unit.docId(), page.pageNo())))
                .toList();
    }

    static boolean usdRefreshChangesAmounts(CaseView before) {
        var eligible = eligibleEvidence(before.sources(), before.attachmentSettings());
        var usd = eligible.stream().filter(unit -> unit.usdMinor() != null).toList();
        if (usd.isEmpty()) return false;

        var units = usdAndCopies(eligible, usd);
        var after = recompute(before.info(), before, eligible, before.overrides());
        var itemIds = Stream.concat(before.links().stream(), after.links().stream())
                .filter(link -> units.contains(link.unitId())).map(Link::itemId).collect(Collectors.toSet());
        return !amountsFor(before.items(), itemIds).equals(amountsFor(after.items(), itemIds))
                || !linksFor(before.links(), itemIds).equals(linksFor(after.links(), itemIds))
                || !claimsFor(before.claims(), itemIds).equals(claimsFor(after.claims(), itemIds));
    }

    private static Set<String> usdAndCopies(List<Evidence> eligible, List<Evidence> usd) {
        var units = usd.stream().map(Evidence::unitId).collect(Collectors.toSet());
        var invoices = usd.stream().map(Evidence::invoiceKey).filter(Objects::nonNull).collect(Collectors.toSet());
        var receipts = usd.stream().map(Evidence::receiptKey).filter(Objects::nonNull).collect(Collectors.toSet());
        eligible.stream().filter(unit -> invoices.contains(unit.invoiceKey()) || receipts.contains(unit.receiptKey()))
                .map(Evidence::unitId).forEach(units::add);
        return units;
    }

    private static Map<String, AmountReview> amountsFor(List<Item> items, Set<String> ids) {
        return items.stream().filter(item -> ids.contains(item.id())).collect(Collectors.toMap(Item::id,
                item -> new AmountReview(item.amounts(), Set.copyOf(item.reviewReasons()))));
    }

    private static Set<Link> linksFor(List<Link> links, Set<String> ids) {
        return links.stream().filter(link -> ids.contains(link.itemId())).collect(Collectors.toSet());
    }

    private static Set<Claim> claimsFor(List<Claim> claims, Set<String> ids) {
        return claims.stream().filter(claim -> ids.contains(claim.itemId())).collect(Collectors.toSet());
    }

    static Projection recompute(CaseInfo info, CaseView before, List<Source> sources, List<OverrideView> overrides,
                                List<AttachmentSetting> settings) {
        return recompute(info, before, eligibleEvidence(sources, settings), overrides);
    }

    private static Projection recompute(CaseInfo info, CaseView before, List<Evidence> eligible,
                                        List<OverrideView> overrides) {
        Map<String, Evidence> known = new LinkedHashMap<>();
        eligible.forEach(evidence -> known.put(evidence.unitId(), evidence));
        Map<String, Long> oldOrder = new HashMap<>();
        before.items().forEach(item -> oldOrder.put(item.id(), item.stableOrder()));
        var receipts = retainReceiptIdentities(before, eligible, overrides,
                new ExpenseMatcher().propose(info.id(), info.type(), eligible, oldOrder, priorTripPairs(before)));
        var proposal = receipts.match();
        Map<String, MutableItem> items = new LinkedHashMap<>();
        proposal.items().forEach(item -> items.put(item.id(), new MutableItem(item)));
        Set<String> anchoredItems = new HashSet<>();
        overrides.forEach(value -> anchoredItems.add(value.targetId()));
        Set<String> userCreated = new HashSet<>();
        overrides.stream().filter(value -> CREATE.equals(value.fieldKey())).forEach(value -> userCreated.add(value.targetId()));
        for (Item item : before.items()) {
            if (anchoredItems.contains(item.id()) && !items.containsKey(item.id()) && !userCreated.contains(item.id())
                    && !receipts.mergedIds().contains(item.id())) {
                var retained = new MutableItem(item);
                retained.amounts.replaceAll((component, amount) -> amount.confirmed(null, "凭证变化后待核对"));
                retained.reasons.add("SOURCE_REVIEW_REQUIRED");
                items.put(item.id(), retained);
            }
        }
        var links = new ArrayList<>(proposal.links());
        long nextOrder = java.util.stream.Stream.concat(oldOrder.values().stream(),
                        proposal.items().stream().map(Item::stableOrder))
                .mapToLong(Long::longValue).max().orElse(0);
        for (OverrideView override : overrides) {
            if (!CREATE.equals(override.fieldKey())) {
                continue;
            }
            var manual = DraftStore.decode(override.value(), ManualItem.class);
            var evidence = known.get(manual.unitId());
            var item = items.get(override.targetId());
            if (item == null) {
                var existingOrder = oldOrder.get(override.targetId());
                item = new MutableItem(new Item(override.targetId(), "MANUAL",
                        manual.startDate() == null ? null : LocalDate.parse(manual.startDate()),
                        manual.endDate() == null ? null : LocalDate.parse(manual.endDate()),
                        manual.description(), existingOrder == null ? ++nextOrder : existingOrder, Map.of(), Map.of(),
                        List.of("AMOUNT_NEEDS_REVIEW")));
                items.put(item.id, item);
            }
            if (evidence == null) {
                item.reasons.add("SOURCE_REVIEW_REQUIRED");
            } else {
                links.add(new Link(item.id, manual.unitId(), Role.SUPPORT, "人工登记费用来源"));
            }
        }
        var claims = new LinkedHashMap<String, Claim>();
        proposal.claims().forEach(claim -> claims.put(claim.chargeKey(), claim));
        var changedAmounts = new LinkedHashSet<AmountOwner>();
        for (OverrideView override : overrides) {
            var item = items.get(override.targetId());
            if (item != null && override.fieldKey().startsWith(LINK)) {
                applyLink(info, item, override, known, items, links, claims, changedAmounts);
            }
        }
        holdChangedSources(overrides, known, items, links, claims, changedAmounts);
        projectChangedAmounts(items, claims, changedAmounts);
        for (OverrideView override : overrides) {
            var item = items.get(override.targetId());
            if (item == null || override.fieldKey().startsWith(LINK) || override.fieldKey().startsWith(PATH)
                    || CREATE.equals(override.fieldKey())) {
                continue;
            }
            if (override.fieldKey().startsWith(AMOUNT)) {
                var component = Component.valueOf(override.fieldKey().substring(AMOUNT.length()));
                if (ExpenseAmounts.derivedMileageFuel(item.subtype, component)) {
                    continue;
                }
                var value = DraftStore.decode(override.value(), ConfirmedAmount.class);
                var preceding = before.items().stream().filter(original -> original.id().equals(item.id))
                        .map(original -> original.amounts().get(component)).filter(Objects::nonNull).findFirst();
                var basis = item.amounts.getOrDefault(component, preceding.orElse(new Amount(null, null, null, "人工核对")));
                if (!hashes(item.id, links, known).containsAll(override.sourceHashes())) {
                    item.reasons.add("SOURCE_REVIEW_REQUIRED");
                    item.amounts.put(component, basis.confirmed(null, "依赖的凭证已变化，请重新核定"));
                } else {
                    item.amounts.put(component, basis.confirmed(value.minor(), value.basis()));
                    if (value.minor() != null) {
                        item.reasons.remove("AMOUNT_NEEDS_REVIEW");
                        item.reasons.remove("CNY_CONFIRMATION_REQUIRED");
                        item.reasons.remove("INVOICE_AMOUNT_CONFLICT");
                        item.reasons.remove("RECEIPT_CONFLICT");
                    }
                }
            } else {
                if ("MILEAGE".equals(item.subtype) && "rawDistanceKm".equals(override.fieldKey())) {
                    continue;
                }
                item.field(override.fieldKey(), override.value());
            }
        }
        preserveTypeRemapping(info, before, items, overrides);
        for (String receipt : receipts.conflicts()) {
            var item = items.get(receipt);
            item.amounts.computeIfPresent(Component.OTHER,
                    (key, value) -> value.confirmed(null, "旧收据的人工金额不同，待核对"));
            item.reasons.add("RECEIPT_CONFLICT");
        }
        if (info.type() == CaseType.PRIVATE_CAR) {
            projectMileage(info, items, overrides, known, links);
        }
        projectHotelNights(info.type(), items, overrides);
        for (var item : items.values()) {
            if (item.start == null) {
                item.reasons.add("BUSINESS_DATE_MISSING");
            } else {
                item.reasons.remove("BUSINESS_DATE_MISSING");
            }
            if (item.amounts.entrySet().stream().anyMatch(value -> value.getValue().confirmedMinor() != null
                    && !ExpenseAmounts.allowed(info.type(), value.getKey()))) {
                item.reasons.add("UNMAPPED_CONFIRMATION");
            }
        }
        List<Item> immutable = items.values().stream().map(MutableItem::freeze).sorted(ITEM_ORDER).toList();
        List<Link> sortedLinks = links.stream().distinct()
                .sorted(Comparator.comparing(Link::itemId).thenComparing(Link::unitId).thenComparing(link -> link.role().name()))
                .toList();
        List<Claim> sortedClaims = claims.values().stream().sorted(Comparator.comparing(Claim::chargeKey)).toList();
        return new Projection(immutable, sortedLinks, sortedClaims, proposal.duplicates());
    }

    private static void projectHotelNights(CaseType type, Map<String, MutableItem> items, List<OverrideView> overrides) {
        var manual = new HashSet<String>();
        overrides.stream().filter(value -> "nights".equals(value.fieldKey()))
                .map(OverrideView::targetId).forEach(manual::add);
        for (var item : items.values()) {
            item.reasons.remove(HOTEL_NIGHTS_NEEDS_REVIEW);
            if (type == CaseType.TRAVEL && ("HOTEL".equals(item.subtype) || item.amounts.containsKey(Component.HOTEL))
                    && !item.fields.containsKey("nights") && !manual.contains(item.id)) {
                item.reasons.add(HOTEL_NIGHTS_NEEDS_REVIEW);
            }
        }
    }

    private static ReceiptProjection retainReceiptIdentities(CaseView before, List<Evidence> eligible,
                                                            List<OverrideView> overrides, ExpenseMatcher.MatchResult proposal) {
        var oldRows = new HashMap<String, Item>();
        before.items().stream().filter(item -> "RECEIPT".equals(item.subtype())).forEach(item -> oldRows.put(item.id(), item));
        var manualAmounts = new HashMap<String, OverrideView>();
        overrides.stream().filter(value -> (AMOUNT + Component.OTHER.name()).equals(value.fieldKey()))
                .forEach(value -> manualAmounts.put(value.targetId(), value));
        var activeHashes = new HashSet<String>();
        var byReceipt = new LinkedHashMap<String, List<Evidence>>();
        for (var evidence : eligible) {
            activeHashes.add(evidence.sha256());
            if (isReceipt(evidence) && evidence.receiptKey() != null) {
                String itemId = ExpenseMatcher.stableId(before.info().id(), "receipt:" + evidence.receiptKey());
                byReceipt.computeIfAbsent(itemId, unused -> new ArrayList<>()).add(evidence);
            }
        }
        var selections = new HashMap<String, ReceiptSelection>();
        var mergedIds = new HashSet<String>();
        var conflicts = new HashSet<String>();
        for (var entry : byReceipt.entrySet()) {
            var selection = receiptSelection(before.info().id(), entry.getKey(), entry.getValue(), oldRows,
                    manualAmounts, activeHashes);
            if (selection != null) {
                selections.put(entry.getKey(), selection);
                mergedIds.addAll(selection.legacyIds());
                if (selection.conflict()) conflicts.add(selection.itemId());
            }
        }
        return new ReceiptProjection(applyReceiptSelections(proposal, selections, oldRows), mergedIds, conflicts);
    }

    private static boolean isReceipt(Evidence evidence) {
        return evidence.page().kind() == PageResult.Kind.RECEIPT || evidence.page().kind() == PageResult.Kind.APPLE_RECEIPT;
    }

    private static Set<String> receiptDuplicates(Set<String> duplicates, List<Source> sources, List<Link> links,
                                                 Map<String, Item> items) {
        if (duplicates.isEmpty()) return duplicates;
        var linked = new HashSet<String>();
        for (var link : links) {
            var item = items.get(link.itemId());
            if (item != null && "RECEIPT".equals(item.subtype())) linked.add(link.unitId());
        }
        var groups = new HashMap<String, List<Evidence>>();
        for (Source source : sources) {
            if (source.missing()) continue;
            for (Evidence evidence : source.units()) {
                if (isReceipt(evidence) && evidence.receiptKey() != null) {
                    groups.computeIfAbsent(evidence.receiptKey(), unused -> new ArrayList<>()).add(evidence);
                }
            }
        }
        var adjusted = new HashSet<>(duplicates);
        for (var copies : groups.values()) {
            if (copies.stream().anyMatch(unit -> duplicates.contains(unit.unitId()))
                    && copies.stream().anyMatch(unit -> linked.contains(unit.unitId()))) {
                for (var unit : copies) {
                    if (linked.contains(unit.unitId())) adjusted.remove(unit.unitId());
                    else adjusted.add(unit.unitId());
                }
            }
        }
        return adjusted;
    }

    private static ReceiptSelection receiptSelection(String caseId, String itemId, List<Evidence> copies,
                                                     Map<String, Item> oldRows, Map<String, OverrideView> manual,
                                                     Set<String> activeHashes) {
        var legacy = new LinkedHashMap<String, Evidence>();
        for (var source : copies) {
            String id = ExpenseMatcher.stableId(caseId, "receipt:" + source.unitId());
            if (oldRows.containsKey(id) || manual.containsKey(id)) legacy.put(id, source);
        }
        if (legacy.isEmpty()) return null;
        var valid = new LinkedHashMap<String, OverrideView>();
        for (String id : legacy.keySet()) {
            var value = manual.get(id);
            if (value != null && activeHashes.containsAll(value.sourceHashes())) valid.put(id, value);
        }
        var current = manual.get(itemId);
        if (current != null && activeHashes.containsAll(current.sourceHashes())) valid.put(itemId, current);
        String chosen = oldRows.containsKey(itemId) ? itemId : legacy.keySet().iterator().next();
        if (!valid.isEmpty() && !valid.containsKey(chosen)) {
            chosen = valid.keySet().stream().filter(oldRows::containsKey).findFirst().orElse(valid.keySet().iterator().next());
        }
        var newest = valid.get(chosen);
        boolean distinctAmounts = valid.values().stream()
                .map(value -> DraftStore.decode(value.value(), ConfirmedAmount.class).minor()).distinct().count() > 1;
        boolean resolvedByNewerOverride = distinctAmounts && newest != null
                && copies.stream().allMatch(source -> newest.sourceHashes().contains(source.sha256()))
                && valid.values().stream().allMatch(value -> value == newest || value.updatedRevision() < newest.updatedRevision());
        var source = legacy.get(chosen);
        return new ReceiptSelection(chosen, source == null ? null : source.unitId(), copies, legacy.keySet(),
                distinctAmounts, distinctAmounts && !resolvedByNewerOverride);
    }

    private static ExpenseMatcher.MatchResult applyReceiptSelections(ExpenseMatcher.MatchResult proposal,
                                                                     Map<String, ReceiptSelection> selections,
                                                                     Map<String, Item> oldRows) {
        if (selections.isEmpty()) return proposal;
        var items = proposal.items().stream().map(item -> {
            var selection = selections.get(item.id());
            if (selection == null) return item;
            var previous = oldRows.get(selection.itemId());
            return new Item(selection.itemId(), item.subtype(), item.startDate(), item.endDate(), item.description(),
                    previous == null ? item.stableOrder() : previous.stableOrder(),
                    item.fields(), item.amounts(), item.reviewReasons());
        }).toList();
        var duplicates = new HashSet<>(proposal.duplicates());
        var links = new ArrayList<Link>();
        var addedMergedGroups = new HashSet<String>();
        for (var link : proposal.links()) {
            var selection = selections.get(link.itemId());
            if (selection == null) {
                links.add(link);
            } else if (selection.retainCopies()) {
                if (addedMergedGroups.add(selection.itemId())) {
                    Role role = selection.conflict() ? Role.PENDING : Role.SUPPORT;
                    String basis = selection.conflict() ? "旧收据的人工金额不同，待核对" : "人工核定的原始收据";
                    for (var source : selection.copies()) {
                        duplicates.remove(source.unitId());
                        links.add(new Link(selection.itemId(), source.unitId(), role, basis));
                    }
                }
            } else if (link.role() == Role.SUPPORT && selection.unitId() != null && duplicates.remove(selection.unitId())) {
                duplicates.add(link.unitId());
                links.add(new Link(selection.itemId(), selection.unitId(), link.role(), link.basis()));
            } else {
                links.add(new Link(selection.itemId(), link.unitId(), link.role(), link.basis()));
            }
        }
        return new ExpenseMatcher.MatchResult(items, links, proposal.claims(), duplicates);
    }

    private static void projectMileage(CaseInfo info, Map<String, MutableItem> items, List<OverrideView> overrides,
                                       Map<String, Evidence> known, List<Link> links) {
        var selected = new HashSet<String>();
        for (var link : links) {
            var evidence = known.get(link.unitId());
            if (evidence != null && Set.of("3", "4", "5").contains(evidence.page().parserVersion())
                    && evidence.page().value("selectedRouteDistanceKm") != null
                    && link.itemId().equals(ExpenseMatcher.stableId(info.id(), "mileage:" + evidence.unitId()))) {
                selected.add(link.itemId());
            }
        }
        var manual = new HashSet<String>();
        overrides.stream().filter(value -> "distanceKm".equals(value.fieldKey()))
                .map(OverrideView::targetId).forEach(manual::add);
        items.values().stream().filter(item -> "MILEAGE".equals(item.subtype))
                .forEach(item -> projectMileageRow(item, manual.contains(item.id), selected.contains(item.id)));
    }

    private static void projectMileageRow(MutableItem item, boolean manual, boolean selected) {
        if (!selected) {
            item.field("rawDistanceKm", null);
        }
        String raw = manual ? item.fields.get("distanceKm") : selected ? item.fields.get("rawDistanceKm") : null;
        item.field("distanceKm", null);
        item.amounts.remove(Component.FUEL);
        item.reasons.remove(MILEAGE_NEEDS_REVIEW);
        item.reasons.remove(MILEAGE_OUT_OF_RANGE);
        BigDecimal confirmed;
        try {
            confirmed = ExpenseMatcher.mileageDistance(raw, manual ? null : item.fields.get("direction"));
        } catch (NumberFormatException | ArithmeticException invalid) {
            item.reasons.add(MILEAGE_OUT_OF_RANGE);
            return;
        }
        var fuel = ExpenseAmounts.mileageFuel(confirmed);
        if (fuel.outOfRange()) {
            item.reasons.add(MILEAGE_OUT_OF_RANGE);
        } else if (item.reasons.contains("SOURCE_REVIEW_REQUIRED") || fuel.amount() == null) {
            if (!manual || raw != null) {
                item.reasons.add(MILEAGE_NEEDS_REVIEW);
            }
        } else {
            item.field("distanceKm", confirmed.signum() == 0 ? "0" : confirmed.toPlainString());
            var amount = fuel.amount();
            String basis = (manual ? "人工确认里程 " : "选中路线 ") + amount.basis();
            item.amounts.put(Component.FUEL, amount.confirmed(amount.confirmedMinor(), basis));
        }
    }

    private static Map<String, Set<String>> priorTripPairs(CaseView before) {
        var units = new HashMap<String, Evidence>();
        before.sources().stream().flatMap(source -> source.units().stream())
                .forEach(unit -> units.put(unit.unitId(), unit));
        Map<String, Set<String>> result = new HashMap<>();
        for (Item item : before.items()) {
            if (!Set.of("DRIVER", "RIDE").contains(item.subtype())) {
                continue;
            }
            var links = before.links().stream().filter(link -> link.itemId().equals(item.id())).toList();
            var trips = links.stream().filter(link -> link.role() == Role.SUPPORT && units.containsKey(link.unitId())
                    && units.get(link.unitId()).page().kind() == PageResult.Kind.TRIP_STATEMENT)
                    .map(Link::unitId).collect(java.util.stream.Collectors.toUnmodifiableSet());
            if (trips.size() == 1) {
                links.stream().filter(link -> link.role() == Role.CHARGE).forEach(link -> result.put(link.unitId(), trips));
            }
        }
        return result;
    }

    private static void preserveTypeRemapping(CaseInfo info, CaseView before, Map<String, MutableItem> items,
                                              List<OverrideView> overrides) {
        for (Item preceding : before.items()) {
            var item = items.get(preceding.id());
            if (item == null || info.type() == before.info().type()
                    && !preceding.reviewReasons().contains("UNMAPPED_CONFIRMATION")) {
                continue;
            }
            boolean hasNewProjection = item.amounts.entrySet().stream().anyMatch(entry ->
                    entry.getValue().confirmedMinor() != null && ExpenseAmounts.allowed(info.type(), entry.getKey()));
            if (!hasNewProjection || preceding.reviewReasons().contains("UNMAPPED_CONFIRMATION")) {
                for (var entry : preceding.amounts().entrySet()) {
                    if (ExpenseAmounts.derivedMileageFuel(item.subtype, entry.getKey())) {
                        item.amounts.remove(Component.FUEL);
                        continue;
                    }
                    if (!ExpenseAmounts.allowed(info.type(), entry.getKey()) && entry.getValue().confirmedMinor() != null) {
                        boolean overridden = overrides.stream().anyMatch(override -> override.targetId().equals(item.id)
                                && override.fieldKey().equals(AMOUNT + entry.getKey().name()));
                        if (!overridden) {
                            item.amounts.putIfAbsent(entry.getKey(), entry.getValue());
                        }
                    }
                }
            }
        }
    }

    private static void applyLink(CaseInfo info, MutableItem item, OverrideView override, Map<String, Evidence> known,
                                  Map<String, MutableItem> items, List<Link> links, Map<String, Claim> claims,
                                  Set<AmountOwner> changedAmounts) {
        String unitId = override.fieldKey().substring(LINK.length());
        var source = known.get(unitId);
        ManualLink value = DraftStore.decode(override.value(), ManualLink.class);
        if (ExpenseAmounts.derivedMileageFuel(item.subtype, value.component())
                && (value.role() == Role.CHARGE || value.role() == Role.REPLACEMENT)) {
            if (source != null && links.stream().noneMatch(link -> link.itemId().equals(item.id)
                    && link.unitId().equals(unitId))) {
                links.add(new Link(item.id, unitId, Role.SUPPORT, "历史油费凭证"));
            }
            return;
        }
        if (source == null) {
            item.reasons.add("SOURCE_REVIEW_REQUIRED");
            return;
        }
        boolean heldHere = links.stream().anyMatch(link -> link.itemId().equals(item.id) && link.unitId().equals(unitId)
                && (link.role() == Role.CHARGE || link.role() == Role.REPLACEMENT));
        links.removeIf(link -> link.itemId().equals(item.id) && link.unitId().equals(unitId));
        if (value.role() != null) {
            links.add(new Link(item.id, unitId, value.role(), "人工关联"));
        }
        String invoice = source.invoiceKey();
        if (invoice == null) {
            return;
        }
        Claim owner = claims.get(invoice);
        if (heldHere && owner != null && item.id.equals(owner.itemId())) {
            claims.remove(invoice);
            changedAmounts.add(new AmountOwner(item.id, owner.component()));
        }
        if (value.role() != Role.CHARGE && value.role() != Role.REPLACEMENT) {
            return;
        }
        if (!ExpenseAmounts.allowed(info.type(), value.component())) {
            item.reasons.add("UNMAPPED_CONFIRMATION");
            return;
        }
        if (owner != null && !item.id.equals(owner.itemId())) {
            if (!value.transfer()) {
                item.reasons.add("CHARGE_CONFLICT");
                links.removeIf(link -> link.itemId().equals(item.id) && link.unitId().equals(unitId));
                return;
            }
            changedAmounts.add(new AmountOwner(owner.itemId(), owner.component()));
            links.removeIf(link -> link.itemId().equals(owner.itemId()) && (link.role() == Role.CHARGE || link.role() == Role.REPLACEMENT)
                    && known.containsKey(link.unitId()) && invoice.equals(known.get(link.unitId()).invoiceKey()));
        }
        assignManualCharge(item, value, source, known, links, claims, changedAmounts);
    }

    private static void assignManualCharge(MutableItem item, ManualLink value, Evidence source,
                                           Map<String, Evidence> known, List<Link> links, Map<String, Claim> claims,
                                           Set<AmountOwner> changedAmounts) {
        Long minor = null;
        if (value.role() == Role.CHARGE) {
            boolean conflict = ExpenseMatcher.invoiceAmountConflict(source.invoiceKey(), known.values());
            minor = conflict ? null : source.chargeMinor();
            if (conflict) item.reasons.add("INVOICE_AMOUNT_CONFLICT");
            if (minor != null && source.usdMinor() != null) {
                links.set(links.size() - 1, new Link(item.id, source.unitId(), Role.CHARGE, source.usdBasis()));
            }
        }
        item.amounts.putIfAbsent(value.component(), new Amount(null, null, null, "人工关联"));
        claims.put(source.invoiceKey(), new Claim(source.invoiceKey(), item.id, value.component(), minor));
        changedAmounts.add(new AmountOwner(item.id, value.component()));
    }

    private static void projectChangedAmounts(Map<String, MutableItem> items, Map<String, Claim> claims,
                                              Set<AmountOwner> changedAmounts) {
        for (AmountOwner changed : changedAmounts) {
            var owner = items.get(changed.itemId());
            var numbers = claims.values().stream()
                    .filter(claim -> claim.itemId().equals(changed.itemId()) && claim.component() == changed.component())
                    .map(Claim::minor).toList();
            var sum = numbers.contains(null) ? null : numbers.stream().reduce(Math::addExact).orElse(null);
            var original = owner.amounts.getOrDefault(changed.component(), new Amount(null, null, null, "人工关联"));
            owner.amounts.put(changed.component(), original.confirmed(sum, "人工关联后的计费凭证"));
            if (sum == null) {
                owner.reasons.add("AMOUNT_NEEDS_REVIEW");
            } else {
                owner.reasons.remove("CNY_CONFIRMATION_REQUIRED");
                if (owner.amounts.values().stream().allMatch(amount -> amount.confirmedMinor() != null)) {
                    owner.reasons.remove("AMOUNT_NEEDS_REVIEW");
                }
            }
        }
    }

    static Set<String> hashes(String itemId, List<Link> links, Map<String, Evidence> evidence) {
        Set<String> hashes = new LinkedHashSet<>();
        for (var link : links) {
            var source = evidence.get(link.unitId());
            if (source != null && link.itemId().equals(itemId)) {
                hashes.add(source.sha256());
            }
        }
        return hashes;
    }

    private static void holdChangedSources(List<OverrideView> overrides, Map<String, Evidence> known,
                                           Map<String, MutableItem> items, List<Link> links, Map<String, Claim> claims,
                                           Set<AmountOwner> changedAmounts) {
        Set<String> needReview = new HashSet<>();
        overrides.stream().filter(value -> SOURCE_VERSION.equals(value.fieldKey()) && "PENDING".equals(value.action()))
                .forEach(value -> needReview.add(value.targetId()));
        Set<AmountOwner> held = new HashSet<>();
        Set<String> affectedKeys = new HashSet<>();
        for (var link : links) {
            var evidence = known.get(link.unitId());
            if (evidence == null || !needReview.contains(evidence.docId())) {
                continue;
            }
            var item = items.get(link.itemId());
            item.reasons.add("SOURCE_REVIEW_REQUIRED");
            var claim = claims.get(evidence.invoiceKey());
            if (claim != null && claim.itemId().equals(link.itemId())) {
                held.add(new AmountOwner(claim.itemId(), claim.component()));
            }
            if ("RECEIPT".equals(item.subtype) && evidence.usdMinor() != null && link.basis().equals(evidence.usdBasis())) {
                held.add(new AmountOwner(item.id, Component.OTHER));
            }
        }
        for (var claim : claims.values()) {
            if (held.contains(new AmountOwner(claim.itemId(), claim.component()))) {
                affectedKeys.add(claim.chargeKey());
            }
        }
        for (int index = 0; index < links.size(); index++) {
            var link = links.get(index);
            var evidence = known.get(link.unitId());
            if (evidence != null && affectedKeys.contains(evidence.invoiceKey())
                    && (link.role() == Role.CHARGE || link.role() == Role.REPLACEMENT)) {
                links.set(index, new Link(link.itemId(), link.unitId(), Role.PENDING, "凭证内容改变，请重新核对"));
            }
        }
        affectedKeys.forEach(claims::remove);
        changedAmounts.addAll(held);
    }

    static AttachmentSetting setting(List<AttachmentSetting> settings, String docId, int page) {
        return settings.stream().filter(row -> row.docId().equals(docId) && row.firstPage() <= page && row.lastPage() >= page)
                .min(Comparator.comparingInt(row -> row.lastPage() - row.firstPage())).orElse(null);
    }

    private static boolean excluded(List<AttachmentSetting> settings, String docId, int page) {
        var entry = setting(settings, docId, page);
        return entry != null && entry.exclusionReason() != null;
    }

    private static String defaultCaption(Item item) {
        if (item.startDate() == null) {
            return item.description();
        }
        var date = item.startDate().toString();
        if (item.endDate() != null && !item.endDate().equals(item.startDate())) {
            date += " 至 " + item.endDate();
        }
        return date + " " + item.description();
    }

    private static int roleOrder(Role role) {
        return switch (role) {
            case CHARGE, REPLACEMENT -> 0;
            case SUPPORT -> 1;
            case PENDING -> 2;
        };
    }

    record ConfirmedAmount(Long minor, String basis) {}
    record ManualLink(Role role, Component component, boolean transfer) {}
    record ManualItem(String unitId, String description, String startDate, String endDate) {}
    record Projection(List<Item> items, List<Link> links, List<Claim> claims, Set<String> duplicates) {}
    private record AmountReview(Map<Component, Amount> amounts, Set<String> reasons) {}
    private record ReceiptProjection(ExpenseMatcher.MatchResult match, Set<String> mergedIds, Set<String> conflicts) {}
    private record ReceiptSelection(String itemId, String unitId, List<Evidence> copies, Set<String> legacyIds,
                                    boolean retainCopies, boolean conflict) {}
    private record AmountOwner(String itemId, Component component) {}
    private record AttachmentEntry(AttachmentPage page, Item first, Long explicitOrder) {}

    private static final class MutableItem {
        private final String id;
        private final String subtype;
        private final long order;
        private LocalDate start;
        private LocalDate end;
        private String description;
        private final Map<String, String> fields;
        private final Map<Component, Amount> amounts;
        private final Set<String> reasons;

        MutableItem(Item item) {
            this.id = item.id();
            this.subtype = item.subtype();
            this.order = item.stableOrder();
            this.start = item.startDate();
            this.end = item.endDate();
            this.description = item.description();
            this.fields = new LinkedHashMap<>(item.fields());
            this.amounts = new LinkedHashMap<>(item.amounts());
            this.reasons = new LinkedHashSet<>(item.reviewReasons());
        }

        void field(String key, String value) {
            switch (key) {
                case "startDate" -> start = value == null ? null : LocalDate.parse(value);
                case "endDate" -> end = value == null ? null : LocalDate.parse(value);
                case "description" -> description = value == null ? "" : value;
                default -> {
                    if (value == null) {
                        fields.remove(key);
                    } else {
                        fields.put(key, value);
                    }
                }
            }
        }

        Item freeze() {
            return new Item(id, subtype, start, end, description, order, fields, amounts, List.copyOf(reasons));
        }
    }
}
