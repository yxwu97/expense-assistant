package expenseassistant.matching;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import expenseassistant.intake.TypeInference.CaseType;
import expenseassistant.matching.ExpenseMatcher.Item;

public final class ExpenseAmounts {
    private static final BigDecimal MILEAGE_RATE = new BigDecimal("0.90");
    private static final BigDecimal USD_RATE = new BigDecimal("6.7");
    public static final String MILEAGE_RATE_TEXT = MILEAGE_RATE.toPlainString() + " 元/公里";
    private static final BigDecimal MIN_EXCEL_POSITIVE = BigDecimal.valueOf(Double.MIN_NORMAL);

    public enum Component {
        TRANSPORT, HOTEL, TAXI, TRAFFIC_SUBSIDY, MEAL_SUBSIDY, NO_STAY_SUBSIDY,
        ENTERTAINMENT, FUEL, TOLL, PARKING, OTHER
    }

    public record Amount(String faceValue, String originalCurrency, Long confirmedMinor, String basis) {
        public Amount confirmed(Long minor, String newBasis) {
            return new Amount(faceValue, originalCurrency, minor, newBasis);
        }
    }

    public record MileageFuel(Amount amount, boolean outOfRange) {}

    private ExpenseAmounts() {}

    public static boolean derivedMileageFuel(String subtype, Component component) {
        return "MILEAGE".equals(subtype) && component == Component.FUEL;
    }

    public static MileageFuel mileageFuel(BigDecimal confirmedKm) {
        if (confirmedKm == null) {
            return new MileageFuel(null, false);
        }
        try {
            if (confirmedKm.signum() == 0) {
                confirmedKm = BigDecimal.ZERO;
            } else if (confirmedKm.compareTo(MIN_EXCEL_POSITIVE) < 0 || !excelNumber(confirmedKm)) {
                return new MileageFuel(null, true);
            }
            var yuan = confirmedKm.multiply(MILEAGE_RATE).setScale(2, RoundingMode.HALF_UP);
            long cents = yuan.movePointRight(2).longValueExact();
            if (!excelNumber(yuan)) {
                return new MileageFuel(null, true);
            }
            return new MileageFuel(new Amount(null, "CNY", cents,
                    confirmedKm.toPlainString() + " 公里 × " + MILEAGE_RATE_TEXT), false);
        } catch (ArithmeticException overflow) {
            return new MileageFuel(null, true);
        }
    }

    private static boolean excelNumber(BigDecimal value) {
        double number = value.doubleValue();
        return Double.isFinite(number) && value.stripTrailingZeros().precision() <= 15
                && value.compareTo(BigDecimal.valueOf(number)) == 0;
    }

    static Long usdToCnyMinor(String faceValue) {
        if (faceValue == null || !faceValue.matches("[0-9]+(?:\\.[0-9]{1,2})?")) {
            return null;
        }
        try {
            var yuan = new BigDecimal(faceValue).multiply(USD_RATE).setScale(2, RoundingMode.HALF_UP);
            long cents = yuan.movePointRight(2).longValueExact();
            return excelNumber(yuan) ? cents : null;
        } catch (NumberFormatException | ArithmeticException invalid) {
            return null;
        }
    }

    static String usdBasis(String faceValue) {
        return "USD " + new BigDecimal(faceValue).setScale(2).toPlainString() + " × " + USD_RATE.toPlainString();
    }

    public static Component inferredComponent(CaseType type, String subtype) {
        if (type == null || "RECEIPT".equals(subtype)) {
            return type == CaseType.OTHER ? Component.OTHER : null;
        }
        return switch (type) {
            case TRAVEL -> switch (subtype) {
                case "FLIGHT", "RAIL", "TOLL", "PARKING" -> Component.TRANSPORT;
                case "HOTEL" -> Component.HOTEL;
                case "DRIVER", "RIDE" -> Component.TAXI;
                default -> null;
            };
            case ENTERTAINMENT -> Component.ENTERTAINMENT;
            case PRIVATE_CAR -> switch (subtype) {
                case "TOLL" -> Component.TOLL;
                case "PARKING" -> Component.PARKING;
                case "MILEAGE" -> Component.FUEL;
                default -> null;
            };
            case TAXI -> Set.of("DRIVER", "RIDE", "TOLL", "PARKING").contains(subtype) ? Component.TAXI : null;
            case OTHER -> Component.OTHER;
        };
    }

    public static boolean allowed(CaseType type, Component component) {
        if (type == null) {
            return false;
        }
        return switch (type) {
            case TRAVEL -> switch (component) {
                case TRANSPORT, HOTEL, TAXI, TRAFFIC_SUBSIDY, MEAL_SUBSIDY, NO_STAY_SUBSIDY -> true;
                default -> false;
            };
            case ENTERTAINMENT -> component == Component.ENTERTAINMENT;
            case PRIVATE_CAR -> component == Component.FUEL || component == Component.TOLL || component == Component.PARKING;
            case TAXI -> component == Component.TAXI;
            case OTHER -> component == Component.OTHER;
        };
    }

    public static Map<String, Long> project(CaseType type, Item item) {
        Map<String, Long> result = new LinkedHashMap<>();
        for (var entry : item.amounts().entrySet()) {
            if (allowed(type, entry.getKey()) && entry.getValue().confirmedMinor() != null) {
                result.put(entry.getKey().name(), entry.getValue().confirmedMinor());
            }
        }
        return Map.copyOf(result);
    }

    public static Long confirmedTotal(CaseType type, Collection<Item> items) {
        Long total = null;
        for (Item item : items) {
            for (Long minor : project(type, item).values()) {
                total = total == null ? minor : Math.addExact(total, minor);
            }
        }
        return total;
    }

    public static String decimal(long minor) {
        return BigDecimal.valueOf(minor, 2).toPlainString();
    }
}
