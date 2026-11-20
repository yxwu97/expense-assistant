package expenseassistant.recognition;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public record PageResult(int pageNo, Kind kind, boolean renderable, List<FieldCandidate> fields,
                         List<TripSegment> segments, List<SummaryReference> summaryReferences,
                         List<String> warnings, String parserVersion) {

    public PageResult {
        if (pageNo < 1) {
            throw new IllegalArgumentException("页码须从 1 开始");
        }
        fields = List.copyOf(fields);
        segments = List.copyOf(segments);
        summaryReferences = List.copyOf(summaryReferences);
        warnings = List.copyOf(warnings);
    }

    public Optional<FieldCandidate> field(String key) {
        var values = fields.stream()
                .filter(field -> key.equals(field.key()) && field.normalized() != null)
                .toList();
        return values.stream().map(FieldCandidate::normalized).distinct().limit(2).count() == 1
                ? Optional.of(values.getFirst()) : Optional.empty();
    }

    public String value(String key) {
        return field(key).map(FieldCandidate::normalized).orElse(null);
    }

    public LocalDate date(String key) {
        var value = value(key);
        return value == null ? null : LocalDate.parse(value);
    }

    public Long faceMinor() {
        var value = value("gross");
        if (value == null || !"CNY".equals(value("currency"))) {
            return null;
        }
        try {
            return new BigDecimal(value).movePointRight(2).longValueExact();
        } catch (ArithmeticException invalidAmount) {
            return null;
        }
    }

    public enum Kind {
        INVOICE, TRIP_STATEMENT, TOLL_SUMMARY_INVOICES, TOLL_SUMMARY_TRIPS, APPLE_RECEIPT, RECEIPT, MILEAGE, UNKNOWN
    }

    public enum Method {
        PDF_TEXT, OCR, FILE_NAME, RULE
    }

    public record FieldCandidate(String key, String raw, String normalized, Method method, Bounds bounds,
                                 String validation) {}

    public record Bounds(double x, double y, double width, double height) {
        public Bounds {
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(width) || !Double.isFinite(height)
                    || x < 0 || y < 0 || width < 0 || height < 0 || x + width > 1.000001 || y + height > 1.000001) {
                throw new IllegalArgumentException("越界的归一化矩形");
            }
        }

        Bounds union(Bounds other) {
            double left = Math.min(x, other.x);
            double top = Math.min(y, other.y);
            return new Bounds(left, top, Math.max(x + width, other.x + other.width) - left,
                    Math.max(y + height, other.y + other.height) - top);
        }

        static Bounds fromEdges(double left, double top, double right, double bottom, double width, double height) {
            double x = Math.clamp(left / width, 0, 1);
            double y = Math.clamp(top / height, 0, 1);
            return new Bounds(x, y, Math.clamp(right / width, x, 1) - x,
                    Math.clamp(bottom / height, y, 1) - y);
        }
    }

    public record TripSegment(LocalDate start, LocalDate end, String origin, String destination,
                              Long minor, String distanceKm) {}

    public record SummaryReference(String invoiceKey, Long faceMinor, Long splitMinor) {}
}
