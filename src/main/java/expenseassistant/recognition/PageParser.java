package expenseassistant.recognition;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Currency;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import expenseassistant.recognition.PageResult.FieldCandidate;
import expenseassistant.recognition.PageResult.Kind;
import expenseassistant.recognition.PageResult.SummaryReference;
import expenseassistant.recognition.PageResult.TripSegment;

public final class PageParser {
    public static final String VERSION = "5";
    static final String NIGHTS = "nights";
    private static final int FLAGS = Pattern.UNICODE_CHARACTER_CLASS | Pattern.CASE_INSENSITIVE;
    private static final String MONEY = "([0-9]+(?:[.,][0-9]{1,2})?)(?![0-9.,])";
    private static final String DATE = "(20[0-9]{2}\\s*(?:年|[./-])\\s*[0-9]{1,2}\\s*(?:月|[./-])\\s*[0-9]{1,2}\\s*日?)(?![0-9])";
    private static final Pattern INVOICE = pattern("发\\s*票\\s*(?:号\\s*码|编\\s*号)\\s*[:：]?\\s*([0-9]{20})(?![0-9])");
    private static final Pattern LEGACY_CODE = pattern("发\\s*票\\s*代\\s*码\\s*[:：]?\\s*([0-9]{10}|[0-9]{12})(?![0-9])");
    private static final Pattern LEGACY_NUMBER = pattern("发\\s*票\\s*号\\s*码\\s*[:：]?\\s*([0-9]{8})(?![0-9])");
    private static final Pattern ISSUE_DATE = pattern("开\\s*票\\s*日\\s*期\\s*[:：]?" + "\\s*" + DATE);
    private static final Pattern GROSS = pattern("[（(]?\\s*小\\s*写\\s*[）)]?\\s*[:：]?\\s*[¥￥Y]\\s*" + MONEY);
    private static final Pattern NET_TAX = pattern("(?:^|\\n)\\s*合\\s*计\\s*[¥￥]\\s*" + MONEY + "\\s*[¥￥]\\s*" + MONEY);
    private static final String CURRENCY_MARK = "(?:"
            + "(?<![A-Z])(?:USD|CAD|AUD|NZD|HKD|SGD|TWD|US|CA|AU|NZ|HK|SG|NT|CN|A|C|S)\\h*[$＄]"
            + "|(?:US|U\\.S\\.)\\h+DOLLARS?\\b|人民币|美元|港币|港元|欧元|英镑|日元|韩元|澳元|加元|新台币"
            + "|(?<![A-Z])(?:" + Currency.getAvailableCurrencies().stream()
                    .map(Currency::getCurrencyCode).sorted().collect(java.util.stream.Collectors.joining("|")) + ")(?![A-Z])"
            + "|\\p{Sc})";
    private static final String AMOUNT_TOKEN = "([+\\-−]?\\h*[(（]?\\h*[0-9.,]+(?:\\h+[0-9.,]+)*[)）]?)";
    private static final String AMOUNT_VALUE = "(?:[(（]\\h*(" + CURRENCY_MARK + ")\\h*[)）]\\h*)?"
            + "[:：]?\\h*(" + CURRENCY_MARK + "|Y(?![A-Z]))?\\h*" + AMOUNT_TOKEN
            + "(?:\\h*(" + CURRENCY_MARK + "|元))?(?![\\p{L}0-9.,])";
    private static final Pattern TOTAL = pattern("(?:[（(]?\\h*小\\s*写\\h*[）)]?\\s*"
            + "|价税合计\\h*|总[额计]\\h*|(?:实付|应付|应收|付款)金额\\h*"
            + "|(?m:^\\h*(?:(?:grand|invoice|receipt)\\h+total|total(?:\\h+(?:amount(?:\\h+due)?|paid|due))?"
            + "|amount\\h+paid))\\b\\h*)"
            + AMOUNT_VALUE);
    private static final Pattern RECEIPT_TOTAL = pattern("(?:^|\\n)\\h*合\\h*计\\h*" + AMOUNT_VALUE);
    private static final Pattern NET_USD = pattern("(?m)^\\h*(?:subtotal|net\\h+amount|税前金额|不含税金额)\\h*" + AMOUNT_VALUE);
    private static final Pattern TAX_USD = pattern("(?m)^\\h*(?:tax(?:\\h+amount)?|税额)\\h*" + AMOUNT_VALUE);
    private static final Pattern DECLARED_CURRENCY = pattern("(?:all\\h+(?:amounts|prices)\\h+(?:are\\h+)?in\\h+"
            + "|(?:currency|(?:交易|结算|票面)?币种|金额单位|货币)\\h*[:：]\\h*)(" + CURRENCY_MARK + ")");
    private static final Pattern CURRENCY_CLUE = pattern(CURRENCY_MARK);
    private static final Pattern RECEIPT_HEADING = pattern("(?m)^\\h*(?:收据|receipt)\\h*$");
    private static final Pattern RECEIPT_ORDER = pattern("(?m)^\\h*(?:订单(?:号|编号)|order\\h+(?:id|no\\.?|number|#))"
            + "\\h*[:：]?\\h*([A-Z0-9][A-Z0-9-]{0,63})(?![A-Z0-9-])");
    private static final Pattern RECEIPT_ID = pattern("(?m)^\\h*(?:收据(?:号|编号)|receipt\\h+(?:id|no\\.?|number|#))"
            + "\\h*[:：]?\\h*([A-Z0-9][A-Z0-9-]{0,63})(?![A-Z0-9-])");
    private static final Pattern RECEIPT_ISSUER = pattern("(?m)^\\h*(?:开具方|收款方|商户(?:名称)?|销售方"
            + "|issued\\h+by|issuer|merchant|seller|vendor)\\h*[:：]\\h*([^\\r\\n]+)");
    private static final Pattern RECEIPT_DATE = pattern("(?m)^\\h*(?:收据日期|交易日期|开具日期|付款日期|业务日期|日期"
            + "|(?:(?:receipt|transaction|issue|purchase|payment)\\h+)?date)\\h*[:：]?\\h*" + DATE);
    private static final Pattern DIDI_TOTAL = pattern("合\\s*计\\s*" + MONEY + "\\s*元");
    private static final Pattern DIDI_DATES = pattern("行\\s*程\\s*起\\s*止\\s*日\\s*期\\s*[:：]?\\s*" + DATE + "\\s*至\\s*" + DATE);
    private static final Pattern APPLICATION_DATE = pattern("申请日期\\s*[:：]?\\s*" + DATE);
    private static final Pattern DIDI_SEGMENTS = pattern("(?m)^\\h*[0-9]+\\h+(\\S+)\\h+([0-9]{1,2})-([0-9]{1,2})"
            + "\\h+[0-9]{1,2}:\\h*(?:[0-9]{2}\\h+)?[^\\r\\n]*?\\h+" + MONEY + "\\h+" + MONEY
            + "(?:[ \\t]*$|\\h+[^0-9.])");
    private static final Pattern SUMMARY_KEY = pattern("汇\\s*总\\s*单\\s*号\\s*[:：]?\\s*([0-9]{8,32})(?![0-9])");
    private static final Pattern SUMMARY_TOTAL = pattern("交\\s*易\\s*金\\s*额\\s*[¥￥]\\s*" + MONEY);
    private static final Pattern SUMMARY_REFERENCE = pattern("\\*\\h*([0-9]{20})(?![0-9])\\h+(" + MONEY + "|-)");
    private static final Pattern INDEX_TRIPS_SPLIT = pattern(MONEY
            + "\\h+(?:[0-9]{1,3}\\h+|号\\h*发\\h*)\\*\\h*([0-9]{20})(?![0-9])");
    private static final Pattern TIMESTAMPS = pattern("(?<![0-9])(20[0-9]{6})(?:[0-9]{6})?(?![0-9])");
    private static final Pattern TOLL_DATES = pattern("(?<![0-9])(20[0-9]{6})\\h+(20[0-9]{6})(?![0-9])");
    private static final Pattern PARKING_DATES = pattern("租赁期起止\\s*[:：]?\\s*" + DATE + "\\h+[0-9:]+\\h+" + DATE);
    private static final Pattern HOTEL_CHECK_IN = pattern("入住(?:日期|时间)?\\s*[:：]?\\s*" + DATE);
    private static final Pattern HOTEL_CHECK_OUT = pattern("(?:离店|退房)(?:日期|时间)?\\s*[:：]?\\s*" + DATE);
    private static final String TICKET_ORIGIN = "(?:出发(?:地|站|机场|城市)|始发(?:地|站|机场)|起飞(?:地|机场)|起点)";
    private static final String TICKET_DESTINATION = "(?:到达(?:地|站|机场|城市)|抵达(?:地|站|机场)|目的地|终点)";
    private static final String TICKET_PLACE = "([\\p{IsHan}·]{1,24}?)";
    private static final String AIRPORT_CODE = "(?:\\h*[(（][A-Z]{3}[)）])?";
    private static final String LABEL_GAP = "(?:\\h*[:：]\\h*|\\h+)";
    private static final Pattern LABELLED_ORIGIN = ticketPlace(TICKET_ORIGIN);
    private static final Pattern LABELLED_DESTINATION = ticketPlace(TICKET_DESTINATION);
    private static final Pattern FLIGHT_LEG = pattern("(?m)^\\h*(?:航\\h*段" + LABEL_GAP + "|(?=[^\\r\\n]*(?:[-－][>＞]|→)))"
            + TICKET_PLACE + AIRPORT_CODE + "\\h*(?:[-－][>＞]?|[→至—–])\\h*"
            + TICKET_PLACE + AIRPORT_CODE + "\\h*$");
    private static final Pattern RAIL_LEG = pattern("(?m)^\\h*([\\p{IsHan}]{1,23}站)\\h+"
            + "[A-Z]?[0-9]{1,5}\\h+([\\p{IsHan}]{1,23}站)\\h*$");
    private static final Pattern TICKET_DEPARTURE = pattern("(?:乘机日期|航班日期|乘车日期|行程起止日期|"
            + "(?:起飞|出发|发车|开车)(?:日期|时间))\\h*[:：]?\\h*" + DATE
            + "(?:\\h*[至~～]\\h*" + DATE + ")?");
    private static final Pattern TICKET_ARRIVAL = pattern("(?:到达|抵达|降落)(?:日期|时间)\\h*[:：]?\\h*" + DATE);
    private static final Pattern RAIL_DEPARTURE = pattern(DATE + "\\h+[0-9]{1,2}[:：][0-9]{2}\\h*开(?!票)");
    private static final String STAY_QUANTITY = "[0-9０-９]+(?:[.．][0-9０-９]+)?(?![0-9０-９.,．，])";
    private static final String STAY_ITEM = "(?:住\\h*宿\\h*费|房\\h*费)";
    private static final String ROOM_SPECIFICATION = "(?:\\h*[(（][^\\r\\n)）*]{1,24}[)）]"
            + "|\\h+(?:(?:商务|豪华|高级|经济|普通|无窗)?(?:双床|大床|单人|双人)[房间]"
            + "|(?:标准|商务|豪华|高级|经济|普通)[房间]|标间|单间|套房|客房|-{1,2}))?";
    private static final Pattern QUANTITY = pattern(STAY_ITEM + ROOM_SPECIFICATION
            + "\\h*([天晚夜]\\h*" + STAY_QUANTITY + ")(?!\\p{L})");
    private static final Pattern DECLARED_STAY_QUANTITY = pattern("住\\h*宿\\h*[天晚夜]\\h*数"
            + "\\h*[:：]?\\h*(" + STAY_QUANTITY + "(?:\\h*[天晚夜])?+)(?!\\h*(?:[个间人张套]|小时))");
    private static final Pattern STAY_DURATION = pattern("(?:" + STAY_ITEM + "|住\\h*宿(?:\\h*时\\h*长)?)"
            + "\\h*[:：]?\\h*(" + STAY_QUANTITY + "\\h*[天晚夜])(?![餐饭点时])");
    private static final Pattern ORDER_ID = pattern("(?:订单号|订单编号|order\\s+id)\\s*[:：]?\\s*([A-Z0-9]{5,32})(?![A-Z0-9])");
    private static final Pattern APPLE_DOCUMENT = pattern("(?:文\\s*稿(?:号)?|document\\s+no\\.?)\\s*[:：]?\\s*([0-9]{8,24})(?![0-9])");
    private static final Pattern APPLE_RENEWAL = pattern(DATE + "\\s*(?:续\\s*期|renew)");
    private static final Pattern APPLE_ITEM = pattern("\\b((?:ChatG\\s*PT\\s+|PT\\s+)?Pro\\s+100)(?![0-9])");
    private static final Pattern APPLE_MERCHANT = pattern("\\bChatG\\s*PT\\b");
    private static final Pattern APPLE_AMOUNT = pattern("((?:US\\h*)?[$＄])\\h*" + AMOUNT_TOKEN);
    private static final Pattern APPLE_BILL = pattern("(?:账|帐)\\s*单|billing");
    private static final String DISTANCE_DIGITS = "[0-9０-９]+(?:\\h+[0-9０-９]+)*";
    private static final Pattern DISTANCE = pattern("(?<![0-9０-９A-Za-z.,．，…⋯+\\-－−])(" + DISTANCE_DIGITS
            + "(?:\\h*[.,．，]\\h*" + DISTANCE_DIGITS + ")?)\\h*(?:公\\h*里|千\\h*米|k\\h*m)"
            + "(?![A-Za-z]|\\s*[/／]|\\h*每\\h*小\\h*时)");
    private static final Pattern ANY_DATE = pattern(DATE);

