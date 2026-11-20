package expenseassistant.output;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import expenseassistant.draft.CaseView;
import expenseassistant.intake.TypeInference;
import expenseassistant.intake.TypeInference.TravelDirectory;
import expenseassistant.matching.ExpenseAmounts.Component;
import expenseassistant.matching.ExpenseMatcher.Evidence;
import expenseassistant.matching.ExpenseMatcher.Item;
import expenseassistant.output.ExportSnapshot.ExpenseRow;
import expenseassistant.recognition.PageResult;

import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.toCollection;
import static java.util.stream.Collectors.toMap;
import static java.util.stream.Collectors.toSet;

final class TravelSummaryRows {
    private static final Set<String> INTERCITY = Set.of("FLIGHT", "RAIL");
    private static final Set<String> LOCAL_TAXI = Set.of("RIDE", "DRIVER");
    private static final List<String> KNOWN_VEHICLES = List.of("火车", "飞机", "私车");
    private static final Pattern STATION_SUFFIX = Pattern.compile("(?<=[\\p{IsHan}]{2})[东西南北]?站$");
    private static final Pattern ROUTE_SEPARATOR = Pattern.compile("\\h*(?:->|[-→—–至])\\h*");
    private static final Pattern VEHICLE_SEPARATOR = Pattern.compile("[、,，;；/／+＋|\\r\\n]+");
    private static final Comparator<LocalDate> DATE_ORDER = Comparator.nullsLast(Comparator.naturalOrder());
    private static final Set<String> TRIP_FIELDS = Set.of("route", "origin", "destination", "vehicle", "startDate");
    private static final Pattern FLIGHT_FILENAME = ticketFilename("(?:机票款凭证|机票报销凭证|航空运输电子客票行程单)");
    private static final Pattern RAIL_FILENAME = ticketFilename("(?:火车票款凭证|火车票报销凭证|铁路电子客票|铁路电子发票)");

    private TravelSummaryRows() {}

    static List<ExpenseRow> summarize(CaseView view, List<ExpenseRow> rows) {
        if (rows.isEmpty()) return List.of();

        var directory = TypeInference.travelDirectory(view.info().folder().getFileName().toString()).orElse(null);
        var located = locate(view, rows, directory);
        var trips = located.stream().filter(LocatedRow::intercity)
                .map(row -> new Group(row, displayableFeePlace(row), row.start, row.end))
                .collect(toCollection(ArrayList::new));
        boolean withTicket = !trips.isEmpty();
        if (!withTicket) buildTicketlessTrips(located, trips, directory);
        var pair = withTicket ? roundTrip(trips, directory) : null;
        var unassigned = new ArrayList<Group>();
        var anchors = trips.stream().flatMap(trip -> trip.rows.stream()).map(row -> row.row.itemId()).collect(toSet());
        for (var fee : located) {
            if (anchors.contains(fee.row.itemId())) continue;
            var trip = matchFee(fee, trips, pair, directory);
            if (trip == null) {
                unassigned.add(new Group(fee, displayableFeePlace(fee), fee.start, fee.end));
            } else {
                trip.add(fee);
            }
        }
        trips.addAll(unassigned);
        var summaries = trips.stream().filter(trip -> !trip.rows.isEmpty())
                .sorted(Comparator.comparing((Group trip) -> trip.start, DATE_ORDER).thenComparingLong(Group::order))
                .map(Group::summarize).toList();
        summaries.stream().flatMap(row -> row.amounts().values().stream()).reduce(0L, Math::addExact);
        return summaries;
    }

    private static List<LocatedRow> locate(CaseView view, List<ExpenseRow> rows, TravelDirectory directory) {
        var order = view.items().stream().collect(toMap(Item::id, Item::stableOrder));
        var overrides = view.overrides().stream().collect(groupingBy(CaseView.OverrideView::targetId,
                toMap(CaseView.OverrideView::fieldKey, CaseView.OverrideView::action)));
        var units = view.sources().stream().filter(source -> !source.missing() && source.error() == null)
                .flatMap(source -> source.units().stream()).collect(toMap(Evidence::unitId, unit -> unit));
        var sources = new HashMap<String, List<Evidence>>();
        for (var link : view.links()) {
            var unit = units.get(link.unitId());
            if (unit != null && !view.duplicateUnitIds().contains(unit.unitId())) {
                sources.computeIfAbsent(link.itemId(), unused -> new ArrayList<>()).add(unit);
            }
        }
        var result = new ArrayList<LocatedRow>();
        for (int index = 0; index < rows.size(); index++) {
            var row = rows.get(index);
            result.add(locate(row, order.getOrDefault(row.itemId(), (long) index),
                    overrides.getOrDefault(row.itemId(), Map.of()),
                    sources.getOrDefault(row.itemId(), List.of()), directory));
        }
        return result;
    }

