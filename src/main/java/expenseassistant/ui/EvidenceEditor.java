package expenseassistant.ui;

import expenseassistant.draft.CaseView;
import expenseassistant.draft.DraftService;
import expenseassistant.draft.DraftService.Change;
import expenseassistant.intake.TypeInference.CaseType;
import expenseassistant.matching.ExpenseAmounts;
import expenseassistant.matching.ExpenseAmounts.Component;
import expenseassistant.matching.ExpenseMatcher.Evidence;
import expenseassistant.matching.ExpenseMatcher.Item;
import expenseassistant.matching.ExpenseMatcher.Role;
import expenseassistant.output.ExportSnapshot;
import expenseassistant.output.ExportSnapshot.Layout;
import expenseassistant.recognition.PageResult;
import javafx.collections.FXCollections;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import javafx.util.StringConverter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

public final class EvidenceEditor {
    private static final Map<String, String> FIELDS = Map.ofEntries(
            Map.entry("description", "费用名称"), Map.entry("startDate", "业务起日"), Map.entry("endDate", "业务止日"),
            Map.entry("route", "行程或区间"), Map.entry("origin", "起点"), Map.entry("destination", "终点"),
            Map.entry("vehicle", "交通工具"), Map.entry("nights", "住宿天数"), Map.entry("note", "备注"),
            Map.entry("customer", "客户单位"), Map.entry("agenda", "沟通事项"), Map.entry("detail", "明细"),
            Map.entry("direction", "方向"), Map.entry("rawDistanceKm", "截图里程（公里）"),
            Map.entry("routeDistanceCandidateKm", "候选路线里程（公里）"),
            Map.entry("selectedRouteDistanceKm", "选中路线里程（公里）"),
            Map.entry("distanceKm", "确认里程（公里）"), Map.entry("people", "人数"), Map.entry("location", "地点"),
            Map.entry("gross", "票面总额"), Map.entry("currency", "币种"), Map.entry("invoiceNumber", "发票号码"),
            Map.entry("invoiceDate", "开票日期"), Map.entry("tax", "税额"), Map.entry("subtotal", "未税金额"),
            Map.entry("orderKey", "订单标识"), Map.entry("platform", "平台"),
            Map.entry("renewalDate", "续期日期"), Map.entry("documentNumber", "文稿号"),
            Map.entry("receiptIssuer", "收据开具方"), Map.entry("receiptNumber", "收据编号"),
            Map.entry("receiptDate", "收据日期"));
    private static final ButtonType SAVE = new ButtonType("保存", ButtonBar.ButtonData.OK_DONE);
    private enum FieldAction {
        EDIT("修改"), CLEAR("明确留空"), RESTORE("恢复识别值");
        private final String label;
        FieldAction(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }

    public record Edit(List<Change> changes, String summary) {
        public Edit {
            changes = List.copyOf(changes);
        }
    }

    private record FieldInput(String key, TextField field, ComboBox<FieldAction> action, String original) {}
    private record AmountInput(Component component, TextField field, ComboBox<FieldAction> action, Long original) {}
    private record LinkChoice(Role role, String label) {
        @Override public String toString() { return label; }
    }

    public Optional<Edit> editFields(Window owner, CaseView view, Item item, ExportSnapshot.AttachmentPage page) {
        var form = form();
        CaptionInput caption = page == null ? null : new CaptionInput(page);
        var fields = new ArrayList<FieldInput>();
        var amounts = new ArrayList<AmountInput>();
        for (String field : fields(view.info().type())) {
            String value = value(item, field);
            if ("MILEAGE".equals(item.subtype()) && "rawDistanceKm".equals(field)) {
                form.addRow(form.getRowCount(), fieldLabel(field), new Label(value == null ? "—" : value));
                continue;
            }
            var input = text(value);
            input.setId("field-" + field);
            if (field.endsWith("Date")) input.setPromptText("YYYY-MM-DD");
            if (field.equals("direction")) input.setPromptText("单程或往返");
            var action = action();
            form.addRow(form.getRowCount(), fieldLabel(field), input, action);
            fields.add(new FieldInput(field, input, action, value));
        }
        var basis = text(null);
        basis.setPromptText("仅新增或调整确认金额时填写");
        addAmountFields(form, view.info().type(), item, basis, amounts);
        if (caption != null) {
            form.addRow(form.getRowCount(), new Label("当前凭证附件说明"), caption.field);
        }
        Label title = new Label(item.description());
        title.getStyleClass().add("section-title");
        return show(owner, "编辑费用", title, scroll(form), () -> {
            var edits = new ArrayList<Change>();
            var summary = new ArrayList<String>();
            for (FieldInput input : fields) {
                addField(item, input, edits, summary);
            }
            for (AmountInput input : amounts) {
                addAmount(item, input, basis.getText(), edits, summary);
            }
            if (caption != null && !caption.field.getText().equals(Objects.toString(page.caption(), ""))) {
                validateCaption(caption.field.getText());
                edits.add(new DraftService.SetCaption(page.docId(), page.sourcePage(), caption.field.getText()));
                summary.add("附件说明  " + display(caption.field.getText()));
            }
            return new Edit(edits, String.join("\n", summary));
        });
    }

