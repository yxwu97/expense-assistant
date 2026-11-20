package expenseassistant.draft;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

import expenseassistant.casework.CaseException;
import expenseassistant.casework.CaseJobs;
import expenseassistant.casework.CaseService;
import expenseassistant.casework.CaseService.CaseInfo;
import expenseassistant.draft.CaseView.AttachmentSetting;
import expenseassistant.draft.CaseView.OverrideView;
import expenseassistant.draft.DraftProjection.ConfirmedAmount;
import expenseassistant.draft.DraftProjection.ManualItem;
import expenseassistant.draft.DraftProjection.ManualLink;
import expenseassistant.intake.ScanService;
import expenseassistant.intake.ScanService.PathIssue;
import expenseassistant.intake.ScanService.Progress;
import expenseassistant.intake.ScanService.ScanResult;
import expenseassistant.intake.TypeInference.CaseType;
import expenseassistant.matching.ExpenseAmounts;
import expenseassistant.matching.ExpenseAmounts.Component;
import expenseassistant.matching.ExpenseMatcher;
import expenseassistant.matching.ExpenseMatcher.Evidence;
import expenseassistant.matching.ExpenseMatcher.Item;
import expenseassistant.matching.ExpenseMatcher.Role;
import expenseassistant.output.ExportSnapshot;
import expenseassistant.output.ExportSnapshot.Layout;
import expenseassistant.output.TemplateArchive;
import expenseassistant.recognition.PageParser;
import expenseassistant.recognition.PageResult;
import expenseassistant.recognition.RecognitionService;
import expenseassistant.store.Database;

public final class DraftService {
    private static final Set<String> BASIC_FIELDS = Set.of("description", "startDate", "endDate");
    private static final Set<String> MILEAGE_FIELDS = Set.of("distanceKm", "direction");
    private final Database database;
    private final CaseJobs jobs;
    private final RecognitionService recognition;

    public DraftService(Database database, CaseJobs jobs, RecognitionService recognition) {
        this.database = database;
        this.jobs = jobs;
        this.recognition = recognition;
    }

    public sealed interface Change permits SetType, SetField, SetAmount, ClearAmount, RestoreField,
            SetAttachment, SetCaption, LinkEvidence, ReplaceTemplate, ExcludePath, AddExpense, AcceptChangedSource {}

    public record SetType(CaseType type) implements Change {}
    public record SetField(String itemId, String key, String value) implements Change {}
    public record SetAmount(String itemId, Component component, String currency, Long minor, String basis) implements Change {}
    public record ClearAmount(String itemId, Component component) implements Change {}
    public record RestoreField(String itemId, String key) implements Change {}
    public record SetAttachment(String docId, int firstPage, int lastPage, String caption, Layout layout,
                                Long orderKey, String exclusionReason) implements Change {}
    public record SetCaption(String docId, int pageNo, String caption) implements Change {}
    public record LinkEvidence(String itemId, String unitId, Role role, Component component, boolean transfer) implements Change {}
    public record ReplaceTemplate(String sha256) implements Change {}
    public record ExcludePath(String relativePath, String reason) implements Change {}
    public record AddExpense(String unitId, String description, LocalDate startDate, LocalDate endDate) implements Change {}
    public record AcceptChangedSource(String docId) implements Change {}

    public CaseView load(String caseId) {
        return database.read(connection -> read(connection, CaseService.load(connection, caseId)));
    }

    public CaseView loadForReview(String caseId) {
        var initial = load(caseId);
        if (pendingAmountRefreshCode(initial) == null) {
            return initial;
        }
        try (var job = jobs.begin(caseId)) {
            return job.commit(() -> database.transaction(connection -> {
                var info = CaseService.load(connection, caseId);
                var before = read(connection, info);
                if (pendingAmountRefreshCode(before) == null) {
                    return before;
                }
                CaseService.requireRevision(info, initial.info().revision());
                CaseService.requireNoActivePublication(connection, caseId);
                var store = new DraftStore(connection, caseId);
                var after = refresh(store, info, before);
                saveRevision(store, before, after, info.scanGeneration());
                return read(connection, CaseService.load(connection, caseId));
            }));
        }
    }

