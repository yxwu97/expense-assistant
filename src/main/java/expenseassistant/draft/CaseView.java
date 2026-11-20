package expenseassistant.draft;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import expenseassistant.casework.CaseService.CaseInfo;
import expenseassistant.matching.ExpenseAmounts;
import expenseassistant.matching.ExpenseMatcher.Claim;
import expenseassistant.matching.ExpenseMatcher.Evidence;
import expenseassistant.matching.ExpenseMatcher.Item;
import expenseassistant.matching.ExpenseMatcher.Link;
import expenseassistant.output.ExportSnapshot.AttachmentPage;
import expenseassistant.output.ExportSnapshot.UnavailableSource;
import expenseassistant.recognition.PageResult;

public record CaseView(CaseInfo info, List<Item> items, List<Source> sources,
                       List<Link> links, List<Claim> claims, Set<String> duplicateUnitIds,
                       List<AttachmentPage> attachments, List<UnavailableSource> unavailable,
                       List<OverrideView> overrides, List<AttachmentSetting> attachmentSettings) {

    public CaseView {
        items = List.copyOf(items);
        sources = List.copyOf(sources);
        links = List.copyOf(links);
        claims = List.copyOf(claims);
        duplicateUnitIds = Set.copyOf(duplicateUnitIds);
        attachments = List.copyOf(attachments);
        unavailable = List.copyOf(unavailable);
        overrides = List.copyOf(overrides);
        attachmentSettings = List.copyOf(attachmentSettings);
    }

    public Long confirmedTotal() {
        return ExpenseAmounts.confirmedTotal(info.type(), items);
    }

    public long unresolvedCount() {
        return items.stream().filter(item -> !item.reviewReasons().isEmpty()).map(Item::id).distinct().count();
    }

    public long unassignedCount() {
        Set<String> assigned = new HashSet<>(duplicateUnitIds);
        links.forEach(link -> assigned.add(link.unitId()));
        return sources.stream().filter(source -> !source.missing()).flatMap(source -> source.units().stream())
                .filter(unit -> !assigned.contains(unit.unitId())).count();
    }

    public record Source(String docId, String sha256, String relativePath, List<String> allPaths, boolean missing,
                         String error, List<PageResult> pages, List<Evidence> units) {
        public Source {
            allPaths = List.copyOf(allPaths);
            pages = List.copyOf(pages);
            units = List.copyOf(units);
        }
    }

    public record OverrideView(String targetId, String fieldKey, String action, String value,
                               List<String> sourceHashes, long updatedRevision) {
        public OverrideView {
            sourceHashes = List.copyOf(sourceHashes);
        }
    }

    public record AttachmentSetting(String docId, int firstPage, int lastPage, String caption,
                                    expenseassistant.output.ExportSnapshot.Layout layout, Long orderKey, String exclusionReason) {}
}
