package expenseassistant.ui;

import expenseassistant.casework.CaseException;
import expenseassistant.casework.CaseService;
import expenseassistant.draft.CaseView;
import expenseassistant.draft.DraftService;
import expenseassistant.intake.ScanService;
import expenseassistant.intake.TypeInference.CaseType;
import expenseassistant.matching.ExpenseAmounts;
import expenseassistant.matching.ExpenseAmounts.Component;
import expenseassistant.matching.ExpenseMatcher.Evidence;
import expenseassistant.matching.ExpenseMatcher.Item;
import expenseassistant.matching.ExpenseMatcher.Link;
import expenseassistant.matching.ExpenseMatcher.Role;
import expenseassistant.output.AttachmentLayout;
import expenseassistant.output.ExportService;
import expenseassistant.output.ExportService.FileKind;
import expenseassistant.output.ExportService.Record;
import expenseassistant.output.ExportService.State;
import expenseassistant.output.ExportSnapshot.AttachmentPage;
import expenseassistant.output.ExportSnapshot.UnavailableSource;
import expenseassistant.output.TemplateArchive;
import expenseassistant.output.TemplateCatalog;
import expenseassistant.platform.AppVersion;
import expenseassistant.platform.WpsLauncher;
import expenseassistant.recognition.AttachmentImages;
import expenseassistant.recognition.AttachmentImages.CropMode;
import expenseassistant.recognition.PageResult;
import expenseassistant.store.StoreException;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.TitledPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import javafx.util.StringConverter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import java.util.function.Function;

public final class Workbench extends ScrollPane implements AutoCloseable {
    private final CaseService cases;
    private final ScanService scans;
    private final DraftService drafts;
    private final WpsLauncher wps;
    private final ExportService exports;
    private final TemplateArchive archive;
    private final TemplateCatalog templates;
    private final EvidenceEditor editor = new EvidenceEditor();
    private final ExecutorService operations = Executors.newSingleThreadExecutor(Thread.ofVirtual().name("expense-workbench").factory());
    private final ExecutorService previews = Executors.newSingleThreadExecutor(Thread.ofVirtual().name("expense-preview").factory());
    private final ExecutorService publications = Executors.newSingleThreadExecutor(Thread.ofVirtual().name("expense-output-status").factory());

    private final VBox content = new VBox(14);
    private final SplitPane review = new SplitPane();
    private final Label appVersion = label("expense-app-version", AppVersion.displayText());
    private final Label folderName = label("expense-case-path", "请选择报销目录");
    private final Label notice = label("expense-notice", "");
    private final Label progressText = label("expense-progress-text", "选择存放凭证的目录后自动扫描。");
    private final ProgressBar progress = new ProgressBar();
    private final Button choose = button("expense-choose", "选择目录");
    private final Button rescan = button("expense-rescan", "重新扫描");
    private final Button cancel = button("expense-cancel", "取消扫描");
    private final ComboBox<CaseType> type = new ComboBox<>(FXCollections.observableArrayList(CaseType.values()));

    private final TableView<Item> items = new TableView<>();
    private final Label count = label("expense-count", "0 笔");
    private final Label total = label("expense-total", "—");
    private final Label pending = label("expense-pending", "");
    private final Label policy = label("expense-policy", "");
    private final Button edit = button("expense-edit", "编辑所选费用");
    private final Button compare = button("expense-compare-edit", "查看未提交编辑");

    private final ToggleButton unassigned = new ToggleButton("待处理凭证");
    private final ComboBox<Relation> evidence = new ComboBox<>();
    private final ComboBox<PageResult> page = new ComboBox<>();
    private final Label relationDetail = label("expense-evidence-detail", "");
    private final Label caption = label("expense-caption", "");
    private final Label previewStatus = label("expense-preview-status", "");
    private final VBox evidenceValues = new VBox(4);
    private final ImageView preview = new ImageView();
    private final Button openSource = button("expense-open-source", "用 WPS 打开原件");
    private final Button link = button("expense-link", "关联或解除");
    private final Button addCopy = button("expense-add-copy", "加入打印附件");
    private final Button addExpense = button("expense-add-item", "从凭证新增费用");
    private final Button editAttachment = button("expense-attachment-edit", "编辑附件");
    private final Button retryPage = button("expense-retry-page", "重试本页识别");
    private final Button acceptSource = button("expense-accept-source", "接受变更后的原件");
    private final ListView<PrintPlacement> printList = new ListView<>();
    private final TitledPane printOrder = new TitledPane();
    private final Label outputStatus = label("expense-export-status", "尚未生成");
    private final Label outputRoot = label("expense-output-root", "");
    private final Label templateName = label("expense-template", "");
    private final Label outputHint = label("expense-export-hint", "");
    private final Button generate = button("expense-generate", "生成清单和附件");
    private final Button chooseOutput = button("expense-output-choose", "选择输出位置");
    private final Button openExcel = button("expense-open-excel", "用 WPS 打开");
    private final Button openWord = button("expense-open-word", "用 WPS 打开");
    private final Button openDirectory = button("expense-output-folder", "打开成品目录");
    private final Button history = button("expense-history", "生成历史");
    private final Button retryOutput = button("expense-export-retry", "重试生成");
    private final Button repairTemplate = button("expense-template-repair", "处理模板");

    private CaseView view;
    private Operation active;
    private PendingEdit staleEdit;
    private Future<?> previewWork;
    private long previewVersion;
    private Future<?> outputWork;
    private long outputVersion;
    private OutputState outputState;
    private Path selectedOutput;
    private boolean rendering;
    private boolean closed;

    private static final class Operation {
        private final boolean scan;
        private volatile boolean cancelled;
        private volatile String caseId;
        private volatile String jobId;

        private Operation(boolean scan) { this.scan = scan; }
    }

    private record ScanOutcome(CaseView view, boolean cancelled, RuntimeException failure) {}
    private record PendingEdit(String caseId, EvidenceEditor.Edit edit) {}
    private record SaveOutcome(CaseView view, EvidenceEditor.Edit conflict) {}
    private record PrintPlacement(AttachmentPage page, String position) {}
    private record OutputState(List<Record> history, Path preferredRoot, Record current,
                               String excelIssue, String wordIssue, String templateIssue) {
        private Record newest() { return history.isEmpty() ? null : history.getFirst(); }
    }
    private record PreviewPixels(int width, int height, int[] pixels) {
        private Image image() {
            var result = new WritableImage(width, height);
            result.getPixelWriter().setPixels(0, 0, width, height, PixelFormat.getIntArgbInstance(), pixels, 0, width);
            return result;
        }
    }
    private record Relation(CaseView.Source source, Evidence unit, Link link, UnavailableSource issue) {
        private String path() { return source == null ? issue.relativePath() : source.relativePath(); }
        private List<PageResult> pages() { return unit != null ? unit.pages() : source == null ? List.of() : source.pages(); }
        private boolean missing() { return source == null || source.missing(); }
        private String title() {
            return path() + (link != null ? " · " + roleLabel(link.role()) : issue != null ? " · " + issueLabel(issue.code()) : "");
        }
    }

    public Workbench(CaseService cases, ScanService scans, DraftService drafts, WpsLauncher wps,
                     ExportService exports, TemplateArchive archive, TemplateCatalog templates) {
        this.cases = cases;
        this.scans = scans;
        this.drafts = drafts;
        this.wps = wps;
        this.exports = exports;
        this.archive = archive;
        this.templates = templates;
        setId("expense-workbench");
        getStyleClass().add("workbench");
        var stylesheet = Workbench.class.getResource("/ui/workbench.css");
        if (stylesheet != null) getStylesheets().add(stylesheet.toExternalForm());
        setFitToWidth(true);
        setHbarPolicy(ScrollBarPolicy.NEVER);
        setContent(content);
        content.setPadding(new Insets(20));
        content.getStyleClass().add("workbench-content");
        content.getChildren().addAll(heading(), importPane(), reviewPane(), printPane(), outputPane());
        actions();
    }