    public static String pendingAmountRefreshCode(CaseView view) {
        if (view.info().usesLegacyMileageRule()
                && view.items().stream().anyMatch(item -> "MILEAGE".equals(item.subtype()))) {
            return "MILEAGE_RULE_REFRESH_REQUIRED";
        }
        String rule = view.info().ruleVersion();
        return ("1".equals(rule) || "2".equals(rule)) && DraftProjection.usdRefreshChangesAmounts(view)
                ? "AMOUNT_RULE_REFRESH_REQUIRED" : null;
    }

    public static CaseView read(Connection connection, CaseInfo info) throws SQLException {
        var store = new DraftStore(connection, info.id());
        var sources = store.sources();
        var settings = store.settings();
        var available = DraftProjection.eligibleEvidence(sources, settings);
        var duplicates = new ExpenseMatcher().propose(info.id(), info.type(), available, Map.of()).duplicates();
        return DraftProjection.render(info, store.items(), sources, store.links(), store.claims(), duplicates,
                store.overrides(), settings, store.pathErrors());
    }

    public ExportSnapshot snapshot(String caseId, long expectedRevision) {
        return database.read(connection -> {
            var info = CaseService.load(connection, caseId);
            CaseService.requireRevision(info, expectedRevision);
            return ExportSnapshot.fromDraft(read(connection, info));
        });
    }

    public CaseView retryPage(String caseId, long expectedRevision, String docId, int pageNo) {
        try (var job = jobs.begin(caseId)) {
            CaseView view = load(caseId);
            CaseService.requireRevision(view.info(), expectedRevision);
            var source = view.sources().stream().filter(candidate ->
                    docId.equals(candidate.docId()) && !candidate.missing() && candidate.error() == null)
                    .findFirst().orElseThrow(() -> invalid("找不到本单有效凭证"));
            if (pageNo < 1 || pageNo > source.pages().size()) {
                throw invalid("凭证页码已失效");
            }
            Path input = view.info().folder().resolve(source.relativePath());
            job.onCancel(() -> recognition.cancel(job.id()));
            PageResult recognized;
            try {
                recognized = recognition.retryPage(input, source.sha256(), job.id(), pageNo, () -> {
                    job.checkActive();
                    return false;
                });
            } catch (RecognitionService.RecognitionFailure failure) {
                throw new CaseException(failure.code(), "无法重新识别所选原件页。", docId, failure);
            }
            var changed = new ArrayList<>(source.pages());
            changed.set(pageNo - 1, recognized);
            var lower = source.relativePath().toLowerCase(java.util.Locale.ROOT);
            var format = lower.endsWith(".pdf") ? ScanService.Format.PDF
                    : lower.endsWith(".png") ? ScanService.Format.PNG : ScanService.Format.JPEG;
            return job.commit(() -> database.transaction(connection -> {
                var info = CaseService.load(connection, caseId);
                CaseService.requireRevision(info, expectedRevision);
                CaseService.requireNoActivePublication(connection, caseId);
                var store = new DraftStore(connection, caseId);
                store.putSource(new ScanService.SourceFile(input, source.relativePath(), source.relativePath(),
                        source.sha256(), format), changed);
                var after = refresh(store, info, view);
                saveRevision(store, view, after, info.scanGeneration());
                return read(connection, CaseService.load(connection, caseId));
            }));
        }
    }

    public CaseView mergeScan(ScanResult scan) {
        return mergeScan(scan, progress -> {});
    }