    public Kind classify(String text) {
        String compact = compact(text).toLowerCase(Locale.ROOT);
        if (compact.contains("通行费电子票据汇总单")) {
            return compact.contains("按行程索引") ? Kind.TOLL_SUMMARY_TRIPS : Kind.TOLL_SUMMARY_INVOICES;
        }
        if (compact.contains("行程单") && (compact.contains("滴滴") || compact.contains("行程起止日期"))
                || compact.contains("diditravel-triptable")) {
            return Kind.TRIP_STATEMENT;
        }
        if ((compact.contains("收据") || compact.contains("receipt")) && compact.contains("apple")) {
            return Kind.APPLE_RECEIPT;
        }
        if (compact.contains("发票") && (compact.contains("开票日期") || INVOICE.matcher(text).find())) {
            return Kind.INVOICE;
        }
        if (compact.startsWith("收据") || compact.startsWith("receipt")
                || RECEIPT_HEADING.matcher(text).find()) {
            return Kind.RECEIPT;
        }
        if (DISTANCE.matcher(text).find()
                || compact.contains("开始导航") && MileageRouteSelector.hasDuration(text)) {
            return Kind.MILEAGE;
        }
        return Kind.UNKNOWN;
    }

    PageResult extractFields(PageText source, int page, String version) {
        var fields = new Fields(source);
        var segments = new ArrayList<TripSegment>();
        var references = new ArrayList<SummaryReference>();
        var warnings = new ArrayList<String>();
        Kind kind = classify(source.text());
        switch (kind) {
            case INVOICE -> invoice(fields, warnings);
            case TRIP_STATEMENT -> trip(fields, segments, warnings);
            case TOLL_SUMMARY_INVOICES, TOLL_SUMMARY_TRIPS -> summary(fields, kind, references);
            case APPLE_RECEIPT -> apple(fields, warnings);
            case MILEAGE, UNKNOWN -> {
                captureRouteDistances(fields);
                if (!fields.candidates.isEmpty()) {
                    kind = Kind.MILEAGE;
                }
            }
            case RECEIPT -> receipt(fields, warnings);
        }
        return new PageResult(page, kind, true, fields.candidates, segments, references, warnings, version);
    }

