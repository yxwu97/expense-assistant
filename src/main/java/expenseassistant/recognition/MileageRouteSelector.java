package expenseassistant.recognition;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import expenseassistant.recognition.PageResult.Bounds;
import expenseassistant.recognition.PageResult.FieldCandidate;

final class MileageRouteSelector {
    static final String CANDIDATE = "routeDistanceCandidateKm";
    static final String SELECTED = "selectedRouteDistanceKm";
    static final String NEEDS_REVIEW = "MILEAGE_NEEDS_REVIEW";

    private static final String NUMBER = "[0-9０-９]+";
    private static final String MINUTES = NUMBER + "\\h*分(?:\\h*钟)?";
    private static final Pattern DURATION = Pattern.compile(NUMBER + "\\h*小\\h*时(?:\\h*" + MINUTES + ")?|" + MINUTES);

    private MileageRouteSelector() {}

    static boolean hasDuration(String text) {
        return DURATION.matcher(text).find();
    }

    static PageResult select(BufferedImage image, PageText text, PageResult page) {
        var durations = durations(text);
        var pairs = new ArrayList<Card>();
        for (var candidate : page.fields()) {
            if (!CANDIDATE.equals(candidate.key()) || candidate.normalized() == null || !visible(image, candidate.bounds())) {
                continue;
            }
            var matches = durations.stream()
                    .filter(duration -> visible(image, duration) && aligned(image, duration, candidate.bounds()))
                    .toList();
            if (matches.size() == 1) {
                pairs.add(new Card(candidate, matches.getFirst()));
            }
        }
        var cards = pairs.stream().filter(card -> pairs.stream()
                .filter(other -> other.duration().equals(card.duration())).count() == 1).toList();
        var row = uniqueRow(cards);
        Card selected = emphasizedCard(image, row);
        if (selected == null) {
            return needsReview(page);
        }
        var distance = selected.distance();
        var fields = new ArrayList<>(page.fields());
        fields.add(new FieldCandidate(SELECTED, distance.raw(), distance.normalized(), distance.method(),
                distance.bounds(), "同一路线卡片的用时和里程均有蓝色选中态"));
        return new PageResult(page.pageNo(), page.kind(), page.renderable(), fields, page.segments(),
                page.summaryReferences(), page.warnings(), page.parserVersion());
    }

    static PageResult needsReview(PageResult page) {
        if (page.warnings().contains(NEEDS_REVIEW)) {
            return page;
        }
        var warnings = new ArrayList<>(page.warnings());
        warnings.add(NEEDS_REVIEW);
        return new PageResult(page.pageNo(), page.kind(), page.renderable(), page.fields(), page.segments(),
                page.summaryReferences(), warnings, page.parserVersion());
    }

    // Keep text offsets while treating gaps between nonadjacent boxes as line boundaries.
    static String lineText(PageText source) {
        var reading = new StringBuilder(source.text());
        for (int i = 1; i < source.spans().size(); i++) {
            var left = source.spans().get(i - 1);
            var right = source.spans().get(i);
            if (left.end() >= right.start() || !reading.substring(left.end(), right.start()).isBlank()) {
                continue;
            }
            String gap = adjacent(left, right) ? " " : "\n";
            reading.replace(left.end(), right.start(), gap.repeat(right.start() - left.end()));
        }
        return reading.toString();
    }

    static Bounds evidenceBounds(PageText source, int start, int end) {
        int covered = start;
        PageText.Span previous = null;
        for (var span : source.spans()) {
            if (span.start() >= end) {
                break;
            }
            if (span.end() <= start) {
                continue;
            }
            if (!source.text().substring(covered, Math.max(covered, span.start())).isBlank()
                    || previous != null && !adjacent(previous, span)) {
                return null;
            }
            covered = Math.min(end, span.end());
            previous = span;
        }
        return covered == end ? source.bounds(start, end) : null;
    }

    private static boolean adjacent(PageText.Span first, PageText.Span second) {
        var left = first.bounds();
        var right = second.bounds();
        double glyph = Math.max(left.width() / (first.end() - first.start()),
                right.width() / (second.end() - second.start()));
        return right.x() >= left.x() && verticalOverlap(left, right)
                && right.x() - left.x() - left.width() <= 0.85 * glyph;
    }