    public CaseView mergeScan(ScanResult scan, Consumer<Progress> progress) {
        var ticket = scan.ticket();
        var job = ticket.job();
        job.checkActive();
        job.onCancel(() -> recognition.cancel(job.id()));
        Map<String, List<PageResult>> recognized = new HashMap<>();
        for (var source : load(ticket.caseInfo().id()).sources()) {
            if (!source.pages().isEmpty() && source.pages().stream().allMatch(page ->
                    page.parserVersion().equals(PageParser.VERSION) && page.warnings().stream()
                            .noneMatch(warning -> warning.startsWith("OCR_") || warning.equals("FIELDS_NEED_REVIEW")
                                    || warning.equals(DraftProjection.MILEAGE_NEEDS_REVIEW)))) {
                recognized.put(source.sha256(), source.pages());
            }
        }
        List<StagedSource> staged = new ArrayList<>();
        List<PathIssue> issues = new ArrayList<>(scan.issues());
        for (int index = 0; index < scan.files().size(); index++) {
            job.checkActive();
            var source = scan.files().get(index);
            try {
                var pages = recognized.get(source.sha256());
                if (pages == null) {
                    pages = recognition.extract(source.path(), source.sha256(), job.id(), PageParser.VERSION, () -> {
                        job.checkActive();
                        return false;
                    });
                    recognized.put(source.sha256(), pages);
                } else if (!source.sha256().equals(TemplateArchive.sha256(source.path()))) {
                    issues.add(new PathIssue(source.relativePath(), source.pathKey(), "SOURCE_CHANGED", false));
                    continue;
                }
                staged.add(new StagedSource(source, pages));
            } catch (RecognitionService.RecognitionFailure failure) {
                issues.add(new PathIssue(source.relativePath(), source.pathKey(),
                        "SOURCE_CHANGED".equals(failure.code()) ? "SOURCE_CHANGED" : "PATH_UNREADABLE", false));
            } catch (java.io.IOException disappeared) {
                issues.add(new PathIssue(source.relativePath(), source.pathKey(), "SOURCE_CHANGED", false));
            }
            progress.accept(new Progress(ticket.caseInfo().id(), job.id(), "RECOGNIZE",
                    index + 1, scan.files().size(), source.relativePath()));
        }
        return job.commit(() -> database.transaction(connection -> {
            var current = CaseService.load(connection, job.caseId());
            CaseService.requireRevision(current, ticket.caseInfo().revision());
            CaseService.requireNoActivePublication(connection, job.caseId());
            if (current.scanGeneration() + 1 != ticket.generation()) {
                throw new CaseException("STALE_REVISION", "扫描批次已更新，请重扫。");
            }
            var before = read(connection, current);
            var store = new DraftStore(connection, job.caseId());
            bindScan(store, staged, issues, ticket.generation(), current.revision() + 1);
            var after = refresh(store, current, before);
            saveRevision(store, before, after, ticket.generation());
            return read(connection, CaseService.load(connection, current.id()));
        }));
    }

    public CaseView apply(String caseId, long expectedRevision, List<Change> changes) {
        try (var job = jobs.begin(caseId)) {
            return job.commit(() -> database.transaction(connection -> {
                var current = CaseService.load(connection, caseId);
                CaseService.requireRevision(current, expectedRevision);
                CaseService.requireNoActivePublication(connection, caseId);
                var before = read(connection, current);
                var store = new DraftStore(connection, caseId);
                requireUniqueRequestedCharges(before, changes);
                for (Change change : changes) {
                    if (!(change instanceof SetAmount)) {
                        saveNonAmount(store, current, before, change);
                    }
                }
                var changed = CaseService.load(connection, caseId);
                var partial = changes.stream().anyMatch(SetAmount.class::isInstance)
                        ? refresh(store, changed, before) : before;
                for (Change change : changes) {
                    if (change instanceof SetAmount amount) {
                        saveAmount(store, changed, partial, amount);
                    }
                }
                var after = refresh(store, changed, before);
                verifyReviewValues(after, changes);
                saveRevision(store, before, after, current.scanGeneration());
                return read(connection, CaseService.load(connection, caseId));
            }));
        }
    }

    private static void saveRevision(DraftStore store, CaseView before, CaseView after, long scanGeneration)
            throws SQLException {
        var info = before.info();
        String rule = CaseService.ruleVersionFor(after.info().type());
        boolean changed = !rule.equals(info.ruleVersion()) || !sameBusiness(before, after);
        if (changed || scanGeneration != info.scanGeneration()) {
            store.update("UPDATE reimbursement_case SET revision=?,scan_generation=?,rule_version=? WHERE id=?",
                    info.revision() + (changed ? 1 : 0), scanGeneration, rule, info.id());
        }
    }

