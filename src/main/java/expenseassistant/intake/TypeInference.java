package expenseassistant.intake;

import java.text.Normalizer;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.Optional;
import java.util.regex.Pattern;

public final class TypeInference {
    private static final Pattern TRAVEL_DIRECTORY = Pattern.compile(
            "^(.+?)出差[\\s._·-]*(\\d{4})[./-](\\d{1,2})[./-](\\d{1,2})"
                    + "(?:\\s*[~至—–-]\\s*(?:(?:(\\d{4})[./-])?(\\d{1,2})[./-])?(\\d{1,2}))?$");

    private TypeInference() {}

    public record TravelDirectory(String destination, LocalDate startDate, LocalDate endDate) {}

    public enum CaseType {
        TRAVEL("差旅费"), ENTERTAINMENT("业务招待费"), PRIVATE_CAR("私车公用"),
        TAXI("的士费用"), OTHER("其他费用");

        private final String label;

        CaseType(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public static CaseType infer(String directoryName) {
        String name = normalizedName(directoryName);
        return switch (name) {
            case "业务招待", "业务招待费" -> CaseType.ENTERTAINMENT;
            case "私车公用" -> CaseType.PRIVATE_CAR;
            case "交通费", "的士费用" -> CaseType.TAXI;
            case "其他报销", "其他费用" -> CaseType.OTHER;
            default -> parseTravelDirectory(name).isPresent() ? CaseType.TRAVEL : null;
        };
    }

    public static Optional<TravelDirectory> travelDirectory(String directoryName) {
        return parseTravelDirectory(normalizedName(directoryName));
    }

    private static String normalizedName(String directoryName) {
        return directoryName == null ? "" : Normalizer.normalize(directoryName, Normalizer.Form.NFKC).strip();
    }

    private static Optional<TravelDirectory> parseTravelDirectory(String name) {
        var match = TRAVEL_DIRECTORY.matcher(name);
        if (!match.matches() || match.group(1).isBlank()) {
            return Optional.empty();
        }
        int year = Integer.parseInt(match.group(2));
        if (year == 0) {
            return Optional.empty();
        }
        int month = Integer.parseInt(match.group(3));
        try {
            LocalDate start = LocalDate.of(year, month, Integer.parseInt(match.group(4)));
            LocalDate end = start;
            if (match.group(7) != null) {
                int endYear = match.group(5) == null ? year : Integer.parseInt(match.group(5));
                int endMonth = match.group(6) == null ? month : Integer.parseInt(match.group(6));
                end = LocalDate.of(endYear, endMonth, Integer.parseInt(match.group(7)));
            }
            return end.isBefore(start) ? Optional.empty()
                    : Optional.of(new TravelDirectory(match.group(1).strip(), start, end));
        } catch (DateTimeException invalidDate) {
            return Optional.empty();
        }
    }
}
