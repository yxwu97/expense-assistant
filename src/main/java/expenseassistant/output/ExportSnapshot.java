package expenseassistant.output;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import expenseassistant.draft.CaseView;
import expenseassistant.draft.DraftService;
import expenseassistant.casework.CaseException;
import expenseassistant.intake.TypeInference.CaseType;
import expenseassistant.matching.ExpenseAmounts;
import expenseassistant.matching.ExpenseAmounts.Component;
import expenseassistant.matching.ExpenseMatcher.Claim;
import expenseassistant.matching.ExpenseMatcher.Evidence;
import expenseassistant.matching.ExpenseMatcher.Role;
import expenseassistant.recognition.AttachmentImages.CropMode;

public record ExportSnapshot(String caseId, String caseName, String caseFolder, CaseType type, long revision,
                             String templateHash, String ruleVersion, List<ExpenseRow> rows,
                             List<AttachmentPage> pages, List<UnavailableSource> unavailable, Audit audit) {
    public ExportSnapshot {
        rows = List.copyOf(rows);
        pages = List.copyOf(pages);
        unavailable = List.copyOf(unavailable);
        audit = audit == null ? Audit.EMPTY : audit;
    }

    public ExportSnapshot(String caseId, String caseName, String caseFolder, CaseType type, long revision,
                          String templateHash, String ruleVersion, List<ExpenseRow> rows,
                          List<AttachmentPage> pages, List<UnavailableSource> unavailable) {
        this(caseId, caseName, caseFolder, type, revision, templateHash, ruleVersion, rows, pages, unavailable, Audit.EMPTY);
    }

    public static ExportSnapshot fromDraft(CaseView view) {
        var info = view.info();
        String pending = DraftService.pendingAmountRefreshCode(view);
        if (pending != null) {
            String rule = "MILEAGE_RULE_REFRESH_REQUIRED".equals(pending) ? "私车公用计费规则" : "美元换算金额";
            throw new CaseException(pending, "请先从工作台打开报销目录核对，更新" + rule + "。");
        }
        var rows = view.items().stream()
                .map(item -> new ExpenseRow(item.id(), item.subtype(), item.startDate(), item.endDate(),
                        item.description(), item.fields(), ExpenseAmounts.project(info.type(), item)))
                .toList();
        if (info.type() == CaseType.TRAVEL) {
            try {
                rows = TravelSummaryRows.summarize(view, rows);
            } catch (ArithmeticException overflow) {
                throw new CaseException("AMOUNT_OUT_OF_RANGE", "差旅费用汇总金额超出范围，请核对已确认金额", overflow);
            }
        }
        var unavailable = new java.util.ArrayList<>(view.unavailable());
        view.items().stream().filter(item -> item.reviewReasons().contains("UNMAPPED_CONFIRMATION"))
                .forEach(item -> unavailable.add(new UnavailableSource(item.id(), item.description(), 0, "UNMAPPED_CONFIRMATION")));
        return new ExportSnapshot(info.id(), info.folder().getFileName().toString(), info.folder().toString(), info.type(),
                info.revision(), info.templateHash(), info.ruleVersion(), rows, view.attachments(), unavailable, audit(view));
    }

    private static Audit audit(CaseView view) {
        var docs = view.sources().stream().collect(Collectors.toMap(CaseView.Source::docId, Function.identity()));
        var units = view.sources().stream().flatMap(source -> source.units().stream())
                .collect(Collectors.toMap(Evidence::unitId, Function.identity()));
        var exclusions = new ArrayList<Exclusion>();
        view.attachmentSettings().stream().filter(setting -> setting.exclusionReason() != null).forEach(setting -> {
            var source = docs.get(setting.docId());
            exclusions.add(new Exclusion(setting.docId(), source == null ? null : source.sha256(),
                    source == null ? null : source.relativePath(), setting.firstPage(), setting.lastPage(),
                    setting.exclusionReason()));
        });
        String pathPrefix = "PATH:";
        view.overrides().stream().filter(override -> override.fieldKey().startsWith(pathPrefix)
                && "SET".equals(override.action()) && override.value() != null).forEach(override ->
                exclusions.add(new Exclusion(null, null, override.fieldKey().substring(pathPrefix.length()), 0, 0, override.value())));
        var amounts = view.items().stream().flatMap(item -> item.amounts().entrySet().stream()
                .sorted(Map.Entry.comparingByKey()).map(entry -> {
                    var value = entry.getValue();
                    return new AmountBasis(item.id(), entry.getKey(), value.confirmedMinor(), value.faceValue(),
                            value.originalCurrency(), value.basis());
                })).toList();
        var evidence = view.links().stream().map(link -> {
            var unit = units.get(link.unitId());
            return new EvidenceBasis(link.itemId(), link.unitId(), unit == null ? null : unit.docId(),
                    unit == null ? null : unit.sha256(), unit == null ? null : unit.relativePath(),
                    unit == null ? 0 : unit.pages().getFirst().pageNo(), unit == null ? 0 : unit.pages().getLast().pageNo(),
                    link.role(), link.basis());
        }).toList();
        return new Audit(exclusions, amounts, evidence, view.claims());
    }

    public record ExpenseRow(String itemId, String subtype, LocalDate startDate, LocalDate endDate, String description,
                             Map<String, String> fields, Map<String, Long> amounts) {
        public ExpenseRow {
            fields = Map.copyOf(fields);
            amounts = Map.copyOf(amounts);
        }
    }

    public enum Layout { TWO_PER_PAGE, FULL_PAGE }

    public record AttachmentPage(String docId, String sha256, String relativePath, int sourcePage, List<String> itemIds,
                                 Role role, String caption, Layout layout, CropMode cropMode) {
        public AttachmentPage {
            itemIds = List.copyOf(itemIds);
            cropMode = cropMode == null ? CropMode.NONE : cropMode;
        }

        public AttachmentPage(String docId, String sha256, String relativePath, int sourcePage, List<String> itemIds,
                              Role role, String caption, Layout layout) {
            this(docId, sha256, relativePath, sourcePage, itemIds, role, caption, layout, CropMode.NONE);
        }
    }

    public record UnavailableSource(String docId, String relativePath, int sourcePage, String code) {}

    public record Exclusion(String docId, String sha256, String relativePath, int firstPage, int lastPage, String reason) {}

    public record AmountBasis(String itemId, Component component, Long confirmedMinor, String faceValue,
                              String currency, String basis) {}

    public record EvidenceBasis(String itemId, String unitId, String docId, String sha256, String relativePath,
                                int firstPage, int lastPage, Role role, String basis) {}

    public record Audit(List<Exclusion> exclusions, List<AmountBasis> amounts, List<EvidenceBasis> evidence,
                        List<Claim> claims) {
        private static final Audit EMPTY = new Audit(List.of(), List.of(), List.of(), List.of());

        public Audit {
            exclusions = exclusions == null ? List.of() : List.copyOf(exclusions);
            amounts = amounts == null ? List.of() : List.copyOf(amounts);
            evidence = evidence == null ? List.of() : List.copyOf(evidence);
            claims = claims == null ? List.of() : List.copyOf(claims);
        }
    }
}