    private static void addAmountFields(GridPane form, CaseType type, Item item, TextField basis, List<AmountInput> amounts) {
        if (type == null) return;
        var visibleComponents = new ArrayList<>(components(type));
        item.amounts().forEach((component, amount) -> {
            if (amount.confirmedMinor() != null && !visibleComponents.contains(component)) visibleComponents.add(component);
        });
        for (Component component : visibleComponents) {
            var amount = item.amounts().get(component);
            Long confirmed = amount == null ? null : amount.confirmedMinor();
            if (ExpenseAmounts.derivedMileageFuel(item.subtype(), component)) {
                var value = new Label((confirmed == null ? "—" : "¥" + ExpenseAmounts.decimal(confirmed))
                        + " · " + ExpenseAmounts.MILEAGE_RATE_TEXT);
                value.setId("amount-FUEL");
                form.addRow(form.getRowCount(), new Label(componentLabel(component)), value);
                continue;
            }
            var input = text(confirmed == null ? null : ExpenseAmounts.decimal(confirmed));
            input.setId("amount-" + component);
            if (amount != null && amount.faceValue() != null) {
                input.setPromptText("票面 " + Objects.toString(amount.originalCurrency(), "") + " " + amount.faceValue());
            } else {
                input.setPromptText("人民币元");
            }
            var action = action();
            boolean allowed = ExpenseAmounts.allowed(type, component);
            if (!allowed) {
                action.getItems().setAll(FieldAction.EDIT, FieldAction.CLEAR);
                input.setDisable(true);
            }
            form.addRow(form.getRowCount(), new Label(componentLabel(component) + (allowed ? "" : "（待归类）")), input, action);
            amounts.add(new AmountInput(component, input, action, confirmed));
        }
        form.addRow(form.getRowCount(), new Label("核对依据"), basis);
    }