    private static void bindScan(DraftStore store, List<StagedSource> staged, List<PathIssue> issues,
                                 long generation, long revision) throws SQLException {
        var previouslyBound = store.activeBindings();
        Set<String> previouslyPresent = new HashSet<>();
        previouslyBound.stream().map(DraftStore.Binding::docId).filter(Objects::nonNull).forEach(previouslyPresent::add);
        Set<String> stillActive = new HashSet<>();
        for (var source : staged) {
            var file = source.file();
            String docId = store.putSource(file, source.pages());
            var priorAtPath = previouslyBound.stream().filter(path -> path.key().equals(file.pathKey()))
                    .map(DraftStore.Binding::docId).filter(Objects::nonNull).findFirst().orElse(null);
            if (priorAtPath != null && !docId.equals(priorAtPath) && !previouslyPresent.contains(docId)) {
                store.putOverride(new OverrideView(docId, DraftProjection.SOURCE_VERSION, "PENDING", priorAtPath,
                        List.of(file.sha256()), revision));
            }
            store.bind(docId, file.relativePath(), file.pathKey(), null, generation);
            stillActive.add(file.pathKey());
        }
        Set<String> explicitlyFlagged = new HashSet<>();
        for (PathIssue issue : issues) {
            explicitlyFlagged.add(issue.pathKey());
            String previousDoc = previouslyBound.stream().filter(path -> path.key().equals(issue.pathKey()))
                    .map(DraftStore.Binding::docId).filter(Objects::nonNull).findFirst().orElse(null);
            boolean temporarilyUnreadable = "PATH_UNREADABLE".equals(issue.code());
            if (!stillActive.contains(issue.pathKey())) {
                store.bind(temporarilyUnreadable ? previousDoc : null, issue.relativePath(), issue.pathKey(),
                        issue.code(), generation);
            }
        }
        for (var previous : previouslyBound) {
            if (stillActive.contains(previous.key()) || explicitlyFlagged.contains(previous.key())) {
                continue;
            }
            boolean protectedByFailure = previous.docId() != null && issues.stream().anyMatch(issue ->
                    "PATH_UNREADABLE".equals(issue.code()) && issue.subtree()
                            && (issue.pathKey().isEmpty() || previous.key().startsWith(issue.pathKey() + "/")));
            if (!protectedByFailure) {
                store.deactivate(previous, generation);
            }
        }
    }

    private static CaseView refresh(DraftStore store, CaseInfo info, CaseView previous) throws SQLException {
        var sources = store.sources();
        var overrides = store.overrides();
        var settings = store.settings();
        var projection = DraftProjection.recompute(info, previous, sources, overrides, settings);
        if (!projection.items().equals(store.items()) || !projection.links().equals(store.links())
                || !projection.claims().equals(store.claims())) {
            store.replaceItems(projection.items(), projection.links(), projection.claims());
        }
        return DraftProjection.render(info, projection.items(), sources, projection.links(), projection.claims(),
                projection.duplicates(), overrides, settings, store.pathErrors());
    }