    boolean reliable(PageResult page) {
        if (page.warnings().stream().anyMatch(warning -> Set.of(
                "AMOUNT_CONFLICT", "CURRENCY_CONFLICT", "CURRENCY_NEEDS_REVIEW").contains(warning))) {
            return false;
        }
        return switch (page.kind()) {
            case INVOICE -> has(page, "invoiceNumber", "invoiceDate", "gross", "currency");
            case TRIP_STATEMENT -> has(page, "gross", "startDate", "endDate");
            case TOLL_SUMMARY_INVOICES, TOLL_SUMMARY_TRIPS ->
                    has(page, "gross", "summaryKey") && !page.summaryReferences().isEmpty();
            case APPLE_RECEIPT -> has(page, "receiptDate", "orderKey", "documentNumber", "item", "gross", "currency");
            case MILEAGE -> has(page, MileageRouteSelector.SELECTED);
            case RECEIPT -> has(page, "receiptIssuer", "receiptDate", "gross", "currency")
                    && (has(page, "orderKey") || has(page, "receiptNumber"));
            case UNKNOWN -> false;
        };
    }

    private static boolean has(PageResult page, String... keys) {
        for (String key : keys) {
            if (page.value(key) == null) {
                return false;
            }
        }
        return true;
    }

    private static void captureRouteDistances(Fields fields) {
        String reading = MileageRouteSelector.lineText(fields.source);
        var matches = DISTANCE.matcher(reading);
        while (matches.find()) {
            int start = matches.start();
            int end = matches.end();
            if (cutOffNumber(reading, start)) {
                continue;
            }
            String digits = Normalizer.normalize(matches.group(1), Normalizer.Form.NFKC)
                    .replaceAll("\\h+", "");
            fields.candidates.add(new FieldCandidate(MileageRouteSelector.CANDIDATE,
                    fields.source.text().substring(start, end), decimal(digits), fields.source.method(),
                    MileageRouteSelector.evidenceBounds(fields.source, start, end), "截图中有单位的候选里程"));
        }
    }

