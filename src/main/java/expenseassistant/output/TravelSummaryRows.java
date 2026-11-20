package expenseassistant.output;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
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
import expenseassistant.output.ExportSnapshot.ExpenseRow;

import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.mapping;
import static java.util.stream.Collectors.toSet;

final class TravelSummaryRows {
    private static final Set<String> INTERCITY = Set.of("FLIGHT", "RAIL");
    private static final Set<String> LOCAL_TAXI = Set.of("RIDE", "DRIVER");
    private static final List<String> KNOWN_VEHICLES = List.of("火车", "飞机", "私车");
    private static final Pattern STATION_SUFFIX = Pattern.compile("(?<=[\\p{IsHan}]{2})[东西南北]?站$");
    private static final Pattern ROUTE_SEPARATOR = Pattern.compile("\\h*(?:->|→|—|至)\\h*");
    private static final Pattern VEHICLE_SEPARATOR = Pattern.compile("[、,，;；/／+＋|\\r\\n]+");
    private static final Comparator<LocalDate> DATE_ORDER = Comparator.nullsLast(Comparator.naturalOrder());
    private static final Comparator<LocatedRow> ROUTE_ORDER =
            Comparator.<LocatedRow, Boolean>comparing(entry -> !entry.place().manual())
                    .thenComparing(entry -> entry.place().origin() == null)
                    .thenComparing(entry -> DateRange.of(entry.row()).start(), DATE_ORDER)
                    .thenComparing(entry -> entry.place().display());

    private TravelSummaryRows() {}

    static List<ExpenseRow> summarize(CaseView view, List<ExpenseRow> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        var directory = TypeInference.travelDirectory(view.info().folder().getFileName().toString()).orElse(null);
        var manualFields = view.overrides().stream().filter(override -> "SET".equals(override.action()))
                .collect(groupingBy(CaseView.OverrideView::targetId, mapping(CaseView.OverrideView::fieldKey, toSet())));
        var located = rows.stream()
                .map(row -> new LocatedRow(row, place(row, manualFields.getOrDefault(row.itemId(), Set.of()))))
                .toList();
        var groups = new LinkedHashMap<String, Group>();
        var unassigned = new ArrayList<ExpenseRow>();
        for (var entry : located) {
            var destination = orient(entry, located, directory);
            if (destination == null) {
                unassigned.add(entry.row());
            } else {
                String key = placeKey(destination.destination(), directory);
                groups.computeIfAbsent(key, Group::new).add(entry.row(), destination);
            }
        }
        if (groups.isEmpty() && directory != null) {
            String key = placeKey(directory.destination(), null);
            var group = new Group(key);
            group.fallback = directory.destination();
            groups.put(key, group);
        }
        int destinations = groups.size();
        assignRemainder(groups, unassigned, directory);
        var summaries = groups.values().stream().map(group -> group.summarize(destinations == 1 ? directory : null))
                .sorted(Comparator.comparing(ExpenseRow::startDate, DATE_ORDER)
                        .thenComparing(row -> row.fields().getOrDefault("route", "")))
                .toList();
        // The worksheet total covers every amount, including amounts from distinct destination groups.
        summaries.stream().flatMap(row -> row.amounts().values().stream()).reduce(0L, Math::addExact);
        return summaries;
    }

    private static Place place(ExpenseRow row, Set<String> manualFields) {
        if (LOCAL_TAXI.contains(row.subtype())) {
            return null;
        }
        String origin = clean(row.fields().get("origin"));
        String destination = clean(row.fields().get("destination"));
        String route = manualFields.contains("route") ? clean(row.fields().get("route")) : null;
        if (route != null && !route.contains("\n") && !route.contains("\r")) {
            String[] parts = ROUTE_SEPARATOR.split(route, -1);
            if (parts.length == 1) {
                return new Place(null, route, true, true);
            }
            if (parts.length == 2 && clean(parts[0]) != null && clean(parts[1]) != null) {
                return new Place(clean(parts[0]), clean(parts[1]), true, true);
            }
        }
        if (manualFields.contains("destination") && destination != null) {
            return new Place(origin, destination, true, true);
        }
        if (!INTERCITY.contains(row.subtype()) || origin == null || destination == null) {
            return null;
        }
        return new Place(origin, destination, manualFields.contains("origin"), false);
    }