    private void saveNonAmount(DraftStore store, CaseInfo info, CaseView before, Change change) throws SQLException {
        switch (change) {
            case SetType select -> {
                if (select.type() == null) {
                    throw invalid("请选择报销类型");
                }
                if (select.type() != info.type()) {
                    store.update("UPDATE reimbursement_case SET type=?,type_origin='MANUAL' WHERE id=?",
                            select.type().name(), info.id());
                }
            }
            case SetField field -> {
                Item item = item(before, field.itemId());
                requireEditableField(item, field.key());
                checkField(info, field.key(), field.value());
                var old = override(store, item.id(), field.key());
                if (field.value() != null && old == null && Objects.equals(field.value(), fieldValue(item, field.key()))) {
                    break;
                }
                putOverride(store, item.id(), field.key(), field.value(), List.of(), info.revision() + 1);
            }
            case RestoreField restore -> {
                var item = item(before, restore.itemId());
                requireEditableField(item, restore.key());
                if ((DraftProjection.AMOUNT + Component.FUEL).equals(restore.key())) {
                    requireEditableAmount(item, Component.FUEL);
                }
                if (restore.key().startsWith(DraftProjection.LINK) || restore.key().startsWith(DraftProjection.PATH)
                        || DraftProjection.CREATE.equals(restore.key())) {
                    throw invalid("请使用凭证关联或路径操作");
                }
                store.removeOverride(restore.itemId(), restore.key());
            }
            case ClearAmount clear -> {
                var item = item(before, clear.itemId());
                requireEditableAmount(item, clear.component());
                if (clear.component() == null) {
                    throw invalid("请选择需清空的分项");
                }
                putOverride(store, clear.itemId(), DraftProjection.AMOUNT + clear.component(),
                        DraftStore.encode(new ConfirmedAmount(null, "人工留空")), List.of(), info.revision() + 1);
            }
            case SetAttachment attachment -> saveAttachment(store, before, new AttachmentSetting(attachment.docId(),
                    attachment.firstPage(), attachment.lastPage(), attachment.caption(), attachment.layout(),
                    attachment.orderKey(), attachment.exclusionReason()));
            case SetCaption caption -> {
                var current = DraftProjection.setting(store.settings(), caption.docId(), caption.pageNo());
                var display = before.attachments().stream().filter(page -> page.docId().equals(caption.docId())
                        && page.sourcePage() == caption.pageNo()).findFirst().orElse(null);
                if (current == null && display != null && Objects.equals(caption.caption(), display.caption())) {
                    break;
                }
                saveAttachment(store, before, new AttachmentSetting(caption.docId(), caption.pageNo(), caption.pageNo(),
                        caption.caption(), current == null ? null : current.layout(), current == null ? null : current.orderKey(),
                        current == null ? null : current.exclusionReason()));
            }
            case LinkEvidence attach -> saveLink(store, info, before, attach);
            case ReplaceTemplate replace -> {
                if (!Objects.equals(info.templateHash(), replace.sha256())) {
                    new TemplateArchive(database.file().getParent()).locate(replace.sha256());
                    store.update("UPDATE reimbursement_case SET template_hash=? WHERE id=?", replace.sha256(), info.id());
                }
            }
            case ExcludePath exclude -> {
                if (exclude.relativePath() == null || before.unavailable().stream().noneMatch(unavailable ->
                        unavailable.relativePath().equals(exclude.relativePath()) && unavailable.sourcePage() == 0)) {
                    throw invalid("找不到待处理的凭证路径");
                }
                String key = DraftProjection.PATH + exclude.relativePath();
                if (exclude.reason() == null) {
                    store.removeOverride(info.id(), key);
                } else if (exclude.reason().isBlank()) {
                    throw invalid("请填写排除原因");
                } else {
                    putOverride(store, info.id(), key, exclude.reason(), List.of(), info.revision() + 1);
                }
            }
            case AddExpense manual -> {
                var evidence = units(before, false).get(manual.unitId());
                if (evidence == null || manual.description() == null || manual.description().isBlank()
                        || evidence.pages().stream().anyMatch(page -> !page.renderable())) {
                    throw invalid("请选有效凭证并填写费用说明");
                }
                String id = ExpenseMatcher.stableId(info.id(), "manual:" + evidence.unitId());
                if (override(store, id, DraftProjection.CREATE) != null) {
                    break;
                }
                if (before.links().stream().anyMatch(link -> link.unitId().equals(evidence.unitId()))) {
                    throw invalid("该凭证已归属费用，请从现有费用继续核对");
                }
                putOverride(store, id, DraftProjection.CREATE, DraftStore.encode(new ManualItem(evidence.unitId(),
                        manual.description(), Objects.toString(manual.startDate(), null),
                        Objects.toString(manual.endDate(), null))), List.of(evidence.sha256()), info.revision() + 1);
            }
            case AcceptChangedSource accept -> {
                if (before.sources().stream().noneMatch(source -> !source.missing() && source.docId().equals(accept.docId()))) {
                    throw invalid("找不到本单的现行凭证");
                }
                acceptSource(store, accept.docId(), info.revision() + 1);
            }
            case SetAmount ignored -> throw new IllegalStateException("金额应在凭证关联保存后更新");
        }
    }