    private static boolean cutOffNumber(String reading, int start) {
        int previous = start - 1;
        while (previous >= 0 && reading.charAt(previous) != '\n' && Character.isWhitespace(reading.charAt(previous))) {
            previous--;
        }
        return previous >= 0 && ".,．，…⋯+-－−".indexOf(reading.charAt(previous)) >= 0;
    }

    private static void invoice(Fields fields, List<String> warnings) {
        fields.capture("invoiceNumber", INVOICE, 1, Function.identity(), "20 位数电发票号码");
        if (fields.find(INVOICE) == null && fields.find(LEGACY_CODE) != null && fields.find(LEGACY_NUMBER) != null) {
            fields.capture("invoiceCode", LEGACY_CODE, 1, Function.identity(), "票面发票代码");
            fields.capture("invoiceNumber", LEGACY_NUMBER, 1, Function.identity(), "通过发票代码配合定位票号");
        }
        fields.capture("invoiceDate", ISSUE_DATE, 1, PageParser::date, "开票日期字段");
        resolveTotal(fields, totals(fields, TOTAL), warnings, true, false);
        fields.capture("orderKey", ORDER_ID, 1, Function.identity(), "订单号字段");
        invoiceDetails(fields);
    }

    private static void invoiceDetails(Fields fields) {
        var text = compact(fields.source.text());
        if (text.contains("通行费")) {
            fields.rule("subtype", "TOLL", "通行费服务");
            fields.range(TOLL_DATES, PageParser::compactDate, "票面通行日期");
        } else if (text.contains("停车")) {
            fields.rule("subtype", "PARKING", "停车服务");
            fields.range(PARKING_DATES, PageParser::date, "停车服务起止时间");
        } else if (text.contains("住宿") || text.contains("房费")) {
            fields.rule("subtype", "HOTEL", "住宿或代订房费");
            fields.capture("startDate", HOTEL_CHECK_IN, 1, PageParser::date, "入住日期");
            fields.capture("endDate", HOTEL_CHECK_OUT, 1, PageParser::date, "离店日期");
            hotelNights(fields);
        } else if (text.contains("代驾")) {
            fields.rule("subtype", "DRIVER", "代驾服务");
        } else if (text.toLowerCase(Locale.ROOT).contains("didi") || text.contains("滴滴")) {
            fields.rule("subtype", "RIDE", "滴滴出行");
        } else if (text.contains("机票") || text.contains("航空")) {
            fields.rule("subtype", "FLIGHT", "机票服务");
            intercityTicket(fields, FLIGHT_LEG, TICKET_DEPARTURE);
        } else if (text.contains("铁路") || text.contains("火车")) {
            fields.rule("subtype", "RAIL", "铁路运输");
            intercityTicket(fields, RAIL_LEG, fields.find(TICKET_DEPARTURE) == null ? RAIL_DEPARTURE : TICKET_DEPARTURE);
        } else if (text.contains("餐饮") || text.contains("餐费")) {
            fields.rule("subtype", "MEAL", "餐饮服务");
        } else if (text.contains("礼品")) {
            fields.rule("subtype", "GIFT", "礼品费用");
        }
        if (text.toLowerCase(Locale.ROOT).contains("didi") || text.contains("滴滴")) {
            fields.rule("platform", "DIDI", "滴滴标识");
        }
    }

