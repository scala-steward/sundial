# --- !Ups

CREATE TABLE sundial_lock_lease
(
  lease_id UUID PRIMARY KEY NOT NULL,
  lease_range TSRANGE NOT NULL,
  client_id UUID NOT NULL,
  client_hostname TEXT,
  client_ip_address INET,
  client_process TEXT,
  EXCLUDE USING gist (lease_range WITH &&),
  CHECK (lower_inc(lease_range) AND NOT upper_inc(lease_range))
);

CREATE TYPE process_overlap_action AS ENUM ('wait', 'terminate', 'overlap');
CREATE TABLE process_definition
(
  process_definition_name TEXT PRIMARY KEY NOT NULL,
  description             TEXT,
  schedule                JSONB,
  overlap_action          process_overlap_action NOT NULL,
  teams                   JSONB,
  is_disabled             BOOLEAN,
  created_at              TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE task_definition
(
  task_definition_name     TEXT NOT NULL,
  process_definition_name  TEXT NOT NULL,
  executable               JSONB NOT NULL,
  max_attempts             INT NOT NULL,
  max_execution_time       INT,
  backoff_seconds          INT NOT NULL,
  backoff_exponent         DOUBLE PRECISION NOT NULL,
  required_dependencies    TEXT[],
  optional_dependencies    TEXT[],
  require_explicit_success BOOLEAN,
  PRIMARY KEY (process_definition_name, task_definition_name)
);
CREATE INDEX task_definition_process_def_name_idx ON task_definition (process_definition_name);

CREATE TABLE task_log
(
  task_log_id UUID PRIMARY KEY NOT NULL,
  task_id     UUID NOT NULL,
  when_       TIMESTAMP WITH TIME ZONE NOT NULL,
  source      TEXT NOT NULL,
  message     TEXT NOT NULL
);
CREATE INDEX task_log_task_id_idx ON task_log (task_id);

CREATE TYPE process_status AS ENUM ('running', 'succeeded', 'failed');
CREATE TABLE process
(
  process_id              UUID PRIMARY KEY NOT NULL,
  process_definition_name TEXT NOT NULL,
  started_at              TIMESTAMP WITH TIME ZONE NOT NULL,
  status                  process_status NOT NULL,
  ended_at                TIMESTAMP WITH TIME ZONE,
  task_filter             TEXT[]
);
CREATE INDEX process_process_def_name_idx ON process(process_definition_name, started_at DESC); -- for loadMostRecentProcess
CREATE INDEX process_running_idx ON process(started_at DESC) WHERE status = 'running'; -- for loadRunningProcesses

CREATE TYPE task_status AS ENUM ('running', 'succeeded', 'failed');
CREATE TABLE task
(
  task_id                 UUID PRIMARY KEY NOT NULL,
  process_id              UUID NOT NULL,
  process_definition_name TEXT NOT NULL,
  task_definition_name    TEXT NOT NULL,
  executable              JSONB NOT NULL,
  previous_attempts       INT NOT NULL,
  started_at              TIMESTAMP WITH TIME ZONE NOT NULL,
  status                  task_status NOT NULL,
  reason                  TEXT,
  ended_at                TIMESTAMP WITH TIME ZONE
);
CREATE INDEX task_process_idx ON task(process_id); -- for loadTasksForProcess

CREATE TABLE reported_task_status
(
  task_id  UUID PRIMARY KEY NOT NULL,
  status   task_status NOT NULL,
  reason   TEXT,
  ended_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE task_metadata
(
  entry_id UUID PRIMARY KEY NOT NULL,
  task_id  UUID NOT NULL,
  when_    TIMESTAMP WITH TIME ZONE NOT NULL,
  key_     TEXT NOT NULL,
  value    TEXT NOT NULL
);
CREATE INDEX tak_metadata_task_idx ON task_metadata(task_id);

CREATE TABLE task_trigger_request
(
  request_id              UUID PRIMARY KEY NOT NULL,
  process_definition_name TEXT NOT NULL,
  task_definition_name    TEXT NOT NULL,
  requested_at            TIMESTAMP WITH TIME ZONE NOT NULL,
  started_process_id      UUID
);
CREATE INDEX task_trigger_request_processdef_name_time_idx ON task_trigger_request(process_definition_name, requested_at DESC);
CREATE INDEX task_trigger_request_processdef_name_incomplete_idx ON task_trigger_request(process_definition_name) WHERE started_process_id IS NULL;

CREATE TABLE process_trigger_request
(
  request_id              UUID PRIMARY KEY NOT NULL,
  process_definition_name TEXT NOT NULL,
  requested_at            TIMESTAMP WITH TIME ZONE NOT NULL,
  started_process_id      UUID,
  task_filter             TEXT[]
);
CREATE INDEX process_trigger_request_processdef_name_time_idx ON process_trigger_request(process_definition_name, requested_at DESC);
CREATE INDEX process_trigger_request_processdef_name_incomplete_idx ON process_trigger_request(process_definition_name) WHERE started_process_id IS NULL;

CREATE TABLE kill_process_request
(
  request_id   UUID PRIMARY KEY NOT NULL,
  process_id   UUID NOT NULL,
  requested_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX kill_process_request_process_id ON kill_process_request(process_id);

CREATE TYPE task_executor_status AS ENUM ('initializing', 'running', 'completed', 'fault');

CREATE TABLE shell_command_state
(
  task_id UUID PRIMARY KEY NOT NULL,
  as_of   TIMESTAMP WITH TIME ZONE NOT NULL,
  status  task_executor_status NOT NULL
);

CREATE TABLE container_service_state
(
  task_id UUID PRIMARY KEY NOT NULL,
  as_of   TIMESTAMP WITH TIME ZONE NOT NULL,
  status  task_executor_status NOT NULL,
  task_arn TEXT
);

# --- !Downs

DROP TABLE IF EXISTS public.sundial_lock_lease;
DROP TABLE IF EXISTS public.process_definition;
DROP TABLE IF EXISTS public.task_definition;
DROP TABLE IF EXISTS public.task_log;
DROP TABLE IF EXISTS public.process;
DROP TABLE IF EXISTS public.task;
DROP TABLE IF EXISTS public.reported_task_status;
DROP TABLE IF EXISTS public.task_metadata;
DROP TABLE IF EXISTS public.task_trigger_request;
DROP TABLE IF EXISTS public.process_trigger_request;
DROP TABLE IF EXISTS public.kill_process_request;
DROP TABLE IF EXISTS public.shell_command_state;
DROP TABLE IF EXISTS public.container_service_state;

DROP TYPE IF EXISTS public.process_overlap_action;
DROP TYPE IF EXISTS public.process_status;
DROP TYPE IF EXISTS public.task_status;
DROP TYPE IF EXISTS public.task_executor_status;