    private static LocatedRow locate(ExpenseRow row, long order, Map<String, String> manual,
                                     List<Evidence> sources, TravelDirectory directory) {
        boolean intercity = INTERCITY.contains(row.subtype());
        var pages = relevantPages(row, sources);
        var place = place(row, manual, sources, pages, directory);
        var start = date(row.startDate(), "startDate", manual, pages);
        var end = date(row.endDate(), "endDate", manual, pages);
        if (start.value != null && end.value != null && end.value.isBefore(start.value)) {
            if (!"SET".equals(manual.get("startDate"))) start = new Value<>(null, true);
            end = new Value<>(null, true);
        }
        return new LocatedRow(row, order, manual, place.value, place.blocked,
                start.value, end.value, start.blocked, end.blocked, intercity);
    }

    private static List<PageResult> relevantPages(ExpenseRow row, List<Evidence> sources) {
        if ("TOLL".equals(row.subtype())) {
            // A toll row may cover a summary and several charge pages with different dates.
            return List.of();
        }
        return sources.stream().flatMap(source -> source.pages().stream())
                .filter(page -> row.subtype().equals(page.value("subtype"))
                        || LOCAL_TAXI.contains(row.subtype()) && page.kind() == PageResult.Kind.TRIP_STATEMENT)
                .toList();
    }

    private static Value<Place> place(ExpenseRow row, Map<String, String> manual, List<Evidence> sources,
                                      List<PageResult> pages, TravelDirectory directory) {
        boolean intercity = INTERCITY.contains(row.subtype());
        String routeAction = manual.get("route");
        if ("CLEAR".equals(routeAction)) return new Value<>(null, true);
        if ("SET".equals(routeAction)) {
            var declared = manualPlace(row.fields().get("route"));
            return new Value<>(declared, declared == null || intercity && !declared.complete(directory));
        }
        var placePages = LOCAL_TAXI.contains(row.subtype()) ? List.<PageResult>of() : pages;
        var origin = field("origin", row, manual, placePages, directory);
        var destination = field("destination", row, manual, placePages, directory);
        var place = new Place(origin.value, destination.value);
        if (origin.blocked || destination.blocked) return new Value<>(null, true);
        if (place.complete(directory)) return new Value<>(place, false);
        if (!intercity) return new Value<>(place.destination == null ? null : place, false);
        if (place.origin != null && place.destination != null) return new Value<>(null, true);

        var named = sources.stream().filter(source -> row.subtype().equals(source.subtype()))
                .map(source -> filenamePlace(source.relativePath(), row.subtype())).filter(Objects::nonNull).toList();
        if (named.isEmpty()) return new Value<>(null, false);
        var first = named.getFirst();
        if (!first.complete(directory) || named.stream().anyMatch(candidate -> !first.sameDirection(candidate, directory))
                || !first.compatibleWith(place, directory)) {
            return new Value<>(null, true);
        }
        return new Value<>(new Place(Objects.requireNonNullElse(place.origin, first.origin),
                Objects.requireNonNullElse(place.destination, first.destination)), false);
    }

    private static Place manualPlace(String raw) {
        String route = clean(raw);
        if (route == null || route.contains("\n") || route.contains("\r")) return null;
        String[] parts = ROUTE_SEPARATOR.split(route, -1);
        if (parts.length == 1) return new Place(null, route);
        if (parts.length != 2 || clean(parts[0]) == null || clean(parts[1]) == null) return null;
        return new Place(clean(parts[0]), clean(parts[1]));
    }

    private static Value<String> field(String key, ExpenseRow row, Map<String, String> manual,
                                       List<PageResult> pages, TravelDirectory directory) {
        String action = manual.get(key);
        if ("CLEAR".equals(action)) return new Value<>(null, true);
        if ("SET".equals(action)) return new Value<>(clean(row.fields().get(key)), false);
        var values = candidates(key, pages);
        if (!(LOCAL_TAXI.contains(row.subtype())) && clean(row.fields().get(key)) != null) {
            values.addFirst(clean(row.fields().get(key)));
        }
        boolean conflict = values.stream().map(value -> placeKey(value, directory)).distinct().limit(2).count() > 1;
        return new Value<>(conflict || values.isEmpty() ? null : values.getFirst(), conflict);
    }