    List<FieldCandidate> extractHotelNights(PageText source) {
        var fields = new Fields(source);
        hotelNights(fields);
        return List.copyOf(fields.candidates);
    }

    private static void hotelNights(Fields fields) {
        fields.capture(NIGHTS, QUANTITY, 1, PageParser::positiveNights, "住宿项目的计量单位及数量");
        fields.capture(NIGHTS, STAY_DURATION, 1, PageParser::positiveNights, "明确标注的住宿时长");
        fields.capture(NIGHTS, DECLARED_STAY_QUANTITY, 1, PageParser::positiveNights, "明确标注的住宿天数");
    }

    private static String positiveNights(String raw) {
        String digits = Normalizer.normalize(raw, Normalizer.Form.NFKC).replaceAll("[\\h天晚夜]", "");
        var quantity = new BigDecimal(digits).stripTrailingZeros();
        return quantity.signum() > 0 && quantity.scale() <= 0 ? quantity.toPlainString() : null;
    }

    private static Pattern ticketPlace(String label) {
        String placeLabels = TICKET_ORIGIN + "|" + TICKET_DESTINATION;
        return pattern("(?m)" + label + LABEL_GAP + "(?!(?:" + placeLabels + "|车次|航班号|开票日期))"
                + TICKET_PLACE + AIRPORT_CODE + "(?=\\h|[|｜,，;；]|$|" + placeLabels + ")");
    }

    private static void intercityTicket(Fields fields, Pattern legPattern, Pattern departurePattern) {
        fields.capture("origin", LABELLED_ORIGIN, 1, Function.identity(), "票面始发地点字段");
        fields.capture("destination", LABELLED_DESTINATION, 1, Function.identity(), "票面到达地点字段");
        fields.capture("origin", legPattern, 1, Function.identity(), "票面城际区间起点");
        fields.capture("destination", legPattern, 2, Function.identity(), "票面城际区间终点");
        fields.capture("endDate", TICKET_ARRIVAL, 1, PageParser::date, "票面到达日期");
        boolean arrivalKnown = fields.find(TICKET_ARRIVAL) != null;
        var departures = departurePattern.matcher(fields.source.text());
        while (departures.find()) {
            fields.add("startDate", departures, 1, PageParser::date, "票面乘机或乘车日期");
            if (departures.groupCount() > 1 && departures.group(2) != null) {
                fields.add("endDate", departures, 2, PageParser::date, "票面行程终止日期");
            } else if (!arrivalKnown) {
                fields.add("endDate", departures, 1, PageParser::date, "票面单日乘机或乘车日期");
            }
        }
    }

    private static void trip(Fields fields, List<TripSegment> segments, List<String> warnings) {
        fields.capture("applicationDate", APPLICATION_DATE, 1, PageParser::date, "申请日期字段");
        fields.range(DIDI_DATES, PageParser::date, "行程起止日期字段");
        fields.capture("gross", DIDI_TOTAL, 1, PageParser::decimal, "行程单合计");
        fields.capture("orderKey", ORDER_ID, 1, Function.identity(), "行程单订单号");
        fields.rule("currency", "CNY", "行程人民币金额");
        String text = compact(fields.source.text()).toLowerCase(Locale.ROOT);
        if (text.contains("滴滴") || text.contains("didi")) {
            fields.rule("platform", "DIDI", "滴滴行程单");
            fields.rule("subtype", text.contains("代驾") ? "DRIVER" : "RIDE", "行程表车型");
        }
        var range = fields.find(DIDI_DATES);
        LocalDate start = range == null ? null : parsedDate(range.group(1));
        LocalDate end = range == null ? null : parsedDate(range.group(2));
        var rows = DIDI_SEGMENTS.matcher(fields.source.text());
        while (rows.find()) {
            LocalDate day = segmentDate(start, end, rows.group(2), rows.group(3));
            String segmentText = rows.group().strip();
            // Token boundaries for the two places are explicit only in compact, single-line rows.
            var places = pattern("(?:市|县)\\h+(\\S+)\\h+(\\S+)\\h+[0-9]").matcher(segmentText);
            String origin = null;
            String destination = null;
            if (places.find()) {
                origin = places.group(1);
                destination = places.group(2);
            }
            segments.add(new TripSegment(day, day, origin, destination, minor(rows.group(5)), decimal(rows.group(4))));
        }
        if (segments.isEmpty() && fields.find(DIDI_TOTAL) != null) {
            warnings.add("TRIP_SEGMENTS_UNVERIFIED");
        }
    }

