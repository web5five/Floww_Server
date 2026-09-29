CREATE TABLE executions (
                            id UUID PRIMARY KEY,
                            owner_id VARCHAR(64) NOT NULL,
                            idempotency_key VARCHAR(128) NOT NULL,
                            request_hash CHAR(64) NOT NULL,
                            max_total NUMERIC(20,8) NOT NULL CHECK (max_total > 0),
                            currency VARCHAR(16) NOT NULL,
                            allowed_recipient VARCHAR(128) NOT NULL,
                            expires_at TIMESTAMPTZ NOT NULL,
                            status VARCHAR(32) NOT NULL,
                            goal VARCHAR(500),
                            item_id VARCHAR(128),
                            created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                            updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                            UNIQUE (owner_id, idempotency_key)
);

CREATE INDEX executions_owner_created_idx
    ON executions(owner_id, created_at DESC, id DESC);


CREATE TABLE execution_events (
                                  seq BIGSERIAL PRIMARY KEY,
                                  execution_id UUID NOT NULL REFERENCES executions(id),
                                  kind VARCHAR(48) NOT NULL,
                                  payload JSONB NOT NULL,
                                  schema_version INTEGER NOT NULL DEFAULT 1,
                                  actor VARCHAR(32) NOT NULL DEFAULT 'legacy_server',
                                  source VARCHAR(64) NOT NULL DEFAULT 'floww_server',
                                  correlation_id UUID,
                                  tool_call_id VARCHAR(128),
                                  evidence_mode VARCHAR(64) NOT NULL DEFAULT 'local_precheck',
                                  model_evidence_mode VARCHAR(64),
                                  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX execution_events_execution_seq_idx
    ON execution_events(execution_id, seq);


CREATE TABLE execution_quotes (
                                  execution_id UUID NOT NULL REFERENCES executions(id),
                                  quote_id VARCHAR(128) NOT NULL,
                                  offer_id VARCHAR(128) NOT NULL,
                                  item_id VARCHAR(128) NOT NULL,
                                  total_cost NUMERIC(20,8) NOT NULL,
                                  currency VARCHAR(16) NOT NULL,
                                  recipient VARCHAR(128) NOT NULL,
                                  expires_at TIMESTAMPTZ NOT NULL,
                                  evidence_mode VARCHAR(64) NOT NULL,
                                  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                                  PRIMARY KEY (execution_id, quote_id)
);


CREATE TABLE authoritative_facts (
                                     execution_id UUID NOT NULL REFERENCES executions(id),
                                     source_system VARCHAR(128) NOT NULL,
                                     source_fact_id VARCHAR(128) NOT NULL,
                                     fact_type VARCHAR(64) NOT NULL,
                                     payload JSONB NOT NULL,
                                     received_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                                     PRIMARY KEY (source_system, source_fact_id)
);