    private static List<Bounds> durations(PageText text) {
        var found = new ArrayList<Bounds>();
        var matcher = DURATION.matcher(lineText(text));
        while (matcher.find()) {
            var bounds = evidenceBounds(text, matcher.start(), matcher.end());
            if (bounds != null) {
                found.add(bounds);
            }
        }
        return found;
    }

    private static boolean aligned(BufferedImage image, Bounds duration, Bounds distance) {
        double lineHeight = Math.max(duration.height(), distance.height());
        double shift = distance.y() - duration.y();
        double leftOff = Math.abs(duration.x() - distance.x()) * image.getWidth() / image.getHeight();
        return shift >= 0.65 * duration.height() && shift <= 2.6 * lineHeight
                && leftOff <= 1.1 * lineHeight;
    }

    private static boolean visible(BufferedImage image, Bounds bounds) {
        if (bounds == null || bounds.width() == 0 || bounds.height() == 0) {
            return false;
        }
        double horizontalMargin = Math.max(1.25 / image.getWidth(), bounds.width() * 0.025);
        return bounds.x() > horizontalMargin && bounds.x() + bounds.width() < 1 - horizontalMargin
                && bounds.y() > 0 && bounds.y() + bounds.height() < 1;
    }

    private static boolean verticalOverlap(Bounds a, Bounds b) {
        double shared = Math.min(a.y() + a.height(), b.y() + b.height()) - Math.max(a.y(), b.y());
        return shared >= 0.55 * Math.min(a.height(), b.height());
    }

    private static List<Card> uniqueRow(List<Card> cards) {
        var rows = new ArrayList<List<Card>>();
        for (var card : cards) {
            var row = rows.stream().filter(existing -> existing.stream().allMatch(other -> sameRow(card, other)))
                    .findFirst().orElse(null);
            if (row == null) {
                row = new ArrayList<>();
                rows.add(row);
            }
            row.add(card);
        }
        int widest = rows.stream().mapToInt(List::size).max().orElse(0);
        var largest = rows.stream().filter(row -> row.size() == widest).toList();
        return largest.size() == 1 ? largest.getFirst() : List.of();
    }

    private static boolean sameRow(Card one, Card other) {
        var a = one.distance().bounds();
        var b = other.distance().bounds();
        return (a.x() + a.width() < b.x() || b.x() + b.width() < a.x())
                && verticalOverlap(a, b) && verticalOverlap(one.duration(), other.duration());
    }

    private static Card emphasizedCard(BufferedImage image, List<Card> row) {
        Card selected = null;
        int competing = 0;
        for (var card : row) {
            double strength = Math.min(blueInk(image, card.duration()), blueInk(image, card.distance().bounds()));
            if (strength >= 0.45) {
                competing++;
            }
            if (strength >= 0.72) {
                selected = card;
            }
        }
        return competing == 1 ? selected : null;
    }

    private static double blueInk(BufferedImage image, Bounds box) {
        int left = (int) (box.x() * image.getWidth());
        int top = (int) (box.y() * image.getHeight());
        int right = Math.min(image.getWidth(), (int) Math.ceil((box.x() + box.width()) * image.getWidth()));
        int bottom = Math.min(image.getHeight(), (int) Math.ceil((box.y() + box.height()) * image.getHeight()));
        int stride = Math.max(1, Math.min(right - left, bottom - top) / 160);
        int sampled = 0, ink = 0, blue = 0;
        for (int y = top; y < bottom; y += stride) {
            for (int x = left; x < right; x += stride) {
                int pixel = image.getRGB(x, y);
                int r = pixel >> 16 & 255, g = pixel >> 8 & 255, b = pixel & 255;
                sampled++;
                if ((pixel >>> 24) >= 192 && 3 * r + 6 * g + b <= 2000) {
                    ink++;
                    if (b - r >= 40 && b - g >= 16) {
                        blue++;
                    }
                }
            }
        }
        // Limit blue to the amount expected from glyph strokes, excluding a solid button fill.
        double density = (double) blue / sampled;
        return density >= 0.045 && density <= 0.65 ? (double) blue / ink : 0;
    }

    private record Card(FieldCandidate distance, Bounds duration) {}
}