    private static void summary(Fields fields, Kind kind, List<SummaryReference> references) {
        fields.capture("summaryKey", SUMMARY_KEY, 1, Function.identity(), "汇总单号");
        fields.capture("applicationDate", APPLICATION_DATE, 1, PageParser::date, "开票申请日期");
        if (fields.find(GROSS) != null) {
            fields.capture("gross", GROSS, 1, PageParser::decimal, "汇总单小写价税合计");
        } else {
            fields.capture("gross", SUMMARY_TOTAL, 1, PageParser::decimal, "票面交易金额");
        }
        fields.rule("currency", "CNY", "通行费人民币金额");
        fields.rule("subtype", "TOLL", "通行费汇总单");
        var timestamps = TIMESTAMPS.matcher(fields.source.text());
        record Observation(String normalized, int start, int end) {}
        var days = new ArrayList<Observation>();
        while (timestamps.find()) {
            var normalized = compactDate(timestamps.group(1));
            if (normalized != null) {
                days.add(new Observation(normalized, timestamps.start(1), timestamps.end(1)));
            }
        }
        if (!days.isEmpty()) {
            days.sort(java.util.Comparator.comparing(Observation::normalized));
            var first = days.getFirst();
            var last = days.getLast();
            fields.addAt("startDate", first.start(), first.end(), first.normalized(), "通行记录起始日期");
            fields.addAt("endDate", last.start(), last.end(), last.normalized(), "通行记录终止日期");
        }
        var recognized = SUMMARY_REFERENCE.matcher(fields.source.text());
        var splitLookup = new java.util.HashMap<Integer, Long>();
        if (kind == Kind.TOLL_SUMMARY_TRIPS) {
            var splits = INDEX_TRIPS_SPLIT.matcher(fields.source.text());
            while (splits.find()) {
                splitLookup.put(splits.start(2), minor(splits.group(1)));
            }
        }
        while (recognized.find()) {
            String invoice = recognized.group(1);
            Long face = minor(recognized.group(2));
            references.add(new SummaryReference(invoice, face, splitLookup.get(recognized.start(1))));
        }
    }

    private static void apple(Fields fields, List<String> warnings) {
        var order = fields.find(ORDER_ID);
        var dates = ANY_DATE.matcher(fields.source.text());
        if (order != null && dates.find() && dates.end() < order.start()) {
            fields.add("receiptDate", dates, 1, PageParser::date, "订单号之前的收据顶部日期");
            fields.add("startDate", dates, 1, PageParser::date, "订单号之前的收据顶部日期");
        }
        fields.capture("orderKey", ORDER_ID, 1, Function.identity(), "Apple 收据订单号");
        fields.capture("documentNumber", APPLE_DOCUMENT, 1, Function.identity(), "Apple 收据文稿号");
        fields.capture("renewalDate", APPLE_RENEWAL, 1, PageParser::date, "独立续期日期，未作为业务日期");
        if (fields.find(APPLE_MERCHANT) != null) {
            fields.capture("item", APPLE_ITEM, 1, raw -> "ChatGPT Pro 100", "收据中的 ChatGPT 项目");
        }
        String compact = compact(fields.source.text());
        var billing = fields.find(APPLE_BILL);
        int bill = billing == null ? -1 : billing.start();
        var item = fields.find(APPLE_ITEM);
        var totals = totals(fields, TOTAL);
        totals.addAll(totals(fields, RECEIPT_TOTAL));
        var amount = APPLE_AMOUNT.matcher(fields.source.text());
        boolean american = bill >= 0 && (compact.contains("美国") || compact.contains("CovinaCA")
                || compact.contains("UnitedStates"));
        if (totals.isEmpty() && item != null && bill >= 0) {
            while (amount.find() && amount.end() < bill) {
                totals.add(new Total(amount.start(2), amount.end(2), amount.group(2), List.of(marker(amount, 1))));
            }
        }
        resolveTotal(fields, totals, warnings, false, american && item != null);
    }

    private static void receipt(Fields fields, List<String> warnings) {
        fields.capture("orderKey", RECEIPT_ORDER, 1, PageParser::businessId, "票面订单号");
        fields.capture("receiptNumber", RECEIPT_ID, 1, PageParser::businessId, "票面收据号");
        fields.capture("receiptIssuer", RECEIPT_ISSUER, 1, String::strip, "票面开具方");
        fields.capture("receiptDate", RECEIPT_DATE, 1, PageParser::date, "票面业务日期");
        var totals = totals(fields, TOTAL);
        totals.addAll(totals(fields, RECEIPT_TOTAL));
        resolveTotal(fields, totals, warnings, false, false);
    }

    private static String businessId(String raw) {
        return raw.toUpperCase(Locale.ROOT);
    }

    private record CurrencyMark(int start, int end, String code) {}
    private record Total(int start, int end, String raw, List<CurrencyMark> marks) {}