    public Optional<Edit> linkEvidence(Window owner, CaseView view, Item item, Evidence source) {
        boolean assigned = view.links().stream().anyMatch(link ->
                link.unitId().equals(source.unitId()) && link.itemId().equals(item.id()));
        var choices = new ArrayList<LinkChoice>();
        if (assigned) choices.add(new LinkChoice(null, "解除与这笔费用的关联"));
        choices.add(new LinkChoice(Role.SUPPORT, "佐证，随附件打印"));
        boolean chargeable = source.invoiceKey() != null && source.chargeMinor() != null && view.info().type() != null;
        if (chargeable && !view.duplicateUnitIds().contains(source.unitId())) {
            choices.add(new LinkChoice(Role.CHARGE, "作为计费凭证"));
            choices.add(new LinkChoice(Role.REPLACEMENT, "关联替票"));
        }
        var operation = new ComboBox<LinkChoice>(FXCollections.observableArrayList(choices));
        operation.setValue(choices.getFirst());
        var chargeComponents = components(view.info().type()).stream()
                .filter(candidate -> !ExpenseAmounts.derivedMileageFuel(item.subtype(), candidate)).toList();
        var component = new ComboBox<Component>(FXCollections.observableArrayList(chargeComponents));
        component.setConverter(converter());
        var usual = ExpenseAmounts.inferredComponent(view.info().type(), item.subtype());
        component.setValue(usual != null && chargeComponents.contains(usual) ? usual
                : chargeComponents.isEmpty() ? null : chargeComponents.getFirst());
        var occupation = source.invoiceKey() == null ? null : view.claims().stream()
                .filter(claim -> source.invoiceKey().equals(claim.chargeKey()) && !claim.itemId().equals(item.id()))
                .findFirst().orElse(null);
        String prior = occupation == null ? "" : view.items().stream().filter(row -> row.id().equals(occupation.itemId()))
                .map(Item::description).findFirst().orElse("另一笔费用");
        var transfer = new CheckBox("确认转移占用，原费用的相关分项将重审");
        transfer.setDisable(occupation == null);
        component.disableProperty().bind(javafx.beans.binding.Bindings.createBooleanBinding(() -> {
            Role role = operation.getValue().role();
            return role != Role.CHARGE && role != Role.REPLACEMENT;
        }, operation.valueProperty()));
        var displayInfo = new Label("费用  " + item.description() + "\n凭证  " + source.relativePath()
                + "\n票面金额  " + (source.amount() == null ? display(source.page().value("currency")) + " "
                + display(source.page().value("gross")) : "¥" + ExpenseAmounts.decimal(source.amount()))
                + (occupation == null ? "" : "\n计费占用  " + prior + " · " + componentLabel(occupation.component())));
        displayInfo.setWrapText(true);
        var form = form();
        form.addRow(0, new Label("关联方式"), operation);
        form.addRow(1, new Label("计费分项"), component);
        if (occupation != null) form.add(transfer, 0, 2, 2, 1);
        return show(owner, "核对凭证关联", displayInfo, form, () -> {
            Role role = operation.getValue().role();
            boolean changesCharge = role == Role.CHARGE || role == Role.REPLACEMENT;
            if (changesCharge && component.getValue() == null) throw new IllegalArgumentException("请选择计费分项。");
            if (changesCharge && occupation != null && !transfer.isSelected()) {
                throw new IllegalArgumentException("请确认是否从「" + prior + "」转移该凭证。");
            }
            return new Edit(List.of(new DraftService.LinkEvidence(item.id(), source.unitId(), role,
                    changesCharge ? component.getValue() : null, changesCharge && transfer.isSelected())),
                    operation.getValue().label() + "  " + source.relativePath() + " → " + item.description());
        });
    }

    public Optional<Edit> editAttachment(Window owner, CaseView.Source source, PageResult page,
                                         ExportSnapshot.AttachmentPage attachment, CaseView.AttachmentSetting previous) {
        String displayedCaption = attachment == null ? "" : Objects.toString(attachment.caption(), "");
        String overrideCaption = previous == null ? null : previous.caption();
        var caption = text(overrideCaption == null ? displayedCaption : overrideCaption);
        caption.setId("attachment-caption");
        var automatic = new CheckBox("使用自动说明");
        automatic.setSelected(overrideCaption == null);
        caption.disableProperty().bind(automatic.selectedProperty());
        var layout = new ComboBox<Layout>(FXCollections.observableArrayList(Layout.values()));
        layout.setConverter(new StringConverter<>() {
            @Override public String toString(Layout value) { return value == Layout.FULL_PAGE ? "整页" : "上下双格"; }
            @Override public Layout fromString(String input) { return null; }
        });
        Layout displayedLayout = attachment != null ? attachment.layout()
                : previous != null && previous.layout() != null ? previous.layout() : Layout.TWO_PER_PAGE;
        layout.setValue(displayedLayout);
        layout.setDisable(page.kind() == PageResult.Kind.INVOICE);
        var exclusion = new CheckBox("排除本页");
        String reasonBefore = previous == null ? null : previous.exclusionReason();
        exclusion.setSelected(reasonBefore != null);
        var reason = text(reasonBefore);
        reason.setPromptText("填写排除原因");
        reason.disableProperty().bind(exclusion.selectedProperty().not());
        var grid = form();
        grid.addRow(0, new Label("附件说明"), caption, automatic);
        grid.addRow(1, new Label("纸张布局"), layout);
        grid.addRow(2, exclusion, reason);
        var info = new Label(source.relativePath() + " · 第 " + page.pageNo() + " 页\n排除计费页会撤回相应金额。");
        info.setWrapText(true);
        return show(owner, "编辑打印附件", info, grid, () -> {
            String after = automatic.isSelected() ? null : caption.getText();
            if (after != null) validateCaption(after);
            String excluded = exclusion.isSelected() ? reason.getText().strip() : null;
            if (excluded != null && excluded.isEmpty()) throw new IllegalArgumentException("请填写排除原因。");
            if (Objects.equals(overrideCaption, after) && displayedLayout == layout.getValue()
                    && Objects.equals(reasonBefore, excluded)) {
                return new Edit(List.of(), "");
            }
            Layout newLayout = displayedLayout == layout.getValue()
                    ? previous == null ? null : previous.layout() : layout.getValue();
            return new Edit(List.of(new DraftService.SetAttachment(source.docId(), page.pageNo(), page.pageNo(),
                    after, newLayout, previous == null ? null : previous.orderKey(), excluded)),
                    "附件  " + source.relativePath() + " 第 " + page.pageNo() + " 页\n"
                            + (after == null ? "自动说明" : "说明  " + display(after)) + "\n"
                            + layout.getConverter().toString(layout.getValue())
                            + (excluded == null ? "" : "\n排除  " + excluded));
        });
    }