    private Node heading() {
        var mark = new ImageView();
        mark.getStyleClass().add("app-mark");
        mark.setFitWidth(32);
        mark.setPreserveRatio(true);
        var title = label("expense-title", "报销助手");
        title.getStyleClass().add("app-title");
        appVersion.getStyleClass().add("quiet");
        return row(mark, title, spacer(), appVersion);
    }

    private Node importPane() {
        folderName.getStyleClass().add("folder-title");
        type.setId("expense-type");
        type.setPromptText("请选择报销类型");
        type.setConverter(new StringConverter<>() {
            @Override public String toString(CaseType value) { return value == null ? "" : value.label(); }
            @Override public CaseType fromString(String text) { return null; }
        });
        type.setAccessibleText("报销类型");
        type.setOnAction(event -> changeType());
        choose.setOnAction(event -> chooseCaseFolder());
        rescan.setOnAction(event -> {
            if (view != null) selectCase(view.info().folder());
        });
        cancel.setOnAction(event -> cancelScan());
        progress.setMaxWidth(Double.MAX_VALUE);
        progress.setId("expense-progress");
        show(progress, false);
        hideEmpty(notice);
        progressText.getStyleClass().add("quiet");
        notice.getStyleClass().add("warning");
        var chooseActions = new FlowPane(8, 7, choose, rescan, cancel, type);
        return card(folderName, chooseActions, progress, progressText, notice);
    }

    private Node reviewPane() {
        review.setId("expense-review");
        review.getStyleClass().add("review-pane");
        review.getItems().addAll(expenses(), evidence());
        review.setDividerPositions(0.54);
        viewportBoundsProperty().addListener((property, old, bounds) -> {
            Orientation layout = bounds.getWidth() > 0 && bounds.getWidth() < 810 ? Orientation.VERTICAL : Orientation.HORIZONTAL;
            if (review.getOrientation() != layout) {
                review.setOrientation(layout);
                review.setDividerPositions(0.54);
            }
        });
        return review;
    }