    private static Place orient(LocatedRow entry, List<LocatedRow> located, TravelDirectory directory) {
        var place = entry.place();
        if (place == null || place.origin() == null) {
            return place;
        }
        String origin = placeKey(place.origin(), directory);
        String destination = placeKey(place.destination(), directory);
        if (origin.equals(destination)) {
            return place.manual() ? new Place(null, place.destination(), true, place.confirmedDestination()) : null;
        }
        if (place.confirmedDestination()) return place;
        if (directory != null) {
            String declared = placeKey(directory.destination(), null);
            if (declared.equals(origin)) return place.reverse();
            if (declared.equals(destination)) return place;
        }
        var opposite = located.stream()
                .filter(other -> sameDirection(other.place(), destination, origin, directory)).toList();
        if (opposite.isEmpty()) {
            return place;
        }
        var inbound = firstBusinessRange(opposite);
        var outbound = firstBusinessRange(located.stream()
                .filter(other -> sameDirection(other.place(), origin, destination, directory)).toList());
        if (outbound == null || inbound == null) {
            return null;
        }
        if (outbound.end().isBefore(inbound.start())) return place;
        if (inbound.end().isBefore(outbound.start())) return place.reverse();
        return null;
    }

    private static boolean sameDirection(Place place, String origin, String destination, TravelDirectory directory) {
        return place != null && place.origin() != null
                && origin.equals(placeKey(place.origin(), directory))
                && destination.equals(placeKey(place.destination(), directory));
    }

    private static DateRange firstBusinessRange(List<LocatedRow> rows) {
        return rows.stream().map(entry -> DateRange.of(entry.row())).filter(date -> date.start() != null)
                .min(Comparator.comparing(DateRange::start).thenComparing(DateRange::end)).orElse(null);
    }

    private static void assignRemainder(Map<String, Group> groups, List<ExpenseRow> unassigned, TravelDirectory directory) {
        // Date intervals come from located expenses and remain stable as other expenses are added.
        var ranges = new LinkedHashMap<String, DateRange>();
        groups.forEach((key, group) -> ranges.put(key, DateRange.cover(group.rows)));
        for (var row : unassigned) {
            String key = remainderDestination(row, ranges, directory);
            groups.computeIfAbsent(key, Group::new).add(row, null);
        }
    }