    private static Value<LocalDate> date(LocalDate projected, String key, Map<String, String> manual, List<PageResult> pages) {
        if ("CLEAR".equals(manual.get(key))) return new Value<>(null, true);
        if ("SET".equals(manual.get(key))) return new Value<>(projected, false);
        var values = candidates(key, pages);
        if (projected != null) values.addFirst(projected.toString());
        boolean conflict = values.stream().distinct().limit(2).count() > 1;
        return new Value<>(conflict || values.isEmpty() ? null : LocalDate.parse(values.getFirst()), conflict);
    }

    private static ArrayList<String> candidates(String key, List<PageResult> pages) {
        var values = new ArrayList<String>();
        for (var page : pages) {
            page.fields().stream().filter(field -> key.equals(field.key())
                            && (field.method() == PageResult.Method.PDF_TEXT || field.method() == PageResult.Method.OCR))
                    .map(field -> clean(field.normalized())).filter(Objects::nonNull).forEach(values::add);
        }
        return values;
    }

    private static Pattern ticketFilename(String voucher) {
        String city = "([\\p{IsHan}·]{2,24})";
        return Pattern.compile("^(?:【[^】\\r\\n]{1,20}】|\\[[^\\]\\r\\n]{1,20}\\])?\\h*" + city
                + "\\h*[-—–→]\\h*" + city
                + "(?:\\h+订单\\h*[A-Z0-9]+\\h*[-—–]\\h*|\\h+|\\h*[-—–]\\h*)"
                + voucher + "(?:\\h+报销凭证)?\\.pdf$", Pattern.CASE_INSENSITIVE);
    }