    private Node expenses() {
        items.setId("expense-rows");
        items.setPlaceholder(new Label("暂无费用明细"));
        items.setPrefHeight(268);
        items.setMinWidth(0);
        items.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        items.getColumns().add(column("费用", Item::description, 128));
        items.getColumns().add(column("业务日期", Workbench::dates, 114));
        items.getColumns().add(column("人民币", item -> confirmedCurrency(view.info().type(), List.of(item)), 76));
        items.getColumns().add(column("状态", this::reviewState, 78));
        items.getSelectionModel().selectedItemProperty().addListener((observable, previous, selected) -> {
            if (!rendering) rebuildEvidence();
        });
        items.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ENTER && !edit.isDisabled()) {
                edit();
                event.consume();
            }
        });
        items.setRowFactory(table -> {
            var itemRow = new javafx.scene.control.TableRow<Item>();
            itemRow.setOnMouseClicked(event -> {
                if (event.getClickCount() == 2 && !itemRow.isEmpty() && !edit.isDisabled()) edit();
            });
            return itemRow;
        });
        edit.setOnAction(event -> edit());
        compare.setOnAction(event -> compareEdit());
        total.getStyleClass().add("total-value");
        pending.getStyleClass().add("quiet");
        policy.getStyleClass().add("warning");
        hideEmpty(policy);
        return card(row(section("费用明细"), spacer(), count), items,
                row(new Label("确认合计"), total), pending, policy, new FlowPane(7, 7, edit, compare));
    }

    private Node evidence() {
        evidence.setId("expense-document");
        evidence.setPromptText("选择凭证");
        evidence.setAccessibleText("选择关联或待处理凭证");
        evidence.setMaxWidth(Double.MAX_VALUE);
        evidence.setConverter(new StringConverter<>() {
            @Override public String toString(Relation value) { return value == null ? "" : value.title(); }
            @Override public Relation fromString(String text) { return null; }
        });
        evidence.valueProperty().addListener((observable, previous, selected) -> {
            if (!rendering) selectRelation(null);
        });
        page.setId("expense-page");
        page.setAccessibleText("凭证页码");
        page.setConverter(new StringConverter<>() {
            @Override public String toString(PageResult value) { return value == null ? "" : "第 " + value.pageNo() + " 页"; }
            @Override public PageResult fromString(String text) { return null; }
        });
        page.valueProperty().addListener((observable, previous, selected) -> {
            if (!rendering) evidenceDetail();
        });
        unassigned.setId("expense-unassigned");
        unassigned.setOnAction(event -> rebuildEvidence());
        openSource.setOnAction(event -> openSource());
        link.setOnAction(event -> linkEvidence());
        addCopy.setOnAction(event -> addCopy());
        addExpense.setOnAction(event -> addExpense());
        editAttachment.setOnAction(event -> editAttachment());
        retryPage.setOnAction(event -> retryPage());
        acceptSource.setOnAction(event -> acceptSource());

        preview.setId("expense-preview");
        preview.setPreserveRatio(true);
        preview.setFitHeight(134);
        preview.setFitWidth(360);
        show(preview, false);
        previewStatus.getStyleClass().add("quiet");
        hideEmpty(previewStatus);
        caption.getStyleClass().add("quiet");
        hideEmpty(caption);
        var values = new ScrollPane(evidenceValues);
        values.setId("expense-evidence-values");
        values.setFitToWidth(true);
        values.setPrefViewportHeight(113);
        values.setHbarPolicy(ScrollBarPolicy.NEVER);
        values.getStyleClass().add("evidence-values");
        return card(row(section("关联凭证"), spacer(), unassigned), evidence, row(page, spacer(), openSource),
                relationDetail, preview, previewStatus, values, caption,
                new FlowPane(7, 6, link, addCopy, addExpense, editAttachment, retryPage, acceptSource));
    }

    private Node printPane() {
        printOrder.setId("expense-order");
        printOrder.setText("打印附件顺序");
        printOrder.setExpanded(false);
        printList.setId("expense-order-list");
        printList.setPrefHeight(200);
        printList.setPlaceholder(new Label("暂无可打印的凭证页"));
        printList.setCellFactory(unused -> new ListCell<>() {
            @Override protected void updateItem(PrintPlacement placement, boolean empty) {
                super.updateItem(placement, empty);
                if (empty || placement == null) {
                    setText(null);
                } else {
                    var attachment = placement.page();
                    setText(placement.position() + "  " + attachment.relativePath() + " · 第 " + attachment.sourcePage()
                            + " 页 · " + roleLabel(attachment.role()) + "\n" + Objects.toString(attachment.caption(), ""));
                }
            }
        });
        printList.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2) openPrintPlacement();
        });
        printList.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ENTER) openPrintPlacement();
        });
        printOrder.setContent(printList);
        return printOrder;
    }

    private Node outputPane() {
        outputStatus.getStyleClass().add("quiet");
        outputRoot.getStyleClass().add("quiet");
        outputHint.getStyleClass().add("quiet");
        templateName.getStyleClass().add("quiet");
        hideEmpty(outputRoot);
        hideEmpty(outputHint);
        hideEmpty(templateName);
        generate.getStyleClass().add("primary");
        chooseOutput.setOnAction(event -> chooseOutputRoot());
        generate.setOnAction(event -> generate());
        retryOutput.setOnAction(event -> retryExport());
        history.setOnAction(event -> showHistory());
        repairTemplate.setOnAction(event -> repairTemplate());
        openExcel.setAccessibleText("用 WPS 打开 Excel 报销清单");
        openWord.setAccessibleText("用 WPS 打开 Word 打印附件");
        openExcel.setOnAction(event -> openCurrent(FileKind.XLSX));
        openWord.setOnAction(event -> openCurrent(FileKind.DOCX));
        openDirectory.setOnAction(event -> {
            if (outputState != null && outputState.current() != null) openDirectory(outputState.current());
        });
        return card(row(section("生成文件"), spacer(), outputStatus),
                new FlowPane(7, 7, generate, chooseOutput, repairTemplate), outputRoot, outputHint,
                row(new VBox(2, new Label("Excel 报销清单  .xlsx"), templateName), spacer(), openExcel),
                row(new Label("Word 打印附件  .docx"), spacer(), openWord),
                new FlowPane(7, 7, openDirectory, history, retryOutput));
    }

    private void chooseCaseFolder() {
        var chooser = new DirectoryChooser();
        chooser.setTitle("选择报销目录");
        if (view != null && Files.isDirectory(view.info().batchRoot())) {
            chooser.setInitialDirectory(view.info().batchRoot().toFile());
        }
        var chosen = chooser.showDialog(owner());
        if (chosen == null) {
            choose.requestFocus();
            return;
        }
        selectCase(chosen.toPath());
    }

    void selectCase(Path folder) {
        if (active != null || closed) return;
        var operation = begin(true, "正在准备扫描…");
        operations.execute(() -> {
            try {
                var info = cases.openCase(folder);
                var initial = drafts.loadForReview(info.id());
                operation.caseId = info.id();
                Platform.runLater(() -> {
                    if (active == operation) render(initial);
                });
                ScanOutcome outcome = scan(operation, initial);
                complete(operation, () -> {
                    render(outcome.view());
                    if (outcome.failure() != null) {
                        feedback(outcome.failure());
                    } else {
                        progressText.setText(outcome.cancelled() ? "扫描已取消，保留原草稿。"
                                : outcome.view().sources().isEmpty() ? "扫描结束，目录中没有可识别凭证。"
                                : "扫描完成 · " + outcome.view().sources().stream().filter(source -> !source.missing()).count() + " 份凭证");
                    }
                });
            } catch (Exception error) {
                complete(operation, () -> feedback(error));
            }
        });
    }

    private ScanOutcome scan(Operation operation, CaseView before) {
        Consumer<ScanService.Progress> listener = update -> Platform.runLater(() -> handleProgress(update));
        try (var ticket = scans.begin(before.info().id(), before.info().revision())) {
            operation.jobId = ticket.job().id();
            if (operation.cancelled) scans.cancel(operation.jobId);
            return new ScanOutcome(drafts.mergeScan(scans.scan(ticket, listener), listener), false, null);
        } catch (CancellationException cancelled) {
            return new ScanOutcome(before, true, null);
        } catch (RuntimeException error) {
            return new ScanOutcome(before, false, error);
        }
    }

    void handleProgress(ScanService.Progress event) {
        if (active == null || !active.scan || active.cancelled || view == null
                || !Objects.equals(active.caseId, event.caseId()) || !Objects.equals(active.jobId, event.jobId())
                || !view.info().id().equals(event.caseId())) return;
        String stage = switch (event.stage()) {
            case "RECOGNIZE" -> "识别凭证";
            case "HASH" -> "核验文件";
            default -> "枚举凭证";
        };
        progress.setProgress(event.total() > 0 ? (double) event.completed() / event.total() : -1);
        progressText.setText(stage + (event.total() == 0 ? "…" : " " + event.completed() + "/" + event.total())
                + (event.relativePath().isBlank() ? "" : " · " + event.relativePath()));
    }

    private void cancelScan() {
        var operation = active;
        if (operation == null || !operation.scan || operation.cancelled) return;
        operation.cancelled = true;
        if (operation.jobId != null) Thread.ofVirtual().start(() -> scans.cancel(operation.jobId));
        progressText.setText("正在取消扫描…");
        actions();
    }

    public void render(CaseView next) {
        var oldView = view;
        boolean sameCase = oldView != null && oldView.info().id().equals(next.info().id());
        var selection = sameCase ? items.getSelectionModel().getSelectedItem() : null;
        if (!sameCase) {
            unassigned.setSelected(false);
            staleEdit = null;
            evidence.setValue(null);
            page.setValue(null);
            selectedOutput = null;
        }
        outputState = null;
        view = next;
        rendering = true;
        try {
            folderName.setText(next.info().folder().getFileName().toString());
            folderName.setTooltip(new Tooltip(next.info().folder().toString()));
            type.setValue(next.info().type());
            items.getItems().setAll(next.items());
            if (selection != null) next.items().stream().filter(item -> item.id().equals(selection.id())).findFirst()
                    .ifPresent(items.getSelectionModel()::select);
            if (items.getSelectionModel().isEmpty() && !next.items().isEmpty()) {
                items.getSelectionModel().selectFirst();
            }
            if (next.items().isEmpty() && !next.sources().isEmpty()) unassigned.setSelected(true);
            count.setText(next.items().size() + " 笔");
            total.setText(confirmedCurrency(next.info().type(), next.items()));
            pending.setText("待核对 " + next.unresolvedCount() + " 笔 · 未归属 " + next.unassignedCount() + " 份"
                    + (next.unavailable().isEmpty() ? "" : " · 原件异常 " + next.unavailable().size() + " 项"));
            policy.setText(policy(next));
            rebuildPrintList(next.attachments());
            renderOutput();
        } finally {
            rendering = false;
        }
        rebuildEvidence();
        refreshOutputs();
    }

    private void rebuildEvidence() {
        var selected = evidence.getValue();
        Integer pageNo = page.getValue() == null ? null : page.getValue().pageNo();
        var selectedItem = items.getSelectionModel().getSelectedItem();
        var list = new ArrayList<Relation>();
        if (view != null) {
            boolean pendingEvidence = unassigned.isSelected();
            for (var source : view.sources()) {
                for (var unit : source.units()) {
                    var assigned = selectedItem == null ? null : view.links().stream()
                            .filter(link -> link.itemId().equals(selectedItem.id()) && link.unitId().equals(unit.unitId()))
                            .findFirst().orElse(null);
                    if (pendingEvidence) {
                        boolean needsReview = view.links().stream().noneMatch(link -> unit.unitId().equals(link.unitId()))
                                || view.links().stream().anyMatch(link -> unit.unitId().equals(link.unitId())
                                   && link.role() == Role.PENDING);
                        if (needsReview || source.missing() || source.error() != null) list.add(new Relation(source, unit, assigned, null));
                    } else if (assigned != null) {
                        list.add(new Relation(source, unit, assigned, null));
                    }
                }
                if (pendingEvidence && source.units().isEmpty() && !source.pages().isEmpty()) {
                    list.add(new Relation(source, null, null, null));
                }
            }
            if (pendingEvidence) view.unavailable().stream().filter(issue -> issue.sourcePage() == 0)
                    .map(issue -> new Relation(null, null, null, issue)).forEach(list::add);
        }
        rendering = true;
        try {
            evidence.getItems().setAll(list);
            Relation replacement = selected == null ? null : list.stream()
                    .filter(candidate -> candidate.path().equals(selected.path())
                            && Objects.equals(unitId(candidate), unitId(selected))).findFirst().orElse(null);
            evidence.setValue(replacement != null ? replacement : list.isEmpty() ? null : list.getFirst());
            if (replacement == null) pageNo = null;
        } finally {
            rendering = false;
        }
        selectRelation(pageNo);
    }

    private void selectRelation(Integer wantedPage) {
        Relation selected = evidence.getValue();
        rendering = true;
        try {
            page.getItems().setAll(selected == null ? List.of() : selected.pages());
            if (wantedPage != null) {
                page.getItems().stream().filter(result -> result.pageNo() == wantedPage).findFirst().ifPresent(page::setValue);
            }
            if (!page.getItems().contains(page.getValue())) {
                page.setValue(page.getItems().isEmpty() ? null : page.getItems().getFirst());
            }
        } finally {
            rendering = false;
        }
        evidenceDetail();
    }

    private void evidenceDetail() {
        Relation selected = evidence.getValue();
        PageResult result = page.getValue();
        evidenceValues.getChildren().clear();
        if (selected == null) {
            relationDetail.setText(unassigned.isSelected() ? "没有待处理凭证" : "请选择费用查看关联凭证");
            caption.setText("");
            clearPreview();
            actions();
            return;
        }
        String relationship = selected.link() == null ? "" : " · " + roleLabel(selected.link().role())
                + " · " + selected.link().basis();
        String error = selected.issue() != null ? selected.issue().code() : selected.source().error();
        String sourceState = error != null ? " · " + issueLabel(error) : selected.missing() ? " · 原件待定位" : "";
        relationDetail.setText(selected.path() + relationship + sourceState);
        var attachment = attachment(selected, result);
        caption.setText(attachment == null ? "" : "附件说明  " + Objects.toString(attachment.caption(), ""));
        if (result != null) {
            for (var field : result.fields()) {
                var value = new Label(EvidenceEditor.fieldName(field.key()) + "  " + Objects.toString(field.raw(), "—")
                        + (field.normalized() == null || Objects.equals(field.raw(), field.normalized())
                        ? "" : " → " + field.normalized())
                        + "  · " + methodLabel(field.method()) + " · 第 " + result.pageNo() + " 页"
                        + (field.validation() == null || field.validation().isBlank() ? "" : " · " + field.validation()));
                value.setWrapText(true);
                evidenceValues.getChildren().add(value);
            }
            result.warnings().forEach(warning -> evidenceValues.getChildren().add(new Label(issueLabel(warning))));
            if (!result.renderable()) {
                var action = new Label("可替换本单目录中的原件后重扫，也可编辑附件并填写排除原因。");
                action.setWrapText(true);
                evidenceValues.getChildren().add(action);
            }
        }
        requestPreview(selected, result);
        actions();
    }

    private void requestPreview(Relation selected, PageResult result) {
        clearPreview();
        if (selected.missing() || result == null || !result.renderable() || view == null) return;
        long request = previewVersion;
        Path root = view.info().folder();
        AttachmentPage attachment = attachment(selected, result);
        CropMode cropMode = attachment == null ? CropMode.NONE : attachment.cropMode();
        previewStatus.setText("正在加载预览…");
        previewWork = previews.submit(() -> {
            try {
                var image = readImage(root, selected, result.pageNo(), 105, cropMode);
                Platform.runLater(() -> {
                    if (previewVersion != request || closed) return;
                    preview.setImage(image.image());
                    show(preview, true);
                    previewStatus.setText("");
                });
            } catch (IOException | RuntimeException failure) {
                Platform.runLater(() -> {
                    if (previewVersion == request && !closed) previewStatus.setText("预览不可用，请重扫或打开原件核对。");
                });
            }
        });
    }

    private static PreviewPixels readImage(Path root, Relation selected, int page, float dpi, CropMode cropMode) throws IOException {
        Path original = sourcePath(root, selected);
        if (!selected.source().sha256().equals(TemplateArchive.sha256(original))) {
            throw new CaseException("SOURCE_CHANGED", "原件已变化，请重扫后再查看。");
        }
        var image = AttachmentImages.render(original, page, dpi, cropMode);
        try {
            return new PreviewPixels(image.getWidth(), image.getHeight(),
                    image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth()));
        } finally {
            image.flush();
        }
    }

    private void clearPreview() {
        previewVersion++;
        if (previewWork != null) previewWork.cancel(true);
        preview.setImage(null);
        show(preview, false);
        previewStatus.setText("");
    }

    private void openSource() {
        Relation selected = evidence.getValue();
        if (selected == null || selected.missing() || view == null || active != null) return;
        Path root = view.info().folder();
        int number = page.getValue() == null ? 1 : page.getValue().pageNo();
        if (!selected.path().toLowerCase(Locale.ROOT).endsWith(".pdf")) {
            run("正在查看原图…", () -> readImage(root, selected, number, 150, CropMode.NONE),
                    image -> showImage(selected.path(), image));
        } else {
            run("正在打开原件…", () -> wps.openSource(sourcePath(root, selected), selected.source().sha256()),
                    result -> launchFeedback(result, this::openSource));
        }
    }

    private static Path sourcePath(Path root, Relation selected) throws IOException {
        Path original = root.resolve(selected.path()).normalize();
        if (!original.startsWith(root) || !Files.isRegularFile(original, LinkOption.NOFOLLOW_LINKS)) {
            throw new CaseException("SOURCE_MISSING", "原件不可访问，请重新扫描。\n" + selected.path());
        }
        if (!original.toRealPath().startsWith(root.toRealPath())) {
            throw new CaseException("SOURCE_CHANGED", "原件位置已变化，请重新扫描。\n" + selected.path());
        }
        return original;
    }

    private void showImage(String name, PreviewPixels pixels) {
        var dialog = new Dialog<Void>();
        if (owner() != null) dialog.initOwner(owner());
        dialog.setResizable(true);
        dialog.setTitle(name);
        var image = new ImageView(pixels.image());
        image.setPreserveRatio(true);
        var viewport = new ScrollPane(image);
        viewport.setPannable(true);
        viewport.setPrefViewportWidth(Math.min(pixels.width(), 850));
        viewport.setPrefViewportHeight(Math.min(pixels.height(), 620));
        var zoom = new ComboBox<Integer>(FXCollections.observableArrayList(50, 75, 100, 125, 150, 200));
        zoom.setAccessibleText("原图缩放百分比");
        zoom.setValue(pixels.width() > 1050 ? 50 : 100);
        image.setFitWidth(pixels.width() * zoom.getValue() / 100d);
        zoom.setOnAction(event -> image.setFitWidth(pixels.width() * zoom.getValue() / 100d));
        dialog.getDialogPane().setContent(new VBox(8, row(new Label("缩放 %"), zoom), viewport));
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        dialog.showAndWait();
        openSource.requestFocus();
    }

    private void launchFeedback(WpsLauncher.Result result, Runnable retry) {
        progressText.setText(result.message());
        if (result.status() == WpsLauncher.Status.WPS_NOT_FOUND) {
            var locate = new ButtonType("选择 WPS 位置", ButtonBar.ButtonData.OK_DONE);
            if (confirm("找不到 WPS", result.message(), locate)) {
                boolean mac = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("mac");
                var chooser = new FileChooser();
                chooser.setTitle("选择本机 WPS " + (mac ? "应用程序" : "组件程序"));
                chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("WPS", mac ? "*.app" : "*.exe"));
                var chosen = chooser.showOpenDialog(owner());
                if (chosen != null) {
                    wps.selectInstallation(chosen.toPath());
                    Platform.runLater(retry);
                }
            }
        } else if (!result.opened() && result.status() != WpsLauncher.Status.IMAGE_PREVIEW) {
            notice.setText(result.status() + " · " + result.message());
        }
    }

    public void edit() {
        var item = items.getSelectionModel().getSelectedItem();
        if (item == null || active != null || view == null) return;
        Node focus = getScene() == null ? edit : getScene().getFocusOwner();
        editor.editFields(owner(), view, item, attachment(evidence.getValue(), page.getValue()))
                .ifPresent(this::save);
        if (focus != null) Platform.runLater(focus::requestFocus);
    }

    private void linkEvidence() {
        var item = items.getSelectionModel().getSelectedItem();
        var relation = evidence.getValue();
        if (item == null || relation == null || relation.unit() == null || relation.missing() || active != null) return;
        editor.linkEvidence(owner(), view, item, relation.unit()).ifPresent(this::save);
        if (active == null) link.requestFocus();
    }

    private void addCopy() {
        var item = items.getSelectionModel().getSelectedItem();
        var relation = evidence.getValue();
        if (item == null || relation == null || relation.unit() == null || active != null) return;
        if (view.links().stream().anyMatch(link -> link.unitId().equals(relation.unit().unitId())
                && link.itemId().equals(item.id()) && link.role() == Role.SUPPORT)) return;
        save(new EvidenceEditor.Edit(List.of(new DraftService.LinkEvidence(item.id(), relation.unit().unitId(),
                Role.SUPPORT, null, false)), "将同票副本作为佐证打印  " + relation.path()));
    }

    private void addExpense() {
        var relation = evidence.getValue();
        if (relation == null || relation.unit() == null || relation.missing() || active != null) return;
        var dialog = new TextInputDialog("补录费用");
        if (owner() != null) dialog.initOwner(owner());
        dialog.setTitle("从凭证新增费用");
        dialog.setHeaderText(relation.path());
        dialog.setContentText("费用名称");
        dialog.showAndWait().map(String::strip).filter(value -> !value.isEmpty()).ifPresent(value ->
                save(new EvidenceEditor.Edit(List.of(new DraftService.AddExpense(relation.unit().unitId(), value, null, null)),
                        "新增费用  " + value + "\n凭证  " + relation.path())));
    }

    private void editAttachment() {
        Relation selected = evidence.getValue();
        PageResult result = page.getValue();
        if (selected == null || view == null || active != null) return;
        if (selected.issue() != null) {
            editor.excludePath(owner(), selected.issue().relativePath()).ifPresent(this::save);
        } else if (result != null) {
            var old = view.attachmentSettings().stream().filter(setting -> setting.docId().equals(selected.source().docId())
                    && setting.firstPage() <= result.pageNo() && setting.lastPage() >= result.pageNo()).findFirst().orElse(null);
            editor.editAttachment(owner(), selected.source(), result, attachment(selected, result), old).ifPresent(this::save);
        }
        if (active == null) editAttachment.requestFocus();
    }

    private void retryPage() {
        Relation selected = evidence.getValue();
        PageResult result = page.getValue();
        if (view == null || active != null || selected == null || selected.missing() || result == null) return;
        var info = view.info();
        run("正在重试本页识别…", () -> drafts.retryPage(info.id(), info.revision(), selected.source().docId(),
                result.pageNo()), refreshed -> {
            render(refreshed);
            progressText.setText("已重新核对第 " + result.pageNo() + " 页");
        });
    }

    private void acceptSource() {
        Relation selected = evidence.getValue();
        if (view == null || active != null || selected == null || selected.missing()) return;
        if (!confirm("接受变更后的原件", "请先比对当前凭证及其金额依据。接受后会重新判断相关分项。",
                new ButtonType("接受当前原件", ButtonBar.ButtonData.OK_DONE))) return;
        save(new EvidenceEditor.Edit(List.of(new DraftService.AcceptChangedSource(selected.source().docId())),
                "接受当前原件  " + selected.path()));
    }

    void save(EvidenceEditor.Edit change) {
        if (view == null || active != null || change.changes().isEmpty()) return;
        var expected = view.info();
        run("正在保存草稿…", () -> {
            try {
                return new SaveOutcome(drafts.apply(expected.id(), expected.revision(), change.changes()), null);
            } catch (CaseException failure) {
                if (!"STALE_REVISION".equals(failure.code())) throw failure;
                return new SaveOutcome(drafts.load(expected.id()), change);
            }
        }, saved -> {
            render(saved.view());
            if (saved.conflict() != null) {
                staleEdit = new PendingEdit(expected.id(), saved.conflict());
                notice.setText("草稿已更新，未提交的编辑已保留，可查看并对照当前草稿。");
                progressText.setText("已重新加载 · 当前修订 " + saved.view().info().revision());
            } else {
                staleEdit = null;
                progressText.setText(saved.view().info().revision() == expected.revision()
                        ? "草稿没有变化" : "已保存修改");
            }
        });
    }

    private void compareEdit() {
        if (view == null || staleEdit == null || !view.info().id().equals(staleEdit.caseId()) || active != null) return;
        var dialog = new Dialog<Boolean>();
        if (owner() != null) dialog.initOwner(owner());
        dialog.setTitle("未提交的编辑");
        var text = new TextArea(staleEdit.edit().summary());
        text.setEditable(false);
        text.setWrapText(true);
        text.setPrefColumnCount(44);
        text.setPrefRowCount(8);
        var apply = new ButtonType("按当前修订重新提交", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().setAll(ButtonType.CANCEL, apply);
        dialog.getDialogPane().setContent(new VBox(8, new Label("当前修订 " + view.info().revision()), text));
        dialog.setResultConverter(value -> value == apply);
        if (dialog.showAndWait().orElse(false)) save(staleEdit.edit());
    }

    private void changeType() {
        if (rendering || view == null || active != null || type.getValue() == null || type.getValue() == view.info().type()) return;
        CaseType requested = type.getValue();
        setDisplayedType(view.info().type());
        long unmapped = view.items().stream().filter(item -> item.amounts().entrySet().stream().anyMatch(amount ->
                amount.getValue().confirmedMinor() != null && !ExpenseAmounts.allowed(requested, amount.getKey()))).count();
        if (unmapped > 0 && !confirm("更改报销类型", "有 " + unmapped + " 笔费用的已确认分项需重新归类。"
                + "\n切换后可在费用编辑中填入对应分项，并明确清空待归类项。", new ButtonType("更改并待核对", ButtonBar.ButtonData.OK_DONE))) return;
        save(new EvidenceEditor.Edit(List.of(new DraftService.SetType(requested)), "报销类型  " + requested.label()));
    }

    private void setDisplayedType(CaseType value) {
        rendering = true;
        try { type.setValue(value); } finally { rendering = false; }
    }

    private void rebuildPrintList(List<AttachmentPage> attachments) {
        var placements = new ArrayList<PrintPlacement>();
        var sheets = AttachmentLayout.paginate(attachments);
        for (int number = 0; number < sheets.size(); number++) {
            var sheet = sheets.get(number);
            for (int slot = 0; slot < sheet.pages().size(); slot++) {
                String position = sheet.fullPage() ? "整页" : slot == 0 ? "上格" : "下格";
                placements.add(new PrintPlacement(sheet.pages().get(slot), "纸页 " + (number + 1) + " · " + position));
            }
        }
        printList.getItems().setAll(placements);
        printOrder.setText("打印附件顺序" + (placements.isEmpty() ? ""
                : "  ·  " + placements.size() + " 个凭证页 · " + sheets.size() + " 张纸"));
    }

    private void openPrintPlacement() {
        PrintPlacement selected = printList.getSelectionModel().getSelectedItem();
        if (selected == null || view == null || active != null) return;
        AttachmentPage attachment = selected.page();
        var item = view.items().stream().filter(row -> attachment.itemIds().contains(row.id())).findFirst().orElse(null);
        unassigned.setSelected(item == null);
        items.getSelectionModel().select(item);
        rebuildEvidence();
        var target = evidence.getItems().stream().filter(relation -> relation.source() != null
                && relation.source().docId().equals(attachment.docId()) && relation.pages().stream()
                .anyMatch(result -> result.pageNo() == attachment.sourcePage())).findFirst().orElse(null);
        if (target != null) {
            evidence.setValue(target);
            selectRelation(attachment.sourcePage());
            evidence.requestFocus();
        }
    }

    private AttachmentPage attachment(Relation relation, PageResult result) {
        if (view == null || relation == null || relation.source() == null || result == null) return null;
        return view.attachments().stream().filter(entry -> entry.docId().equals(relation.source().docId())
                && entry.sourcePage() == result.pageNo()).findFirst().orElse(null);
    }

    public void refreshOutputs() {
        if (view == null || closed) return;
        CaseView snapshot = view;
        long request = ++outputVersion;
        if (outputWork != null) outputWork.cancel(true);
        outputWork = publications.submit(() -> {
            try {
                OutputState loaded = loadOutputs(snapshot);
                Platform.runLater(() -> {
                    if (!closed && request == outputVersion && snapshot == view) {
                        outputState = loaded;
                        renderOutput();
                        actions();
                    }
                });
            } catch (RuntimeException failure) {
                Platform.runLater(() -> {
                    if (request == outputVersion && !closed) outputStatus.setText("暂时无法读取成品记录");
                });
            }
        });
    }

    private OutputState loadOutputs(CaseView snapshot) {
        var info = snapshot.info();
        var records = exports.history(info.id());
        Record current = records.stream().filter(record -> record.exportId().equals(info.currentExportId())
                && record.state() == State.SUCCEEDED).findFirst().orElse(null);
        String templateError = null;
        if (info.type() != null) {
            try {
                templates.resolveByHash(info.templateHash(), info.type());
            } catch (CaseException failure) {
                templateError = failure.getMessage();
            }
        }
        boolean matches = current != null && current.revision() == info.revision();
        return new OutputState(records, exports.preferredRoot(info.id()), current,
                matches ? fileIssue(current, FileKind.XLSX) : null,
                matches ? fileIssue(current, FileKind.DOCX) : null, templateError);
    }

    private String fileIssue(Record record, FileKind type) {
        try {
            exports.verifiedFile(record.exportId(), type);
            return null;
        } catch (CaseException issue) {
            return issue.getMessage();
        }
    }

    private void renderOutput() {
        if (view == null) return;
        var info = view.info();
        templateName.setText(Objects.toString(TemplateCatalog.companySheetName(info.type()), "待选择报销类型"));
        if (outputState == null) {
            outputStatus.setText("正在检查成品记录…");
            outputRoot.setText("");
            outputHint.setText("");
            return;
        }
        Path root = selectedOutput != null ? selectedOutput : outputState.preferredRoot();
        outputRoot.setText("输出位置  " + root);
        var current = outputState.current();
        var latest = outputState.newest();
        long revision = info.revision();
        if (latest != null && latest.state() != State.SUCCEEDED) {
            outputStatus.setText(exportStatus(latest)
                    + (current == null ? "" : " · 保留上次已发布成品"));
        } else if (current == null) {
            outputStatus.setText("尚未生成");
        } else if (current.revision() != revision) {
            outputStatus.setText("草稿已更新，请重新生成 · 上次成品对应修订 " + current.revision());
        } else {
            outputStatus.setText(exportStatus(current));
        }
        var hints = new ArrayList<String>();
        if (outputState.templateIssue() != null) hints.add(outputState.templateIssue());
        if (!view.unavailable().isEmpty()) hints.add("「待处理凭证」中有 " + view.unavailable().size() + " 项原件异常。");
        long empty = view.items().stream().filter(item -> ExpenseAmounts.project(info.type(), item).isEmpty()).count();
        if (empty > 0) hints.add(empty + " 笔费用的人民币金额留空。");
        if (outputState.excelIssue() != null) hints.add("Excel  " + outputState.excelIssue());
        if (outputState.wordIssue() != null) hints.add("Word  " + outputState.wordIssue());
        outputHint.setText(String.join("\n", hints));
    }

    private static String exportStatus(Record record) {
        String status = switch (record.state()) {
            case SUCCEEDED -> "已生成";
            case GENERATING -> "生成中";
            case READY_TO_PUBLISH -> "等待发布 · " + Objects.toString(record.message(), "检查输出位置可用性");
            case FAILED -> "生成失败 · " + Objects.toString(record.message(), "可重试");
            case CONFLICT -> "输出位置冲突 · " + Objects.toString(record.message(), "请选择其他位置后重试");
        };
        return status + " · 修订 " + record.revision();
    }

    private void chooseOutputRoot() {
        if (view == null || active != null) return;
        var chooser = new DirectoryChooser();
        chooser.setTitle("选择输出位置（请选择空目录或已登记输出目录）");
        var chosen = chooser.showDialog(owner());
        if (chosen != null) {
            selectedOutput = chosen.toPath();
            renderOutput();
        }
        chooseOutput.requestFocus();
    }

    public void generate() { generate(null); }

    private void generate(String retryOf) {
        if (view == null || active != null || generate.isDisabled()) return;
        var info = view.info();
        Path root = selectedOutput;
        String request = UUID.randomUUID().toString();
        run("正在生成 Excel 报销清单和 Word 附件…", () ->
                exports.generate(info.id(), info.revision(), root, request, retryOf), this::finishExport);
    }

    private void finishExport(Record record) {
        if (record == null || view == null || !record.caseId().equals(view.info().id())) return;
        if (record.state() == State.SUCCEEDED) {
            selectedOutput = null;
        }
        String result = exportStatus(record);
        progressText.setText(result);
        if (record.state() == State.FAILED || record.state() == State.CONFLICT) {
            notice.setText(Objects.toString(record.errorCode(), "") + " · " + result);
        }
        var caseId = view.info().id();
        run(result, () -> drafts.load(caseId), loaded -> {
            render(loaded);
            progressText.setText(result);
            if (record.state() == State.FAILED || record.state() == State.CONFLICT) {
                notice.setText(Objects.toString(record.errorCode(), "") + " · " + result);
            }
        });
    }

    private void retryExport() {
        if (active != null || outputState == null || outputState.newest() == null) return;
        var latest = outputState.newest();
        if (latest.state() == State.READY_TO_PUBLISH) {
            run("正在检查待发布成品…", () -> exports.recoverReady(latest.exportId()), this::finishExport);
        } else if (latest.state() == State.FAILED || latest.state() == State.CONFLICT) {
            generate(latest.exportId());
        }
    }

    private void openCurrent(FileKind kind) {
        if (outputState != null && outputState.current() != null) open(outputState.current(), kind);
    }

    public void open(Record record, FileKind kind) {
        if (active != null || record.state() != State.SUCCEEDED || record.file(kind) == null) return;
        var file = record.file(kind);
        run("正在核验成品…", () -> {
            Path path = exports.verifiedFile(record.exportId(), kind);
            return wps.openInWps(path, file.sha256(), kind == FileKind.XLSX ? WpsLauncher.Component.XLSX : WpsLauncher.Component.DOCX);
        }, result -> launchFeedback(result, () -> open(record, kind)));
    }

    private void openDirectory(Record record) {
        run("正在定位成品目录…", () -> wps.openDirectory(record.finalDirectory()),
                result -> launchFeedback(result, () -> openDirectory(record)));
    }

    private void showHistory() {
        if (outputState == null || active != null) return;
        var dialog = new Dialog<ButtonType>();
        if (owner() != null) dialog.initOwner(owner());
        dialog.setTitle("生成历史");
        var records = new ListView<Record>(FXCollections.observableArrayList(outputState.history()));
        records.setPrefSize(555, 280);
        records.setCellFactory(list -> new ListCell<>() {
            @Override protected void updateItem(Record entry, boolean blank) {
                super.updateItem(entry, blank);
                setText(blank || entry == null ? null : exportStatus(entry)
                        + "\n" + entry.finalDirectory());
            }
        });
        records.getSelectionModel().selectFirst();
        var excel = new ButtonType("打开 Excel", ButtonBar.ButtonData.OTHER);
        var word = new ButtonType("打开 Word", ButtonBar.ButtonData.OTHER);
        var directory = new ButtonType("打开目录", ButtonBar.ButtonData.OTHER);
        var pane = dialog.getDialogPane();
        pane.setContent(records);
        pane.getButtonTypes().setAll(ButtonType.CLOSE, excel, word, directory);
        Consumer<Record> update = record -> {
            boolean published = record != null && record.state() == State.SUCCEEDED;
            pane.lookupButton(excel).setDisable(!published || record.file(FileKind.XLSX) == null);
            pane.lookupButton(word).setDisable(!published || record.file(FileKind.DOCX) == null);
            pane.lookupButton(directory).setDisable(!published);
        };
        update.accept(records.getSelectionModel().getSelectedItem());
        records.getSelectionModel().selectedItemProperty().addListener((observable, before, record) -> update.accept(record));
        dialog.showAndWait().ifPresent(result -> {
            Record record = records.getSelectionModel().getSelectedItem();
            if (record == null) return;
            if (result == excel) open(record, FileKind.XLSX);
            if (result == word) open(record, FileKind.DOCX);
            if (result == directory) openDirectory(record);
        });
    }

    private void repairTemplate() {
        if (view == null || active != null) return;
        var info = view.info();
        var locate = new ButtonType("定位同摘要模板", ButtonBar.ButtonData.OTHER);
        var replace = new ButtonType("切换到当前公司模板", ButtonBar.ButtonData.OK_DONE);
        var choices = new Alert(Alert.AlertType.CONFIRMATION, "草稿绑定的模板摘要为\n" + info.templateHash(),
                ButtonType.CANCEL, locate, replace);
        if (owner() != null) choices.initOwner(owner());
        choices.setTitle("处理模板");
        choices.setHeaderText("定位原模板，或改用新模板并保存修改");
        choices.getDialogPane().lookupButton(replace).setDisable(TemplateArchive.BUNDLED_SHA256.equals(info.templateHash()));
        var selection = choices.showAndWait().orElse(ButtonType.CANCEL);
        if (selection == locate) {
            var chooser = new FileChooser();
            chooser.setTitle("选择原版 Excel 模板");
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Excel 模板", "*.xlsx"));
            var picked = chooser.showOpenDialog(owner());
            if (picked != null) run("正在验证模板摘要…", () -> archive.locate(info.templateHash(), picked.toPath()), matched -> {
                progressText.setText("已恢复模板目录记录");
                refreshOutputs();
            });
        } else if (selection == replace) {
            save(new EvidenceEditor.Edit(List.of(new DraftService.ReplaceTemplate(TemplateArchive.BUNDLED_SHA256)),
                    "切换到当前公司报销模板"));
        }
    }

    private Operation begin(boolean scanning, String message) {
        active = new Operation(scanning);
        notice.setText("");
        progressText.setText(message);
        progress.setProgress(-1);
        show(progress, true);
        actions();
        return active;
    }

    private <T> void run(String message, Callable<T> task, Consumer<T> success) {
        if (active != null || closed) return;
        var operation = begin(false, message);
        operations.execute(() -> {
            try {
                T value = task.call();
                complete(operation, () -> success.accept(value));
            } catch (Exception error) {
                complete(operation, () -> operationFailure(error));
            }
        });
    }

    private void complete(Operation operation, Runnable result) {
        Platform.runLater(() -> {
            if (closed || active != operation) return;
            active = null;
            show(progress, false);
            try { result.run(); } finally { actions(); }
        });
    }

    private void actions() {
        boolean busy = active != null;
        boolean present = view != null;
        var item = items.getSelectionModel().getSelectedItem();
        var relation = evidence.getValue();
        boolean canInspect = present && !busy;
        Record latest = outputState == null ? null : outputState.newest();
        boolean publicationPending = latest != null
                && (latest.state() == State.GENERATING || latest.state() == State.READY_TO_PUBLISH);
        boolean canEdit = canInspect && !publicationPending;
        boolean available = relation != null && !relation.missing();
        boolean hasUnit = available && relation.unit() != null;
        boolean duplicate = hasUnit && view != null && view.duplicateUnitIds().contains(relation.unit().unitId());
        choose.setDisable(busy);
        rescan.setDisable(!canEdit);
        show(cancel, busy && active.scan);
        cancel.setDisable(!busy || !active.scan || active.cancelled);
        type.setDisable(!canEdit);
        edit.setDisable(!canEdit || item == null);
        show(compare, staleEdit != null);
        compare.setDisable(!canEdit);
        unassigned.setDisable(!present);
        evidence.setDisable(evidence.getItems().isEmpty());
        page.setDisable(page.getItems().isEmpty());
        openSource.setDisable(!canInspect || !available);
        openSource.setText(available && !relation.path().toLowerCase(Locale.ROOT).endsWith(".pdf") ? "查看原图" : "用 WPS 打开原件");
        link.setDisable(!canEdit || item == null || !hasUnit);
        show(addCopy, duplicate && item != null);
        addCopy.setDisable(!canEdit || !duplicate || item == null);
        boolean loose = hasUnit && !duplicate && view.links().stream().noneMatch(edge -> edge.unitId().equals(relation.unit().unitId()));
        show(addExpense, loose);
        addExpense.setDisable(!canEdit || !loose);
        editAttachment.setDisable(!canEdit || relation == null || (relation.issue() == null && page.getValue() == null));
        editAttachment.setText(relation != null && relation.issue() != null ? "排除此路径" : "编辑附件");
        boolean retryable = available && page.getValue() != null && !page.getValue().warnings().isEmpty();
        show(retryPage, retryable);
        retryPage.setDisable(!canEdit || !retryable);
        boolean replaced = available && present && view.overrides().stream().anyMatch(override ->
                override.targetId().equals(relation.source().docId()) && "SOURCE_VERSION".equals(override.fieldKey())
                        && "PENDING".equals(override.action()));
        show(acceptSource, replaced);
        acceptSource.setDisable(!canEdit || !replaced);
        outputActions(canInspect);
    }

    private void outputActions(boolean canEdit) {
        Record current = outputState == null ? null : outputState.current();
        Record latest = outputState == null ? null : outputState.newest();
        boolean checkPending = latest != null && latest.state() == State.READY_TO_PUBLISH;
        boolean latestActive = checkPending || latest != null && latest.state() == State.GENERATING;
        boolean versionMatches = current != null && view != null && current.revision() == view.info().revision();
        boolean eligible = canEdit && outputState != null && view.info().type() != null && !view.attachments().isEmpty()
                && view.unavailable().isEmpty() && view.items().stream().noneMatch(item -> item.reviewReasons().contains("UNMAPPED_CONFIRMATION"))
                && outputState.templateIssue() == null && !latestActive;
        generate.setDisable(!eligible);
        generate.setText(current == null ? "生成清单和附件" : "重新生成清单和附件");
        chooseOutput.setDisable(!canEdit || latestActive);
        openExcel.setDisable(!canEdit || !versionMatches || outputState.excelIssue() != null || current.file(FileKind.XLSX) == null);
        openWord.setDisable(!canEdit || !versionMatches || outputState.wordIssue() != null || current.file(FileKind.DOCX) == null);
        openDirectory.setDisable(!canEdit || current == null);
        history.setDisable(!canEdit || latest == null);
        boolean retryable = latest != null && (latest.state() == State.CONFLICT || latest.state() == State.FAILED);
        show(retryOutput, retryable || checkPending);
        retryOutput.setText(checkPending ? "检查并继续发布" : "重试生成");
        retryOutput.setDisable(!canEdit || (!checkPending && (!retryable || !eligible)));
        boolean templateIssue = outputState != null && outputState.templateIssue() != null;
        show(repairTemplate, templateIssue);
        repairTemplate.setDisable(!canEdit || !templateIssue);
    }

    private void feedback(Exception error) {
        progressText.setText("操作未完成");
        notice.setText(switch (error) {
            case CaseException domain -> domain.code() + " · " + domain.getMessage();
            case StoreException store -> store.code() + " · " + store.getMessage();
            case TemplateArchive.TemplateMismatch template -> template.code() + " · " + template.getMessage();
            default -> "当前操作无法完成，请重新选择目录或重试。";
        });
    }

    private void operationFailure(Exception error) {
        if (error instanceof CaseException conflict && "STALE_REVISION".equals(conflict.code()) && view != null) {
            var id = view.info().id();
            run("正在重新加载草稿…", () -> drafts.load(id), current -> {
                render(current);
                feedback(conflict);
            });
        } else {
            feedback(error);
        }
    }

    private boolean confirm(String title, String detail, ButtonType proceed) {
        var prompt = new Alert(Alert.AlertType.CONFIRMATION, detail, ButtonType.CANCEL, proceed);
        if (owner() != null) prompt.initOwner(owner());
        prompt.setTitle(title);
        prompt.setHeaderText(title);
        return prompt.showAndWait().orElse(ButtonType.CANCEL) == proceed;
    }

    private Window owner() { return getScene() == null ? null : getScene().getWindow(); }
    boolean busy() { return active != null; }
    public boolean canClose() {
        if (!busy()) return true;
        notice.setText(active.scan ? "扫描正在进行，请先取消扫描或等待完成。" : "正在处理草稿或成品，请等待任务结束。");
        return false;
    }

    @Override public void close() {
        closed = true;
        cancelScan();
        operations.shutdownNow();
        clearPreview();
        previews.shutdownNow();
        if (outputWork != null) outputWork.cancel(true);
        publications.shutdownNow();
    }

    private String reviewState(Item item) {
        if (item.reviewReasons().contains("UNMAPPED_CONFIRMATION")) return "金额待归类";
        if (item.reviewReasons().contains("MILEAGE_OUT_OF_RANGE")) return "公里数超出精度";
        if (item.reviewReasons().contains("MILEAGE_NEEDS_REVIEW")) return "路线里程待核对";
        if (item.reviewReasons().contains("INVOICE_AMOUNT_CONFLICT")) return "发票金额冲突";
        if (item.reviewReasons().contains("RECEIPT_CONFLICT")) return "收据金额冲突";
        if (item.reviewReasons().contains("CNY_CONFIRMATION_REQUIRED")
                || item.reviewReasons().contains("AMOUNT_NEEDS_REVIEW")) return "人民币金额待确认";
        if (item.reviewReasons().contains("HOTEL_NIGHTS_NEEDS_REVIEW")
                && !item.reviewReasons().contains("SOURCE_REVIEW_REQUIRED")) return "住宿天数待核对";
        if (item.reviewReasons().isEmpty()) return "已核对";
        return ExpenseAmounts.project(view.info().type(), item).isEmpty() ? "待核对" : "部分确认";
    }

    private static String dates(Item row) {
        if (row.startDate() == null) return "—";
        if (row.endDate() == null || row.endDate().equals(row.startDate())) return row.startDate().toString();
        return row.startDate() + "\n" + row.endDate();
    }

    private static String confirmedCurrency(CaseType type, List<Item> items) {
        try {
            Long total = ExpenseAmounts.confirmedTotal(type, items);
            return total == null ? "—" : "¥" + ExpenseAmounts.decimal(total);
        } catch (ArithmeticException overflow) {
            return "金额超出范围";
        }
    }

    private static String policy(CaseView view) {
        if (view.info().type() != CaseType.TRAVEL) return "";
        boolean taxi = view.items().stream().anyMatch(item -> item.amounts().containsKey(Component.TAXI));
        return "请核对 OA 出差申请。"
                + (taxi ? " 的士费用按 G 列填列，请核对明细附表和同日交通补贴冲突。" : "")
                + " 同日招待与伙食补贴、未住宿资格请按财务制度核对。";
    }

    private static String unitId(Relation relation) { return relation.unit() == null ? null : relation.unit().unitId(); }

    private static String roleLabel(Role role) {
        return switch (role) {
            case CHARGE -> "计费凭证"; case SUPPORT -> "佐证"; case REPLACEMENT -> "替票"; case PENDING -> "待核对";
        };
    }

    private static String methodLabel(PageResult.Method method) {
        return switch (method) {
            case PDF_TEXT -> "PDF 文本"; case OCR -> "图像识别"; case FILE_NAME -> "文件名"; case RULE -> "规则核对";
        };
    }

    private static String issueLabel(String code) {
        return switch (code) {
            case "PATH_UNREADABLE" -> "路径不可读";
            case "PAGE_UNRENDERABLE" -> "页面不可渲染";
            case "SOURCE_CHANGED" -> "原件已变化";
            case "SOURCE_MISSING" -> "原件待定位";
            case "OCR_UNAVAILABLE" -> "OCR 不可用，可人工补录";
            case "OCR_TIMEOUT" -> "OCR 超时，可重扫或人工补录";
            case "FIELDS_NEED_REVIEW" -> "字段待核对";
            case "AMOUNT_CONFLICT" -> "票面金额冲突";
            case "CURRENCY_NEEDS_REVIEW" -> "票面币种待核对";
            case "CURRENCY_CONFLICT" -> "票面币种冲突";
            case "MILEAGE_NEEDS_REVIEW" -> "路线里程待核对，可重扫或补录";
            case "MILEAGE_OUT_OF_RANGE" -> "公里数超出清单可用精度，请核对里程";
            default -> code;
        };
    }

    private static TableColumn<Item, String> column(String name, Function<Item, String> value, double width) {
        var column = new TableColumn<Item, String>(name);
        column.setPrefWidth(width);
        column.setCellValueFactory(cell -> new ReadOnlyStringWrapper(value.apply(cell.getValue())));
        column.setCellFactory(table -> new javafx.scene.control.TableCell<>() {
            private final Label text = new Label();
            @Override protected void updateItem(String value, boolean empty) {
                super.updateItem(value, empty);
                text.setWrapText(true);
                text.setText(value);
                setGraphic(empty ? null : text);
            }
        });
        column.setSortable(false);
        return column;
    }

    private static Label label(String id, String value) {
        var label = new Label(value);
        label.setId(id);
        label.setWrapText(true);
        return label;
    }
    private static Label section(String value) {
        var label = new Label(value);
        label.getStyleClass().add("section-title");
        return label;
    }
    private static Button button(String id, String title) {
        var button = new Button(title);
        button.setId(id);
        return button;
    }
    private static HBox row(Node... controls) {
        var row = new HBox(8, controls);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }
    private static VBox card(Node... controls) {
        var card = new VBox(8, controls);
        card.setMinWidth(0);
        card.getStyleClass().add("workbench-card");
        return card;
    }
    private static Region spacer() {
        var spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        return spacer;
    }
    private static void hideEmpty(Label label) {
        label.visibleProperty().bind(label.textProperty().isNotEmpty());
        label.managedProperty().bind(label.visibleProperty());
    }
    private static void show(Node node, boolean visible) {
        node.setVisible(visible);
        node.setManaged(visible);
    }
}
