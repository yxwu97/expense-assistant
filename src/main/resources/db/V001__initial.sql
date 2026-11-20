CREATE TABLE reimbursement_case (
    id TEXT PRIMARY KEY NOT NULL,
    batch_real_path TEXT NOT NULL,
    case_real_path TEXT NOT NULL,
    batch_path_key TEXT NOT NULL,
    case_path_key TEXT NOT NULL,
    type TEXT,
    type_origin TEXT NOT NULL DEFAULT 'UNKNOWN',
    revision INTEGER NOT NULL DEFAULT 0 CHECK (revision >= 0),
    template_hash TEXT,
    rule_version TEXT NOT NULL,
    output_root TEXT,
    current_export_id TEXT,
    scan_generation INTEGER NOT NULL DEFAULT 0 CHECK (scan_generation >= 0),
    UNIQUE (batch_path_key, case_path_key),
    FOREIGN KEY (id, current_export_id) REFERENCES export_record (case_id, export_id)
);

CREATE TABLE source_document (
    doc_id TEXT PRIMARY KEY NOT NULL,
    case_id TEXT NOT NULL REFERENCES reimbursement_case (id) ON DELETE CASCADE,
    sha256 TEXT NOT NULL,
    format TEXT NOT NULL,
    page_count INTEGER CHECK (page_count >= 0),
    parse_state TEXT NOT NULL DEFAULT 'PENDING',
    UNIQUE (case_id, doc_id),
    UNIQUE (case_id, sha256)
);

CREATE TABLE source_path (
    path_id INTEGER PRIMARY KEY,
    case_id TEXT NOT NULL REFERENCES reimbursement_case (id) ON DELETE CASCADE,
    doc_id TEXT,
    relative_path TEXT NOT NULL,
    path_key TEXT NOT NULL,
    present INTEGER NOT NULL DEFAULT 1 CHECK (present IN (0, 1)),
    scan_generation INTEGER NOT NULL,
    path_error TEXT,
    FOREIGN KEY (case_id, doc_id) REFERENCES source_document (case_id, doc_id)
);

CREATE UNIQUE INDEX source_path_active ON source_path (case_id, path_key) WHERE present = 1;
CREATE INDEX source_path_document ON source_path (case_id, doc_id);

CREATE TABLE document_page (
    case_id TEXT NOT NULL,
    doc_id TEXT NOT NULL,
    page_no INTEGER NOT NULL CHECK (page_no >= 1),
    kind TEXT NOT NULL,
    render_state TEXT NOT NULL,
    warnings TEXT NOT NULL DEFAULT '[]',
    PRIMARY KEY (doc_id, page_no),
    UNIQUE (case_id, doc_id, page_no),
    FOREIGN KEY (case_id, doc_id) REFERENCES source_document (case_id, doc_id) ON DELETE CASCADE
);

CREATE TABLE field_candidate (
    candidate_id TEXT PRIMARY KEY NOT NULL,
    case_id TEXT NOT NULL,
    doc_id TEXT NOT NULL,
    page_no INTEGER NOT NULL,
    key TEXT NOT NULL,
    raw TEXT NOT NULL,
    normalized TEXT,
    method TEXT NOT NULL,
    bbox TEXT,
    validation TEXT NOT NULL,
    parser_version TEXT NOT NULL,
    FOREIGN KEY (case_id, doc_id, page_no) REFERENCES document_page (case_id, doc_id, page_no) ON DELETE CASCADE
);

CREATE TABLE evidence_unit (
    unit_id TEXT PRIMARY KEY NOT NULL,
    case_id TEXT NOT NULL,
    doc_id TEXT NOT NULL,
    first_page INTEGER NOT NULL CHECK (first_page >= 1),
    last_page INTEGER NOT NULL CHECK (last_page >= first_page),
    kind TEXT NOT NULL,
    invoice_key TEXT,
    order_key TEXT,
    summary_key TEXT,
    face_minor INTEGER,
    currency TEXT,
    UNIQUE (case_id, unit_id),
    FOREIGN KEY (case_id, doc_id, first_page) REFERENCES document_page (case_id, doc_id, page_no) ON DELETE CASCADE,
    FOREIGN KEY (case_id, doc_id, last_page) REFERENCES document_page (case_id, doc_id, page_no) ON DELETE CASCADE
);

CREATE TABLE trip_segment (
    case_id TEXT NOT NULL,
    unit_id TEXT NOT NULL,
    sequence_no INTEGER NOT NULL CHECK (sequence_no >= 1),
    origin TEXT,
    destination TEXT,
    start_date TEXT,
    end_date TEXT,
    amount_minor INTEGER,
    distance_km TEXT,
    PRIMARY KEY (unit_id, sequence_no),
    FOREIGN KEY (case_id, unit_id) REFERENCES evidence_unit (case_id, unit_id) ON DELETE CASCADE
);

CREATE TABLE summary_reference (
    case_id TEXT NOT NULL,
    unit_id TEXT NOT NULL,
    sequence_no INTEGER NOT NULL CHECK (sequence_no >= 1),
    invoice_key TEXT NOT NULL,
    face_minor INTEGER,
    split_minor INTEGER,
    PRIMARY KEY (unit_id, sequence_no),
    FOREIGN KEY (case_id, unit_id) REFERENCES evidence_unit (case_id, unit_id) ON DELETE CASCADE
);