    private static Place filenamePlace(String path, String subtype) {
        String name = clean(path.substring(Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\')) + 1));
        if (name == null) return null;
        var match = ("FLIGHT".equals(subtype) ? FLIGHT_FILENAME : RAIL_FILENAME).matcher(name);
        return match.matches() ? new Place(match.group(1), match.group(2)) : null;
    }

    private static void buildTicketlessTrips(List<LocatedRow> located, List<Group> trips, TravelDirectory directory) {
        for (var row : located) {
            if (row.manual.entrySet().stream().noneMatch(entry ->
                    TRIP_FIELDS.contains(entry.getKey()) && "SET".equals(entry.getValue()))) continue;
            var matches = trips.stream().filter(trip -> trip.sameManualTrip(row, directory)).limit(2).toList();
            if (matches.size() == 1) {
                matches.getFirst().joinedAnchor(row);
            } else {
                var trip = new Group(row, displayableFeePlace(row), row.manualDeparture(), row.end);
                if (row.dates().known()) trip.matchingRange = row.dates();
                trips.add(trip);
            }
        }
        if (trips.isEmpty() && directory != null
                && located.stream().anyMatch(row -> !row.placeBlocked && !row.startBlocked && !row.endBlocked
                        && directoryCompatible(row, directory))) {
            trips.add(new Group(null, new Place(null, directory.destination()), null, null));
        }
        if (trips.size() != 1 || directory == null) return;
        var trip = trips.getFirst();
        if (trip.place == null && !trip.placeBlocked()) {
            trip.place = new Place(null, directory.destination());
        }
        var window = DateRange.directory(directory);
        if (!trip.startBlocked() && !trip.endBlocked() && trip.compatibleDestination(directory)
                && located.stream().filter(row -> !row.startBlocked && !row.endBlocked)
                        .allMatch(row -> directoryCompatible(row, directory))) {
            if (trip.start == null) trip.start = window.start;
            trip.matchingRange = window;
        }
    }

    private static boolean directoryCompatible(LocatedRow row, TravelDirectory directory) {
        return (row.place == null || row.place.destination == null
                        || samePlace(row.place.destination, directory.destination(), directory))
                && DateRange.directory(directory).compatible(row.dates());
    }

    private static RoundTrip roundTrip(List<Group> trips, TravelDirectory directory) {
        if (trips.size() != 2) return null;
        var go = trips.getFirst();
        var back = trips.getLast();
        if (go.place == null || back.place == null || !go.place.sameDirection(back.place.reverse(), directory)) {
            return null;
        }
        boolean directoryChooses = directory != null
                && go.compatibleDestination(directory) != back.compatibleDestination(directory);
        if (directoryChooses ? back.compatibleDestination(directory)
                : DATE_ORDER.compare(back.start, go.start) < 0) {
            go = trips.getLast();
            back = trips.getFirst();
        }
        if (directoryChooses) fillRoundTripDates(go, back, directory);
        if (go.start == null || back.start == null || !directoryChooses && go.start.equals(back.start)
                || go.endBlocked() || back.endBlocked() || back.start.isBefore(go.dates().end)) return null;
        return new RoundTrip(go, back);
    }

    private static void fillRoundTripDates(Group go, Group back, TravelDirectory directory) {
        // Keep any contradictory or explicitly cleared departure unresolved.
        var range = DateRange.directory(directory);
        if (go.dateConflict() || back.dateConflict() || !range.compatible(go.dates()) || !range.compatible(back.dates())) {
            return;
        }
        LocalDate start = go.start == null && !go.startBlocked() ? range.start : go.start;
        LocalDate finish = back.start == null && !back.startBlocked() ? range.end : back.start;
        if (start != null && go.end != null && go.end.isBefore(start)
                || finish != null && back.end != null && back.end.isBefore(finish)
                || finish != null && (go.end != null && finish.isBefore(go.end)
                        || start != null && finish.isBefore(start))) return;
        go.start = start;
        back.start = finish;
    }

    private static Group matchFee(LocatedRow fee, List<Group> trips, RoundTrip pair, TravelDirectory directory) {
        if (fee.placeBlocked || fee.startBlocked) return null;
        var eligible = trips.stream().filter(trip -> trip.acceptsPlace(fee.place, directory)).toList();
        boolean onlyTrip = trips.size() == 1;
        var manual = eligible.stream().filter(trip -> fee.matchesManually(trip.feeRange(directory, onlyTrip)))
                .limit(2).toList();
        if (manual.size() == 1) return manual.getFirst();
        if (fee.hotel() && fee.start != null) {
            var arrival = hotelArrival(fee, trips, eligible, directory);
            if (arrival != null) return arrival;
        }
        if (fee.endBlocked) return null;
        var dated = eligible.stream().filter(trip -> trip.feeRange(directory, onlyTrip).contains(fee.dates()))
                .limit(2).toList();
        if (dated.size() == 1) return dated.getFirst();
        if (pair != null && eligible.contains(pair.go) && pair.assignToDeparture(fee)) return pair.go;
        if (dated.isEmpty() && eligible.size() == 1 && !fee.dates().known()
                && (fee.place != null || trips.size() == 1 && eligible.getFirst().hasTripBasis())) {
            return eligible.getFirst();
        }
        return null;
    }

    private static Group hotelArrival(LocatedRow fee, List<Group> trips, List<Group> eligible, TravelDirectory directory) {
        var checkIn = DateRange.of(fee.start, fee.start);
        var arrivals = eligible.stream().filter(trip -> trip.anchor != null && trip.anchor.intercity)
                .filter(trip -> fee.start.equals(trip.end)).toList();
        var matches = arrivals.isEmpty() ? eligible.stream().filter(trip -> trip.anchor != null && trip.anchor.intercity)
                .filter(trip -> trip.feeRange(directory, trips.size() == 1).contains(checkIn)).toList() : arrivals;
        if (matches.size() != 1) return null;
        var arrival = matches.getFirst();
        if (fee.place == null && trips.size() > 1 && (fee.endBlocked || trips.stream().anyMatch(trip -> trip != arrival
                && trip.dates().known() && trip.dates().overlaps(fee.dates()) && !Objects.equals(trip.start, fee.end)))) {
            return null;
        }
        return arrival;
    }

    private static Place displayableFeePlace(LocatedRow fee) {
        return fee.placeBlocked ? null : fee.place;
    }

    private static boolean samePlace(String a, String b, TravelDirectory directory) {
        return a != null && b != null && placeKey(a, directory).equals(placeKey(b, directory));
    }

    private static String placeKey(String place, TravelDirectory directory) {
        String key = STATION_SUFFIX.matcher(place).replaceFirst("").replaceFirst("(?<=[\\p{IsHan}]{2})市$", "");
        if (directory != null && key.endsWith("机场") && key.startsWith(directory.destination())) {
            return placeKey(directory.destination(), null);
        }
        return key;
    }

    private static String clean(String value) {
        if (value == null) return null;
        String result = Normalizer.normalize(value, Normalizer.Form.NFKC).strip();
        return result.isEmpty() ? null : result;
    }

    private static String vehicles(List<String> vehicles) {
        var tools = vehicles.stream().filter(Objects::nonNull)
                .flatMap(VEHICLE_SEPARATOR::splitAsStream).map(TravelSummaryRows::clean)
                .filter(Objects::nonNull).distinct()
                .sorted(Comparator.<String>comparingInt(tool -> {
                    int order = KNOWN_VEHICLES.indexOf(tool);
                    return order < 0 ? KNOWN_VEHICLES.size() : order;
                }).thenComparing(Comparator.naturalOrder())).toList();
        return String.join("、", tools);
    }

    private static BigDecimal nights(List<LocatedRow> rows) {
        var stays = rows.stream().filter(row -> row.hotel() || row.row.fields().containsKey("nights"))
                .sorted(Comparator.comparing(LocatedRow::start, DATE_ORDER)).toList();
        if (stays.isEmpty()) return null;
        if (stays.size() == 1) return nightQuantity(stays.getFirst().row);
        BigDecimal total = BigDecimal.ZERO;
        LocalDate previousCheckout = null;
        for (var stay : stays) {
            var quantity = nightQuantity(stay.row);
            var checkIn = stay.start;
            var checkOut = stay.end;
            if (quantity == null || checkIn == null || checkOut == null || !checkIn.isBefore(checkOut)
                    || previousCheckout != null && checkIn.isBefore(previousCheckout)
                    || quantity.compareTo(BigDecimal.valueOf(ChronoUnit.DAYS.between(checkIn, checkOut))) > 0) {
                return null;
            }
            total = total.add(quantity);
            previousCheckout = checkOut;
        }
        return total;
    }

    private static BigDecimal nightQuantity(ExpenseRow row) {
        String value = row.fields().get("nights");
        if (value == null) return null;
        try {
            BigDecimal quantity = new BigDecimal(value);
            return quantity.signum() < 0 ? null : quantity;
        } catch (NumberFormatException invalid) {
            return null;
        }
    }

    private record Value<T>(T value, boolean blocked) {}

    private record LocatedRow(ExpenseRow row, long order, Map<String, String> manual, Place place,
                              boolean placeBlocked, LocalDate start, LocalDate end, boolean startBlocked,
                              boolean endBlocked, boolean intercity) {
        DateRange dates() { return DateRange.of(start, end); }
        boolean hotel() { return "HOTEL".equals(row.subtype()) || row.amounts().containsKey(Component.HOTEL.name()); }
        boolean manualTripField(String key) { return "SET".equals(manual.get(key)); }
        LocalDate manualDeparture() { return manualTripField("startDate") ? start : null; }

        boolean matchesManually(DateRange range) {
            if (manualTripField("startDate")) return range.contains(DateRange.of(start, start));
            boolean confirmedPlace = place != null && (manualTripField("route") || manualTripField("origin")
                    || manualTripField("destination"));
            return confirmedPlace && (!range.known() || !dates().known() || range.overlaps(dates()));
        }
    }

    private record Place(String origin, String destination) {
        Place reverse() { return new Place(destination, origin); }

        boolean complete(TravelDirectory directory) {
            return origin != null && destination != null && !samePlace(origin, destination, directory);
        }

        boolean sameDirection(Place other, TravelDirectory directory) {
            return samePlace(origin, other.origin, directory) && samePlace(destination, other.destination, directory);
        }

        boolean compatibleWith(Place partial, TravelDirectory directory) {
            return (partial.origin == null || samePlace(origin, partial.origin, directory))
                    && (partial.destination == null || samePlace(destination, partial.destination, directory));
        }

        String display() {
            return origin == null ? destination : origin + "-" + destination;
        }
    }

    private record DateRange(LocalDate start, LocalDate end) {
        static DateRange of(LocalDate start, LocalDate end) {
            return new DateRange(start == null ? end : start, end == null ? start : end);
        }

        static DateRange directory(TravelDirectory directory) {
            return new DateRange(directory.startDate(), directory.endDate());
        }

        boolean known() { return start != null; }

        boolean contains(DateRange other) {
            return known() && other.known() && !other.start.isBefore(start) && !other.end.isAfter(end);
        }

        boolean compatible(DateRange other) {
            return !other.known() || contains(other);
        }

        boolean overlaps(DateRange other) {
            return !other.start.isAfter(end) && !other.end.isBefore(start);
        }
    }

    private record RoundTrip(Group go, Group back) {
        boolean assignToDeparture(LocatedRow fee) {
            var wholeTrip = new DateRange(go.start, back.dates().end);
            if (fee.hotel()) {
                if (!fee.dates().known()) return !fee.manualTripField("route");
                // Stay dates run from arrival through checkout, which can be the return day.
                var stay = new DateRange(go.dates().end, back.start);
                if (!fee.manualTripField("startDate") && stay.contains(fee.dates())
                        && fee.start != null && fee.start.isBefore(back.start)) return true;
            }
            return !go.start.equals(back.start) && wholeTrip.equals(fee.dates())
                    && !fee.manualTripField("startDate") && !fee.manualTripField("route");
        }
    }

    private static final class Group {
        private final LocatedRow anchor;
        private final List<LocatedRow> rows = new ArrayList<>();
        private Place place;
        private LocalDate start;
        private LocalDate end;
        private DateRange matchingRange;

        private Group(LocatedRow anchor, Place place, LocalDate start, LocalDate end) {
            this.anchor = anchor;
            this.place = place;
            this.start = start;
            this.end = end;
            if (anchor != null) add(anchor);
        }

        private void add(LocatedRow row) { rows.add(row); }

        private void joinedAnchor(LocatedRow row) {
            add(row);
            if (place == null) place = row.place;
            if (start == null) start = row.manualDeparture();
        }

        private long order() { return anchor == null ? rows.getFirst().order : anchor.order; }
        private boolean placeBlocked() { return anchor != null && anchor.placeBlocked; }
        private boolean startBlocked() { return anchor != null && anchor.startBlocked; }
        private boolean endBlocked() { return anchor != null && anchor.endBlocked; }
        private boolean dateConflict() {
            return startBlocked() && !"CLEAR".equals(anchor.manual.get("startDate"))
                    || endBlocked() && !"CLEAR".equals(anchor.manual.get("endDate"));
        }
        private boolean hasTripBasis() { return place != null || dates().known(); }
        private DateRange dates() { return DateRange.of(start, end); }

        private boolean sameManualTrip(LocatedRow row, TravelDirectory directory) {
            if (placeBlocked() || row.placeBlocked || startBlocked() || row.startBlocked || endBlocked() || row.endBlocked) {
                return false;
            }
            boolean sameRoute = place != null && row.place != null
                    && (place.sameDirection(row.place, directory)
                        || place.origin == null && row.place.origin == null
                            && samePlace(place.destination, row.place.destination, directory));
            boolean sameDate = start != null && start.equals(row.manualDeparture());
            return (sameRoute || sameDate)
                    && (sameRoute || place == null || row.place == null)
                    && (sameDate || start == null || row.manualDeparture() == null)
                    && (matchingRange == null || !row.dates().known() || matchingRange.overlaps(row.dates()));
        }

        private boolean compatibleDestination(TravelDirectory directory) {
            return place != null && samePlace(place.destination, directory.destination(), directory);
        }

        private DateRange feeRange(TravelDirectory directory, boolean onlyTrip) {
            if (matchingRange != null) return matchingRange;
            var dates = dates();
            if (onlyTrip && directory != null && compatibleDestination(directory)
                    && !startBlocked() && !endBlocked() && DateRange.directory(directory).compatible(dates)) {
                return DateRange.directory(directory);
            }
            return dates;
        }

        private boolean acceptsPlace(Place feePlace, TravelDirectory directory) {
            return feePlace == null || place != null && place.compatibleWith(feePlace, directory);
        }

        private ExpenseRow summarize() {
            var fields = new LinkedHashMap<String, String>();
            String route = place == null ? null : place.display();
            if (route != null) fields.put("route", route);
            String tools = vehicles(rows.stream().filter(row -> row.intercity
                            || row.row.amounts().containsKey(Component.TRANSPORT.name()) && !LOCAL_TAXI.contains(row.row.subtype())
                            || row.manualTripField("vehicle") && anchor != null && !anchor.intercity)
                    .map(row -> row.row.fields().get("vehicle")).toList());
            if (!tools.isEmpty()) fields.put("vehicle", tools);
            var quantity = nights(rows);
            if (quantity != null) fields.put("nights", quantity.stripTrailingZeros().toPlainString());
            var amounts = new LinkedHashMap<String, Long>();
            rows.forEach(row -> row.row.amounts().forEach((column, amount) -> amounts.merge(column, amount, Math::addExact)));
            return new ExpenseRow(rows.getFirst().row.itemId(), "TRAVEL_SUMMARY", start, end,
                    Objects.toString(route, "差旅汇总"), fields, amounts);
        }
    }
}
