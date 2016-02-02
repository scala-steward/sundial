# --- !Ups

ALTER TABLE task_definition RENAME TO task_definition_template;

CREATE TABLE task_definition
(
  task_definition_name     TEXT NOT NULL,
  process_id               UUID NOT NULL,
  executable               JSONB NOT NULL,
  max_attempts             INT NOT NULL,
  max_execution_time       INT,
  backoff_seconds          INT NOT NULL,
  backoff_exponent         DOUBLE PRECISION NOT NULL,
  required_dependencies    TEXT[],
  optional_dependencies    TEXT[],
  require_explicit_success BOOLEAN,
  PRIMARY KEY (process_id, task_definition_name)
);
CREATE INDEX task_definition_process_id_idx ON task_definition (process_id);

# --- !Downs

DROP TABLE IF EXISTS public.task_definition;

ALTER TABLE task_definition_template RENAME TO task_definition;