    public Optional<Edit> excludePath(Window owner, String relativePath) {
        var reason = text(null);
        reason.setPromptText("填写排除原因");
        var info = new Label(relativePath + "\n排除后本次生成不会包含此路径。");
        info.setWrapText(true);
        return show(owner, "排除无法读取的原件", info, reason, () -> {
            String value = reason.getText().strip();
            if (value.isEmpty()) throw new IllegalArgumentException("请填写排除原因。");
            return new Edit(List.of(new DraftService.ExcludePath(relativePath, value)), "排除  " + relativePath + "\n" + value);
        });
    }

    static String fieldName(String key) { return FIELDS.getOrDefault(key, key); }

    static String componentLabel(Component component) {
        return switch (component) {
            case TRANSPORT -> "交通金额"; case HOTEL -> "住宿费用"; case TAXI -> "的士费用";
            case TRAFFIC_SUBSIDY -> "交通补贴"; case MEAL_SUBSIDY -> "伙食补贴";
            case NO_STAY_SUBSIDY -> "未住宿补贴"; case ENTERTAINMENT -> "业务招待费用";
            case FUEL -> "油费"; case TOLL -> "通行费"; case PARKING -> "停车费"; case OTHER -> "其他费用";
        };
    }

    static List<Component> components(CaseType type) {
        return Arrays.stream(Component.values()).filter(component -> ExpenseAmounts.allowed(type, component)).toList();
    }

    private static List<String> fields(CaseType type) {
        var fields = new ArrayList<>(List.of("description", "startDate", "endDate"));
        if (type != null) {
            fields.addAll(switch (type) {
                case TRAVEL -> List.of("route", "origin", "destination", "vehicle", "nights", "note");
                case ENTERTAINMENT -> List.of("customer", "agenda", "detail", "note");
                case PRIVATE_CAR -> List.of("route", "origin", "destination", "direction", "rawDistanceKm", "distanceKm", "note");
                case TAXI -> List.of("route", "people", "note");
                case OTHER -> List.of("location", "detail", "note");
            });
        }
        return fields;
    }

    private static String value(Item item, String field) {
        return switch (field) {
            case "description" -> item.description();
            case "startDate" -> Objects.toString(item.startDate(), null);
            case "endDate" -> Objects.toString(item.endDate(), null);
            default -> item.fields().get(field);
        };
    }

    private static void addField(Item item, FieldInput input, List<Change> edits, List<String> summary) {
        FieldAction action = input.action.getValue();
        String value = input.field.getText().strip();
        String label = fieldName(input.key);
        if (action == FieldAction.RESTORE) {
            edits.add(new DraftService.RestoreField(item.id(), input.key));
            summary.add(label + "  恢复识别值");
        } else if (action == FieldAction.CLEAR || !value.equals(Objects.toString(input.original, "").strip())) {
            if (action == FieldAction.CLEAR) value = "";
            if (!value.isEmpty() && input.key.endsWith("Date")) {
                try { LocalDate.parse(value); } catch (DateTimeParseException invalid) {
                    throw new IllegalArgumentException(label + "应为 YYYY-MM-DD。");
                }
            }
            edits.add(new DraftService.SetField(item.id(), input.key, value.isEmpty() ? null : value));
            summary.add(label + "  " + display(value));
        }
    }