    private static void saveAttachment(DraftStore store, CaseView before, AttachmentSetting attachment)
            throws SQLException {
        var source = before.sources().stream().filter(doc -> doc.docId().equals(attachment.docId())).findFirst()
                .orElseThrow(() -> invalid("找不到本单凭证"));
        if (attachment.firstPage() < 1 || attachment.lastPage() < attachment.firstPage()
                || attachment.lastPage() > source.pages().size()) {
            throw invalid("凭证页码超出范围");
        }
        if (attachment.caption() != null && attachment.caption().codePointCount(0, attachment.caption().length()) > 90) {
            throw new CaseException("LAYOUT_OVERFLOW", "附件说明最多 90 个字符。", source.docId(), null);
        }
        if (attachment.exclusionReason() != null && attachment.exclusionReason().isBlank()) {
            throw invalid("请填写排除原因");
        }
        if (attachment.layout() == Layout.FULL_PAGE && source.pages().stream().anyMatch(page ->
                page.pageNo() >= attachment.firstPage() && page.pageNo() <= attachment.lastPage()
                        && page.kind() == PageResult.Kind.INVOICE)) {
            throw invalid("发票使用双格版式");
        }
        var existing = store.settings().stream().filter(row -> row.docId().equals(attachment.docId())
                && row.firstPage() == attachment.firstPage() && row.lastPage() == attachment.lastPage())
                .findFirst().orElse(null);
        if (existing == null && attachment.caption() == null && attachment.orderKey() == null
                && attachment.exclusionReason() == null
                && (attachment.layout() == null || attachment.layout() == Layout.TWO_PER_PAGE)) {
            return;
        }
        if (!Objects.equals(existing, attachment)) {
            store.putSetting(attachment);
        }
    }

    private static void saveLink(DraftStore store, CaseInfo info, CaseView before, LinkEvidence change)
            throws SQLException {
        var item = item(before, change.itemId());
        var source = units(before, false).get(change.unitId());
        if (source == null) {
            throw invalid("找不到本单有效凭证");
        }
        if (change.role() == Role.CHARGE || change.role() == Role.REPLACEMENT) {
            requireEditableAmount(item, change.component());
            if (change.component() == null || !ExpenseAmounts.allowed(info.type(), change.component())
                    || source.invoiceKey() == null || source.chargeMinor() == null) {
                throw invalid("请选择本单可用的计费分项和有可靠金额的发票");
            }
            var owner = before.claims().stream().filter(claim -> source.invoiceKey().equals(claim.chargeKey())).findFirst();
            if (owner.isPresent() && !owner.get().itemId().equals(change.itemId()) && !change.transfer()) {
                throw new CaseException("CHARGE_ALREADY_USED", "该发票已被另一笔费用占用。",
                        owner.get().itemId(), null);
            }
        }
        var value = DraftStore.encode(new ManualLink(change.role(), change.component(), change.transfer()));
        putOverride(store, change.itemId(), DraftProjection.LINK + change.unitId(), value,
                List.of(source.sha256()), info.revision() + 1);
    }

    private static void requireUniqueRequestedCharges(CaseView before, List<Change> changes) {
        var evidence = units(before, false);
        Map<String, String> requested = new HashMap<>();
        for (Change command : changes) {
            if (command instanceof LinkEvidence link && (link.role() == Role.CHARGE || link.role() == Role.REPLACEMENT)) {
                var source = evidence.get(link.unitId());
                if (source != null && source.invoiceKey() != null) {
                    String current = requested.putIfAbsent(source.invoiceKey(), link.itemId());
                    if (current != null && !current.equals(link.itemId())) {
                        throw new CaseException("CHARGE_ALREADY_USED", "同一批操作不能向多笔费用分配同一张发票。",
                                current, null);
                    }
                }
            }
        }
    }