CREATE TABLE expense_item (
    id TEXT PRIMARY KEY NOT NULL,
    case_id TEXT NOT NULL REFERENCES reimbursement_case (id) ON DELETE CASCADE,
    subtype TEXT NOT NULL,
    start_date TEXT,
    end_date TEXT,
    description TEXT,
    stable_order INTEGER NOT NULL,
    review_reasons TEXT NOT NULL DEFAULT '[]',
    UNIQUE (case_id, id)
);

CREATE TABLE item_field (
    case_id TEXT NOT NULL,
    item_id TEXT NOT NULL,
    key TEXT NOT NULL,
    typed_value TEXT,
    state TEXT NOT NULL,
    candidate_ids TEXT NOT NULL DEFAULT '[]',
    PRIMARY KEY (item_id, key),
    FOREIGN KEY (case_id, item_id) REFERENCES expense_item (case_id, id) ON DELETE CASCADE
);

CREATE TABLE expense_amount (
    case_id TEXT NOT NULL,
    item_id TEXT NOT NULL,
    component TEXT NOT NULL,
    face_value TEXT,
    original_currency TEXT,
    confirmed_cny_minor INTEGER,
    basis TEXT NOT NULL,
    PRIMARY KEY (item_id, component),
    UNIQUE (case_id, item_id, component),
    FOREIGN KEY (case_id, item_id) REFERENCES expense_item (case_id, id) ON DELETE CASCADE
);

CREATE TABLE expense_evidence (
    case_id TEXT NOT NULL,
    item_id TEXT NOT NULL,
    unit_id TEXT NOT NULL,
    role TEXT NOT NULL,
    basis TEXT NOT NULL,
    status TEXT NOT NULL,
    PRIMARY KEY (item_id, unit_id, role),
    FOREIGN KEY (case_id, item_id) REFERENCES expense_item (case_id, id) ON DELETE CASCADE,
    FOREIGN KEY (case_id, unit_id) REFERENCES evidence_unit (case_id, unit_id) ON DELETE CASCADE
);

CREATE TABLE charge_claim (
    case_id TEXT NOT NULL,
    charge_key TEXT NOT NULL,
    owner_item_id TEXT NOT NULL,
    component TEXT NOT NULL,
    cny_minor INTEGER,
    PRIMARY KEY (case_id, charge_key),
    FOREIGN KEY (case_id, owner_item_id, component)
        REFERENCES expense_amount (case_id, item_id, component) ON DELETE CASCADE
);

CREATE TABLE field_override (
    case_id TEXT NOT NULL REFERENCES reimbursement_case (id) ON DELETE CASCADE,
    target_id TEXT NOT NULL,
    field_key TEXT NOT NULL,
    action TEXT NOT NULL,
    value TEXT,
    source_hashes TEXT NOT NULL DEFAULT '[]',
    updated_revision INTEGER NOT NULL CHECK (updated_revision >= 0),
    PRIMARY KEY (case_id, target_id, field_key)
);

CREATE TABLE attachment_setting (
    case_id TEXT NOT NULL,
    doc_id TEXT NOT NULL,
    first_page INTEGER NOT NULL CHECK (first_page >= 1),
    last_page INTEGER NOT NULL CHECK (last_page >= first_page),
    caption TEXT,
    layout TEXT,
    order_key INTEGER,
    exclusion_reason TEXT,
    PRIMARY KEY (doc_id, first_page, last_page),
    FOREIGN KEY (case_id, doc_id) REFERENCES source_document (case_id, doc_id) ON DELETE CASCADE
);

CREATE TABLE output_root (
    root_id TEXT PRIMARY KEY NOT NULL,
    batch_real_path TEXT,
    root_real_path TEXT NOT NULL,
    root_path_key TEXT NOT NULL UNIQUE,
    marker_version INTEGER
);

CREATE TABLE export_record (
    export_id TEXT PRIMARY KEY NOT NULL,
    case_id TEXT NOT NULL REFERENCES reimbursement_case (id) ON DELETE CASCADE,
    revision INTEGER NOT NULL CHECK (revision >= 0),
    request_id TEXT NOT NULL,
    request_fingerprint TEXT NOT NULL,
    retry_of TEXT,
    rule_version TEXT NOT NULL,
    template_hash TEXT NOT NULL,
    snapshot_json TEXT NOT NULL,
    state TEXT NOT NULL CHECK (state IN ('GENERATING', 'READY_TO_PUBLISH', 'SUCCEEDED', 'FAILED', 'CONFLICT')),
    staging_path TEXT NOT NULL UNIQUE,
    final_path TEXT NOT NULL UNIQUE,
    error TEXT,
    created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    UNIQUE (case_id, export_id),
    UNIQUE (case_id, request_id),
    FOREIGN KEY (case_id, retry_of) REFERENCES export_record (case_id, export_id)
);

CREATE UNIQUE INDEX export_record_active ON export_record (case_id)
    WHERE state IN ('GENERATING', 'READY_TO_PUBLISH');

CREATE TABLE export_file (
    export_id TEXT NOT NULL REFERENCES export_record (export_id) ON DELETE CASCADE,
    kind TEXT NOT NULL CHECK (kind IN ('XLSX', 'DOCX')),
    file_name TEXT NOT NULL,
    sha256 TEXT NOT NULL,
    size INTEGER NOT NULL CHECK (size > 0),
    PRIMARY KEY (export_id, kind),
    UNIQUE (export_id, file_name)
);
