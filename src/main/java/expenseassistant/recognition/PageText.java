package expenseassistant.recognition;

import java.util.ArrayList;
import java.util.List;

import expenseassistant.recognition.PageResult.Bounds;

record PageText(String text, List<Span> spans, PageResult.Method method) {

    Bounds bounds(int start, int end) {
        Bounds result = null;
        for (Span span : spans) {
            if (span.start() >= end) {
                break;
            }
            if (span.end() > start && span.bounds() != null) {
                result = result == null ? span.bounds() : result.union(span.bounds());
            }
        }
        return result;
    }

    record Span(int start, int end, Bounds bounds) {}

    static final class Builder {
        private final StringBuilder text = new StringBuilder();
        private final List<Span> spans = new ArrayList<>();

        void append(String raw, Bounds bounds) {
            int start = text.length();
            text.append(raw);
            if (bounds != null && !raw.isEmpty()) {
                spans.add(new Span(start, text.length(), bounds));
            }
        }

        PageText toPageText(PageResult.Method method) {
            return new PageText(text.toString(), List.copyOf(spans), method);
        }
    }
}