    private static void addAmount(Item item, AmountInput input, String basis, List<Change> edits, List<String> summary) {
        FieldAction action = input.action.getValue();
        String label = componentLabel(input.component);
        if (action == FieldAction.RESTORE) {
            edits.add(new DraftService.RestoreField(item.id(), "AMOUNT:" + input.component.name()));
            summary.add(label + "  恢复识别值");
            return;
        }
        if (action == FieldAction.CLEAR) {
            edits.add(new DraftService.ClearAmount(item.id(), input.component));
            summary.add(label + "  留空");
            return;
        }
        String amount = input.field.getText().strip();
        Long value = amount.isEmpty() ? null : minor(amount, label);
        if (!Objects.equals(input.original, value)) {
            if (value != null && basis.isBlank()) throw new IllegalArgumentException("请输入金额的核对依据。");
            edits.add(new DraftService.SetAmount(item.id(), input.component, "CNY", value, basis.strip()));
            summary.add(label + "  " + (value == null ? "留空" : "¥" + ExpenseAmounts.decimal(value) + " · " + basis.strip()));
        }
    }

    private static long minor(String value, String label) {
        if (!value.matches("[0-9]+(?:\\.[0-9]{1,2})?")) {
            throw new IllegalArgumentException(label + "应为非负人民币金额，最多两位小数。");
        }
        try {
            return new BigDecimal(value).movePointRight(2).longValueExact();
        } catch (ArithmeticException oversized) {
            throw new IllegalArgumentException(label + "超出可录入范围。");
        }
    }

    private static void validateCaption(String caption) {
        if (caption.codePointCount(0, caption.length()) > 90) {
            throw new IllegalArgumentException("附件说明最多 90 个字符。");
        }
    }

    private static String display(String value) { return value == null || value.isEmpty() ? "留空" : value; }

    private static StringConverter<Component> converter() {
        return new StringConverter<>() {
            @Override public String toString(Component value) { return value == null ? "" : componentLabel(value); }
            @Override public Component fromString(String input) { return null; }
        };
    }

    private static ComboBox<FieldAction> action() {
        var combo = new ComboBox<FieldAction>(FXCollections.observableArrayList(FieldAction.values()));
        combo.setValue(FieldAction.EDIT);
        return combo;
    }

    private static Label fieldLabel(String field) { return new Label(fieldName(field)); }

    private static TextField text(String value) {
        var input = new TextField(Objects.toString(value, ""));
        GridPane.setHgrow(input, Priority.ALWAYS);
        return input;
    }

    private static GridPane form() {
        var grid = new GridPane();
        grid.setHgap(9);
        grid.setVgap(8);
        grid.setPadding(new Insets(3));
        return grid;
    }

    private static ScrollPane scroll(Node node) {
        var scroll = new ScrollPane(node);
        scroll.setFitToWidth(true);
        scroll.setPrefViewportHeight(460);
        return scroll;
    }

    private static Optional<Edit> show(Window owner, String name, Node information, Node form, Supplier<Edit> collect) {
        var dialog = new Dialog<Edit>();
        if (owner != null) dialog.initOwner(owner);
        dialog.setTitle(name);
        dialog.setResizable(true);
        var pane = dialog.getDialogPane();
        pane.setPrefWidth(620);
        pane.getButtonTypes().setAll(ButtonType.CANCEL, SAVE);
        var notice = new Label();
        notice.setWrapText(true);
        notice.setStyle("-fx-text-fill: #9b291b");
        notice.managedProperty().bind(notice.textProperty().isNotEmpty());
        var content = new VBox(10, information, form, notice);
        VBox.setVgrow(form, Priority.ALWAYS);
        pane.setContent(content);
        var result = new Edit[1];
        pane.lookupButton(SAVE).addEventFilter(ActionEvent.ACTION, event -> {
            try {
                result[0] = collect.get();
            } catch (IllegalArgumentException invalid) {
                notice.setText(invalid.getMessage());
                event.consume();
            }
        });
        dialog.setResultConverter(button -> button == SAVE ? result[0] : null);
        return dialog.showAndWait();
    }

    private static final class CaptionInput {
        private final TextArea field = new TextArea();

        private CaptionInput(ExportSnapshot.AttachmentPage page) {
            field.setText(Objects.toString(page.caption(), ""));
            field.setPrefRowCount(2);
            field.setWrapText(true);
            field.setId("fee-attachment-caption");
        }
    }
}