    private static List<Total> totals(Fields fields, Pattern pattern) {
        List<Total> totals = new ArrayList<>();
        var matches = pattern.matcher(fields.source.text());
        while (matches.find()) {
            List<CurrencyMark> marks = new ArrayList<>();
            for (int index : new int[]{1, 2, 4}) {
                if (matches.group(index) != null) marks.add(marker(matches, index));
            }
            totals.add(new Total(matches.start(3), matches.end(3), matches.group(3), marks));
        }
        return totals;
    }

    private static CurrencyMark marker(Matcher matches, int index) {
        return new CurrencyMark(matches.start(index), matches.end(index), currencyCode(matches.group(index)));
    }

    private static String currencyCode(String mark) {
        return switch (Normalizer.normalize(compact(mark), Normalizer.Form.NFKC).toUpperCase(Locale.ROOT)) {
            case "USD", "USD$", "US$", "USDOLLAR", "USDOLLARS", "U.S.DOLLAR", "U.S.DOLLARS", "美元" -> "USD";
            case "CNY", "CN$", "人民币", "¥", "元" -> "CNY";
            case "Y" -> "Y";
            case "$" -> "$";
            case "HK$", "HKD$", "港币", "港元" -> "HKD";
            case "€", "欧元" -> "EUR";
            case "£", "英镑" -> "GBP";
            case "日元" -> "JPY";
            case "₩", "韩元" -> "KRW";
            case "AU$", "AUD$", "A$", "澳元" -> "AUD";
            case "CA$", "CAD$", "C$", "加元" -> "CAD";
            case "NZ$", "NZD$" -> "NZD";
            case "SG$", "SGD$", "S$" -> "SGD";
            case "NT$", "TWD$", "新台币" -> "TWD";
            default -> Normalizer.normalize(mark, Normalizer.Form.NFKC).toUpperCase(Locale.ROOT);
        };
    }

    private static void resolveTotal(Fields fields, List<Total> totals, List<String> warnings,
                                     boolean invoice, boolean americanApple) {
        var declared = declarations(fields);
        List<CurrencyMark> marks = new ArrayList<>(declared);
        totals.forEach(total -> marks.addAll(total.marks()));
        var explicit = new LinkedHashSet<String>();
        marks.stream().map(CurrencyMark::code).filter(code -> !Set.of("$", "Y").contains(code)).forEach(explicit::add);
        boolean dollar = marks.stream().anyMatch(mark -> "$".equals(mark.code()));
        if (dollar && americanApple) explicit.add("USD");
        boolean conflict = explicit.size() > 1 || dollar && explicit.contains("CNY");
        String currency = conflict || explicit.isEmpty() ? null : explicit.iterator().next();
        var foreignClue = CURRENCY_CLUE.matcher(fields.source.text()).results()
                .anyMatch(match -> !"CNY".equals(currencyCode(match.group())));
        if (currency == null && !conflict && invoice && !foreignClue && !totals.isEmpty()) currency = "CNY";
        if (conflict) warnings.add("CURRENCY_CONFLICT");
        else if (currency == null && (!totals.isEmpty() || foreignClue)) warnings.add("CURRENCY_NEEDS_REVIEW");
        writeCurrency(fields, marks, currency, americanApple);

        String resolved = currency;
        var values = totals.stream().map(total -> parseTotal(total.raw(), resolved)).toList();
        boolean inconsistent = values.stream().anyMatch(java.util.Objects::isNull)
                || values.stream().distinct().count() > 1
                || reconciliationDiffers(fields, values, resolved, declared, invoice);
        if (inconsistent) warnings.add("AMOUNT_CONFLICT");
        for (int index = 0; index < totals.size(); index++) {
            var amount = totals.get(index);
            fields.addAt("gross", amount.start(), amount.end(), conflict || inconsistent ? null : values.get(index),
                    inconsistent ? "总额格式或分项待核对" : "票面总额");
        }
    }

    private static List<CurrencyMark> declarations(Fields fields) {
        List<CurrencyMark> marks = new ArrayList<>();
        var declaration = DECLARED_CURRENCY.matcher(fields.source.text());
        while (declaration.find()) marks.add(marker(declaration, 1));
        return marks;
    }

    private static void writeCurrency(Fields fields, List<CurrencyMark> marks, String currency, boolean americanApple) {
        for (var mark : marks) {
            fields.addAt("currency", mark.start(), mark.end(), currency, "总额币种或票面币种声明");
        }
        if (marks.isEmpty() && currency != null) fields.rule("currency", currency, "人民币电子发票");
        if (americanApple && "USD".equals(currency) && marks.stream().anyMatch(mark -> "$".equals(mark.code()))) {
            fields.rule("currency", currency, "美元符号及美国账单地址");
        }
    }

    private static String parseTotal(String raw, String currency) {
        String value = raw.strip();
        if (value.matches("(?:[0-9]+|[0-9]{1,3}(?:,[0-9]{3})+)(?:\\.[0-9]{1,2})?")) {
            return new BigDecimal(value.replace(",", "")).setScale(2).toPlainString();
        }
        if ("CNY".equals(currency) && value.matches("[0-9]+,[0-9]{1,2}")) {
            return new BigDecimal(value.replace(',', '.')).setScale(2).toPlainString();
        }
        return null;
    }