    private static void saveAmount(DraftStore store, CaseInfo info, CaseView partial, SetAmount change)
            throws SQLException {
        var item = item(partial, change.itemId());
        requireEditableAmount(item, change.component());
        if (!"CNY".equals(change.currency()) || change.component() == null
                || !ExpenseAmounts.allowed(info.type(), change.component()) || change.minor() != null && change.minor() < 0
                || change.minor() != null && (change.basis() == null || change.basis().isBlank())) {
            throw invalid("分项金额须为非负人民币分，并注明核对依据");
        }
        String key = DraftProjection.AMOUNT + change.component().name();
        var existing = override(store, item.id(), key);
        var automatic = item.amounts().get(change.component());
        if (existing == null && change.minor() != null && automatic != null
                && change.minor().equals(automatic.confirmedMinor())) {
            return;
        }
        List<String> sources = DraftProjection.hashes(item.id(), partial.links(), units(partial, false))
                .stream().sorted().toList();
        String basis = change.minor() == null ? "人工留空" : change.basis();
        putOverride(store, item.id(), key, DraftStore.encode(new ConfirmedAmount(change.minor(), basis)),
                sources, info.revision() + 1);
        if (change.minor() != null) {
            var linkedUnits = units(partial, false);
            for (var link : partial.links()) {
                var unit = linkedUnits.get(link.unitId());
                if (item.id().equals(link.itemId()) && unit != null) {
                    acceptSource(store, unit.docId(), info.revision() + 1);
                }
            }
        }
    }

    private static void acceptSource(DraftStore store, String docId, long revision) throws SQLException {
        var pending = override(store, docId, DraftProjection.SOURCE_VERSION);
        if (pending != null && "PENDING".equals(pending.action())) {
            store.putOverride(new OverrideView(docId, pending.fieldKey(), "ACCEPTED", pending.value(),
                    pending.sourceHashes(), revision));
        }
    }

    private static OverrideView override(DraftStore store, String id, String key) throws SQLException {
        return store.overrides().stream().filter(entry -> entry.targetId().equals(id) && entry.fieldKey().equals(key))
                .findFirst().orElse(null);
    }

    private static void putOverride(DraftStore store, String id, String key, String value, List<String> hashes,
                                    long revision) throws SQLException {
        var old = override(store, id, key);
        if (old == null || !Objects.equals(old.value(), value) || !old.sourceHashes().equals(hashes)) {
            store.putOverride(new OverrideView(id, key, value == null ? "CLEAR" : "SET", value, hashes, revision));
        }
    }

    private static Item item(CaseView view, String id) {
        return view.items().stream().filter(item -> item.id().equals(id)).findFirst()
                .orElseThrow(() -> invalid("找不到本单费用"));
    }

    private static Map<String, Evidence> units(CaseView view, boolean includeMissing) {
        var units = new LinkedHashMap<String, Evidence>();
        view.sources().stream().filter(source -> includeMissing || !source.missing())
                .flatMap(source -> source.units().stream()).forEach(unit -> units.put(unit.unitId(), unit));
        return units;
    }

    private static String fieldValue(Item item, String key) {
        return switch (key) {
            case "startDate" -> Objects.toString(item.startDate(), null);
            case "endDate" -> Objects.toString(item.endDate(), null);
            case "description" -> item.description();
            default -> item.fields().get(key);
        };
    }

    private static void checkField(CaseInfo info, String key, String value) {
        if (key == null) {
            throw invalid("请选择核对字段");
        }
        Set<String> extra = switch (info.type() == null ? CaseType.OTHER : info.type()) {
            case TRAVEL -> Set.of("route", "origin", "destination", "vehicle", "nights", "note");
            case ENTERTAINMENT -> Set.of("customer", "agenda", "detail", "note");
            case PRIVATE_CAR -> Set.of("route", "origin", "destination", "direction", "rawDistanceKm", "distanceKm", "note");
            case TAXI -> Set.of("route", "people", "note");
            case OTHER -> Set.of("location", "detail", "note");
        };
        if (!BASIC_FIELDS.contains(key) && !extra.contains(key)) {
            throw invalid("该字段不适用于当前类型");
        }
        if (value == null) {
            return;
        }
        if ("rawDistanceKm".equals(key) || "distanceKm".equals(key)) {
            checkMileageField(key, value);
            return;
        }
        try {
            if (key.endsWith("Date")) {
                LocalDate.parse(value);
            } else if (Set.of("nights", "people").contains(key)
                    && new BigDecimal(value).signum() < 0) {
                throw new NumberFormatException("负数");
            } else if ("direction".equals(key) && !Set.of("单程", "往返").contains(value)) {
                throw new IllegalArgumentException("方向");
            }
        } catch (IllegalArgumentException | java.time.DateTimeException invalid) {
            throw invalid("字段格式有误，请修改后保存");
        }
    }