    private static String remainderDestination(ExpenseRow row, Map<String, DateRange> ranges, TravelDirectory directory) {
        if (ranges.size() == 1) {
            return ranges.keySet().iterator().next();
        }
        String destination = INTERCITY.contains(row.subtype()) ? clean(row.fields().get("destination")) : null;
        if (destination != null && clean(row.fields().get("origin")) == null) {
            String key = placeKey(destination, directory);
            if (ranges.containsKey(key)) return key;
        }
        DateRange dates = DateRange.of(row);
        var matching = ranges.entrySet().stream().filter(entry -> entry.getValue().contains(dates)).limit(2).toList();
        return matching.size() == 1 ? matching.getFirst().getKey() : null;
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

    private static String vehicles(List<ExpenseRow> rows) {
        var tools = rows.stream().filter(row -> !LOCAL_TAXI.contains(row.subtype()))
                .map(row -> row.fields().get("vehicle")).filter(Objects::nonNull)
                .flatMap(VEHICLE_SEPARATOR::splitAsStream).map(TravelSummaryRows::clean)
                .filter(Objects::nonNull).distinct()
                .sorted(Comparator.<String>comparingInt(tool -> {
                    int order = KNOWN_VEHICLES.indexOf(tool);
                    return order < 0 ? KNOWN_VEHICLES.size() : order;
                }).thenComparing(Comparator.naturalOrder())).toList();
        return String.join("、", tools);
    }

    private static BigDecimal nights(List<ExpenseRow> rows) {
        var stays = rows.stream().filter(row -> "HOTEL".equals(row.subtype())
                || row.amounts().containsKey(Component.HOTEL.name()) || row.fields().containsKey("nights"))
                .sorted(Comparator.comparing(ExpenseRow::startDate, DATE_ORDER)).toList();
        if (stays.isEmpty()) return null;
        if (stays.size() == 1) return nightQuantity(stays.getFirst());
        BigDecimal total = BigDecimal.ZERO;
        LocalDate previousCheckout = null;
        for (var stay : stays) {
            var quantity = nightQuantity(stay);
            var checkIn = stay.startDate();
            var checkOut = stay.endDate();
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

    private record LocatedRow(ExpenseRow row, Place place) {}

    private record Place(String origin, String destination, boolean manual, boolean confirmedDestination) {
        Place reverse() {
            return new Place(destination, origin, manual, confirmedDestination);
        }

        String display() {
            return origin == null ? destination : origin + "->" + destination;
        }
    }

    private record DateRange(LocalDate start, LocalDate end) {
        static DateRange of(ExpenseRow row) {
            if (row.startDate() != null && row.endDate() != null && row.endDate().isBefore(row.startDate())) {
                return new DateRange(row.endDate(), row.startDate());
            }
            return new DateRange(row.startDate() == null ? row.endDate() : row.startDate(),
                    row.endDate() == null ? row.startDate() : row.endDate());
        }

        static DateRange cover(List<ExpenseRow> rows) {
            var ranges = rows.stream().map(DateRange::of).filter(date -> date.start() != null).toList();
            return new DateRange(ranges.stream().map(DateRange::start).min(Comparator.naturalOrder()).orElse(null),
                    ranges.stream().map(DateRange::end).max(Comparator.naturalOrder()).orElse(null));
        }

        boolean contains(DateRange other) {
            return start != null && other.start != null && !other.start.isBefore(start) && !other.end.isAfter(end);
        }

        DateRange include(TravelDirectory directory) {
            return new DateRange(start == null || directory.startDate().isBefore(start) ? directory.startDate() : start,
                    end == null || directory.endDate().isAfter(end) ? directory.endDate() : end);
        }
    }

    private static final class Group {
        private final String key;
        private final List<ExpenseRow> rows = new ArrayList<>();
        private String fallback;
        private LocatedRow bestRoute;

        private Group(String key) {
            this.key = key;
        }

        private void add(ExpenseRow row, Place place) {
            rows.add(row);
            if (place != null) {
                var candidate = new LocatedRow(row, place);
                if (bestRoute == null || ROUTE_ORDER.compare(candidate, bestRoute) < 0) bestRoute = candidate;
            }
        }

        private ExpenseRow summarize(TravelDirectory directory) {
            var dates = DateRange.cover(rows);
            if (directory != null && key != null && (dates.start() == null
                    || rows.stream().anyMatch(row -> row.startDate() == null || row.endDate() == null))) {
                dates = dates.include(directory);
            }
            String route = bestRoute == null ? fallback : bestRoute.place().display();
            var fields = new LinkedHashMap<String, String>();
            if (route != null) fields.put("route", route);
            String vehicles = vehicles(rows);
            if (!vehicles.isEmpty()) fields.put("vehicle", vehicles);
            BigDecimal nights = nights(rows);
            if (nights != null) fields.put("nights", nights.stripTrailingZeros().toPlainString());
            var amounts = new LinkedHashMap<String, Long>();
            rows.forEach(row -> row.amounts().forEach((column, amount) -> amounts.merge(column, amount, Math::addExact)));
            return new ExpenseRow(rows.getFirst().itemId(), "TRAVEL_SUMMARY", dates.start(), dates.end(),
                    Objects.toString(route, "差旅汇总"), fields, amounts);
        }
    }
}