    private static boolean reconciliationDiffers(Fields fields, List<String> values, String currency,
                                                  List<CurrencyMark> declared, boolean invoice) {
        if (values.isEmpty() || values.getFirst() == null) return false;
        if (invoice && "CNY".equals(currency)) {
            Long total = minor(values.getFirst());
            var tax = fields.find(NET_TAX);
            return total == null || tax != null && !total.equals(sumMinor(tax.group(1), tax.group(2)));
        }
        if (!"USD".equals(currency)) return false;
        boolean declaredUsd = declared.stream().anyMatch(mark -> "USD".equals(mark.code()));
        var net = usdPart(fields, NET_USD, declaredUsd);
        var tax = usdPart(fields, TAX_USD, declaredUsd);
        return net != null && tax != null && net.add(tax).compareTo(new BigDecimal(values.getFirst())) != 0;
    }

    private static BigDecimal usdPart(Fields fields, Pattern pattern, boolean declaredUsd) {
        var candidates = totals(fields, pattern);
        if (candidates.size() != 1) return null;
        var part = candidates.getFirst();
        if (!(part.marks().isEmpty() ? declaredUsd : part.marks().stream()
                .allMatch(mark -> "USD".equals(mark.code()) || declaredUsd && "$".equals(mark.code())))) {
            return null;
        }
        String value = parseTotal(part.raw(), "USD");
        return value == null ? null : new BigDecimal(value);
    }

    private static LocalDate segmentDate(LocalDate start, LocalDate end, String month, String day) {
        if (start == null || end == null) {
            return null;
        }
        for (int year = start.getYear(); year <= end.getYear() && year - start.getYear() <= 1; year++) {
            try {
                LocalDate date = LocalDate.of(year, Integer.parseInt(month), Integer.parseInt(day));
                if (!date.isBefore(start) && !date.isAfter(end)) {
                    return date;
                }
            } catch (DateTimeException invalid) {
                // Keep the segment amount available for independent reconciliation.
            }
        }
        return null;
    }

    private static String compact(String text) {
        return text.replaceAll("(?U)\\s+", "");
    }

    static String date(String input) {
        LocalDate date = parsedDate(input);
        return date == null ? null : date.toString();
    }

    private static LocalDate parsedDate(String input) {
        String[] pieces = compact(input).replace("日", "").split("[年月./-]");
        if (pieces.length != 3) {
            return null;
        }
        try {
            return LocalDate.of(Integer.parseInt(pieces[0]), Integer.parseInt(pieces[1]), Integer.parseInt(pieces[2]));
        } catch (DateTimeException | NumberFormatException invalidDate) {
            return null;
        }
    }

    private static String compactDate(String input) {
        if (!input.matches("[0-9]{8}")) {
            return null;
        }
        return date(input.substring(0, 4) + "-" + input.substring(4, 6) + "-" + input.substring(6));
    }

    static String decimal(String raw) {
        try {
            var value = new BigDecimal(raw.replace(',', '.'));
            return value.signum() >= 0 ? value.toPlainString() : null;
        } catch (NumberFormatException invalid) {
            return null;
        }
    }

    private static Long minor(String raw) {
        String normalized = decimal(raw);
        if (normalized == null) {
            return null;
        }
        try {
            return new BigDecimal(normalized).movePointRight(2).longValueExact();
        } catch (ArithmeticException invalid) {
            return null;
        }
    }

    private static Long sumMinor(String one, String two) {
        Long left = minor(one);
        Long right = minor(two);
        if (left == null || right == null) {
            return null;
        }
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException outOfRange) {
            return null;
        }
    }

    private static Pattern pattern(String syntax) {
        return Pattern.compile(syntax, FLAGS);
    }

    private static final class Fields {
        private final PageText source;
        private final List<FieldCandidate> candidates = new ArrayList<>();

        private Fields(PageText source) {
            this.source = source;
        }

        private Matcher find(Pattern pattern) {
            Matcher match = pattern.matcher(source.text());
            return match.find() ? match : null;
        }

        private void capture(String key, Pattern pattern, int index, Function<String, String> normalize, String basis) {
            Matcher matcher = pattern.matcher(source.text());
            while (matcher.find()) {
                add(key, matcher, index, normalize, basis);
            }
        }

        private void range(Pattern pattern, Function<String, String> normalize, String basis) {
            Matcher matcher = find(pattern);
            if (matcher != null) {
                add("startDate", matcher, 1, normalize, basis);
                add("endDate", matcher, 2, normalize, basis);
            }
        }

        private void add(String key, Matcher matcher, int index, Function<String, String> normalize, String basis) {
            String normalized = normalize.apply(matcher.group(index));
            addAt(key, matcher.start(index), matcher.end(index), normalized,
                    normalized == null ? basis + "，待复核" : basis);
        }

        private void addAt(String key, int start, int end, String normalized, String basis) {
            candidates.add(new FieldCandidate(key, source.text().substring(start, end), normalized,
                    source.method(), source.bounds(start, end), basis));
        }

        private void rule(String key, String value, String basis) {
            candidates.add(new FieldCandidate(key, basis, value, PageResult.Method.RULE, null, basis));
        }
    }
}