    private static void checkMileageField(String key, String value) {
        try {
            var distance = new BigDecimal(value);
            if (distance.signum() < 0 || "distanceKm".equals(key) && ExpenseAmounts.mileageFuel(distance).outOfRange()) {
                throw mileageOutOfRange();
            }
        } catch (NumberFormatException invalid) {
            throw mileageOutOfRange();
        }
    }

    private static void requireEditableAmount(Item item, Component component) {
        if (ExpenseAmounts.derivedMileageFuel(item.subtype(), component)) {
            throw invalid("里程油费按确认公里数计算，请修改确认公里数。");
        }
    }

    private static void requireEditableField(Item item, String key) {
        if ("MILEAGE".equals(item.subtype()) && "rawDistanceKm".equals(key)) {
            throw invalid("截图里程来自识别结果，请修改确认公里数。");
        }
    }

    private static CaseException mileageOutOfRange() {
        return new CaseException(DraftProjection.MILEAGE_OUT_OF_RANGE, "公里数超出清单可用精度，请核对里程");
    }

    private static void verifyReviewValues(CaseView after, List<Change> changes) {
        var checkedMileage = new HashSet<String>();
        for (var change : changes) {
            if (change instanceof SetField field && MILEAGE_FIELDS.contains(field.key())) {
                checkedMileage.add(field.itemId());
            } else if (change instanceof RestoreField field && MILEAGE_FIELDS.contains(field.key())) {
                checkedMileage.add(field.itemId());
            }
        }
        var subsidies = new ArrayList<DayClaim>();
        for (Item item : after.items()) {
            if (checkedMileage.contains(item.id()) && item.reviewReasons().contains(DraftProjection.MILEAGE_OUT_OF_RANGE)) {
                throw mileageOutOfRange();
            }
            if (item.startDate() != null && item.endDate() != null && item.endDate().isBefore(item.startDate())) {
                throw invalid("费用的结束日期早于起始日期");
            }
            if (after.info().type() != CaseType.TRAVEL) {
                continue;
            }
            for (var component : List.of(Component.TRAFFIC_SUBSIDY, Component.MEAL_SUBSIDY, Component.NO_STAY_SUBSIDY)) {
                var amount = item.amounts().get(component);
                if (amount == null || amount.confirmedMinor() == null) {
                    continue;
                }
                if (item.startDate() == null) {
                    throw invalid("确认差旅补贴前请补录对应日期");
                }
                var end = item.endDate() == null ? item.startDate() : item.endDate();
                var claim = new DayClaim(component, item.startDate(), end);
                if (subsidies.stream().anyMatch(other -> other.component() == component
                        && !other.end().isBefore(claim.start()) && !claim.end().isBefore(other.start()))) {
                    throw invalid("同日的同类补贴已在其他费用行确认");
                }
                subsidies.add(claim);
            }
        }
    }

    private static CaseException invalid(String text) {
        return new CaseException("INVALID_CHANGE", text);
    }

    private static boolean sameBusiness(CaseView before, CaseView after) {
        return before.info().type() == after.info().type()
                && Objects.equals(before.info().templateHash(), after.info().templateHash())
                && before.items().equals(after.items()) && before.sources().equals(after.sources())
                && before.links().equals(after.links()) && before.claims().equals(after.claims())
                && before.attachments().equals(after.attachments()) && before.unavailable().equals(after.unavailable())
                && before.overrides().equals(after.overrides()) && before.attachmentSettings().equals(after.attachmentSettings());
    }

    private record StagedSource(ScanService.SourceFile file, List<PageResult> pages) {}
    private record DayClaim(Component component, LocalDate start, LocalDate end) {}
}
